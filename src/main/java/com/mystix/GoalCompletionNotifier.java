package com.mystix;

import com.mystix.model.RoadmapGoal;
import java.io.File;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ScheduledExecutorService;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.SoundEffectID;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetModalMode;
import net.runelite.api.WidgetNode;
import net.runelite.client.RuneLite;
import net.runelite.client.audio.AudioPlayer;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.util.Text;

/**
 * Shows the game's own notification popup (the one used for collection log and
 * combat task completions) when a roadmap goal is completed, and plays a sound.
 *
 * <p>The popup is the {@code NOTIFICATION_DISPLAY} interface opened under the
 * current top-level layout's notifications component and initialised by its
 * script; it animates in on its own and is closed once its container collapses.
 * Popups queue so two quick completions show one after the other.
 */
@Slf4j
@Singleton
public class GoalCompletionNotifier {
	/** Fills the notification interface with a title and body; RuneLite's
	 * {@code ScriptID} only names the follow-up open/delay scripts (3346/3347). */
	static final int NOTIFICATION_DISPLAY_INIT = 3343;
	/** In-game sound effect for the default option. */
	static final int DEFAULT_SOUND_ID = SoundEffectID.GE_ADD_OFFER_DINGALING;
	static final String CUSTOM_SOUND_PATH = "mystix/goal-complete.wav";
	static final String TITLE = "<col=f28c28>Roadmap Goal Complete!</col>";

	private final Client client;
	private final ClientThread clientThread;
	private final MystixConfig config;
	private final AudioPlayer audioPlayer;
	private final ScheduledExecutorService executorService;

	/** A popup that is queued or on screen, with the roadmap progress to draw under it. */
	public static final class Popup {
		private final String body;
		private final String roadmapTitle;
		private final int goalsDone;
		private final int goalsTotal;

		Popup(String body, String roadmapTitle, int goalsDone, int goalsTotal) {
			this.body = body;
			this.roadmapTitle = roadmapTitle;
			this.goalsDone = goalsDone;
			this.goalsTotal = goalsTotal;
		}

		public String getRoadmapTitle() {
			return roadmapTitle;
		}

		public int getGoalsDone() {
			return goalsDone;
		}

		public int getGoalsTotal() {
			return goalsTotal;
		}
	}

	private final Deque<Popup> queue = new ArrayDeque<>();
	private WidgetNode openNode;
	/** The popup currently on screen (read by the overlay on the render thread). */
	private volatile Popup active;

	@Inject
	public GoalCompletionNotifier(
			Client client,
			ClientThread clientThread,
			MystixConfig config,
			AudioPlayer audioPlayer,
			ScheduledExecutorService executorService) {
		this.client = client;
		this.clientThread = clientThread;
		this.config = config;
		this.audioPlayer = audioPlayer;
		this.executorService = executorService;
	}

	/**
	 * Queues a popup for the goal; safe to call from any thread.
	 *
	 * @param goalsDone  completed goals in the roadmap (including this one)
	 * @param goalsTotal goals in the roadmap
	 */
	public void show(RoadmapGoal goal, String roadmapTitle, int goalsDone, int goalsTotal) {
		if (goal == null || goal.getName() == null) {
			return;
		}
		Popup popup = new Popup(body(goal.getName(), roadmapTitle, goalsDone, goalsTotal),
				roadmapTitle, goalsDone, goalsTotal);
		clientThread.invokeLater(() -> {
			if (!config.showGoalPopup() || client.getGameState() != GameState.LOGGED_IN) {
				return;
			}
			queue.add(popup);
			if (openNode == null) {
				showNext();
			}
		});
	}

	/** The popup on screen right now, or null. */
	public Popup getActive() {
		return active;
	}

	/** Drops queued popups and closes an open one (plugin shutdown). */
	public void clear() {
		clientThread.invokeLater(() -> {
			queue.clear();
			closeOpen();
		});
	}

	/** Blank lines reserved at the bottom of the body so the roadmap progress bar
	 * ({@link GoalCompletionOverlay}) can be drawn inside the box. */
	static final String PROGRESS_SPACER = "<br><br>";

	/** Builds the popup body: the goal name in white, the roadmap title beneath,
	 * then reserved space for the progress bar when the roadmap has goals. */
	static String body(String goalName, String roadmapTitle, int goalsDone, int goalsTotal) {
		String name = Text.removeTags(goalName);
		StringBuilder sb = new StringBuilder("<col=ffffff>").append(name).append("</col>");
		if (roadmapTitle != null && !roadmapTitle.trim().isEmpty()) {
			sb.append("<br><col=c6c6c6>").append(Text.removeTags(roadmapTitle)).append("</col>");
		}
		if (goalsTotal > 0) {
			sb.append(PROGRESS_SPACER);
		}
		return sb.toString();
	}

	/** The notifications component of the given top-level layout, or -1 when the
	 * layout has no popup slot the plugin knows about. */
	static int parentComponentFor(int topLevelInterfaceId) {
		switch (topLevelInterfaceId) {
			case InterfaceID.TOPLEVEL_OSRS_STRETCH:
				return InterfaceID.ToplevelOsrsStretch.NOTIFICATIONS;
			case InterfaceID.TOPLEVEL_PRE_EOC:
				return InterfaceID.ToplevelPreEoc.NOTIFICATIONS;
			case InterfaceID.TOPLEVEL_DISPLAY:
				return InterfaceID.ToplevelDisplay.NOTIFICATIONS;
			case InterfaceID.TOPLEVEL:
				return InterfaceID.Toplevel.NOTIFICATIONS;
			default:
				return -1;
		}
	}

	// Client thread from here down.

	private void showNext() {
		Popup popup = queue.poll();
		if (popup == null) {
			return;
		}
		int parent = parentComponentFor(client.getTopLevelInterfaceId());
		if (parent == -1) {
			log.debug("No notification slot for top-level interface {}", client.getTopLevelInterfaceId());
			queue.clear();
			return;
		}
		try {
			openNode = client.openInterface(parent, InterfaceID.NOTIFICATION_DISPLAY, WidgetModalMode.MODAL_CLICKTHROUGH);
			client.runScript(NOTIFICATION_DISPLAY_INIT, TITLE, popup.body, -1);
		} catch (RuntimeException e) {
			log.debug("Goal popup could not be opened", e);
			openNode = null;
			return;
		}
		active = popup;
		playSound();
		clientThread.invokeLater(this::pollClosed);
	}

	/** Returns false to be invoked again next tick while the popup is still visible. */
	private boolean pollClosed() {
		if (openNode == null) {
			return true;
		}
		Widget container = client.getWidget(InterfaceID.NotificationDisplay.CONTAINER);
		if (container != null && container.getWidth() > 0) {
			return false;
		}
		closeOpen();
		showNext();
		return true;
	}

	private void closeOpen() {
		active = null;
		if (openNode == null) {
			return;
		}
		try {
			client.closeInterface(openNode, true);
		} catch (RuntimeException e) {
			log.debug("Goal popup was already closed");
		}
		openNode = null;
	}

	private void playSound() {
		GoalCompleteSound sound = config.goalCompleteSound();
		if (sound == null || sound == GoalCompleteSound.OFF) {
			return;
		}
		int volume = client.getPreferences().getSoundEffectVolume();
		if (volume <= 0) {
			return;
		}
		if (sound == GoalCompleteSound.IN_GAME) {
			client.playSoundEffect(DEFAULT_SOUND_ID, volume);
			return;
		}
		File file = new File(RuneLite.RUNELITE_DIR, CUSTOM_SOUND_PATH);
		if (!file.isFile()) {
			log.debug("Custom goal sound not found at {}", file);
			return;
		}
		// Game sound effect volume is 0-127; map to a gain in dB like RuneLite's Notifier.
		float gainDb = (float) (20d * Math.log10(volume / 127d));
		executorService.execute(() -> {
			try {
				audioPlayer.play(file, gainDb);
			} catch (Exception e) {
				log.debug("Custom goal sound could not be played", e);
			}
		});
	}
}
