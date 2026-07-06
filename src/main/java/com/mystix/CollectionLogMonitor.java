package com.mystix;

import com.google.gson.Gson;
import com.mystix.api.MystixApiClient;
import com.mystix.model.CollectionLogSyncPayload;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.MenuAction;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.ScriptPreFired;
import net.runelite.api.events.ScriptPostFired;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.eventbus.Subscribe;

/**
 * Monitors the player's Collection Log and syncs obtained items to the Mystix API.
 *
 * <p>Since the 2023 collection log rework, obtained items are only materialised
 * when the collection log interface is drawn — there is no clean login-time read.
 * So the first time the player opens their collection log each session we replicate
 * WikiSync's technique: trigger the in-log "search" ({@link #SEARCH_SCRIPT}), which
 * forces the draw script ({@link #DRAW_SCRIPT}) to fire for every obtained item.
 * We collect those item IDs and push them. After that, {@link #DRAW_SCRIPT} keeps
 * firing for new-item unlocks, so incremental obtains flow in real time. A final
 * deduped sync runs on logout. No manual button, no per-page clicking.
 */
@Slf4j
@Singleton
public class CollectionLogMonitor {
	// Fired once the collection log interface has been built/opened.
	private static final int SETUP_SCRIPT = 7797;
	// Fired once per obtained item as the collection log draws its list.
	private static final int DRAW_SCRIPT = 4100;
	// The in-log "search all" script; forces a full draw of every obtained item.
	private static final int SEARCH_SCRIPT = 2240;
	// Submit two ticks after the last draw script fires (lets the burst settle).
	private static final int SYNC_DELAY_TICKS = 2;

	private final Client client;
	private final ClientThread clientThread;
	private final MystixConfig config;
	private final MystixApiClient apiClient;
	private final Gson gson;

	private final Set<Integer> obtainedItemIds = new HashSet<>();
	private GameState previousGameState = GameState.UNKNOWN;
	private boolean captureActive;
	private boolean fullReadTriggeredThisSession;
	private int tickScriptFired = -1;
	private String lastSyncJson;

	@Inject
	public CollectionLogMonitor(
			Client client,
			ClientThread clientThread,
			MystixConfig config,
			MystixApiClient apiClient,
			Gson gson) {
		this.client = client;
		this.clientThread = clientThread;
		this.config = config;
		this.apiClient = apiClient;
		this.gson = gson;
	}

	public void stop() {
		resetSessionState();
		previousGameState = GameState.UNKNOWN;
		lastSyncJson = null;
	}

	/**
	 * Re-pushes the currently captured collection log on the client thread. Used
	 * by the roadmap panel's "Sync &amp; refresh". A no-op until the log has been
	 * opened at least once this session (nothing is captured before then).
	 */
	public void forceSync() {
		clientThread.invokeLater(() -> {
			lastSyncJson = null;
			syncCollectionLog();
		});
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event) {
		GameState state = event.getGameState();
		switch (state) {
			case LOGGED_IN:
				if (previousGameState != GameState.LOGGED_IN) {
					// Fresh login: capture from now so real-time unlocks are caught
					// even before the player opens their log.
					resetSessionState();
					captureActive = true;
				}
				break;
			case HOPPING:
			case LOGGING_IN:
			case CONNECTION_LOST:
				// Clear per-account state; the next login re-enumerates on log open.
				resetSessionState();
				break;
			default:
				if (previousGameState == GameState.LOGGED_IN) {
					// Logging out: flush whatever we captured, then reset.
					syncCollectionLog();
					resetSessionState();
				}
				break;
		}
		previousGameState = state;
	}

	/**
	 * The collection log interface just finished setup. The first time this happens
	 * in a session, force a full read of every obtained item.
	 */
	@Subscribe
	public void onScriptPostFired(ScriptPostFired event) {
		if (event.getScriptId() != SETUP_SCRIPT) {
			return;
		}
		if (!config.syncCollectionLog() || !SyncGuard.hasAppKey(config)) {
			return;
		}
		if (fullReadTriggeredThisSession) {
			return;
		}
		fullReadTriggeredThisSession = true;
		captureActive = true;
		clientThread.invokeLater(this::triggerFullRead);
	}

	/**
	 * Each obtained item the collection log draws fires this script with the item
	 * id on the argument stack. Captures both the full-read burst and single-item
	 * real-time unlocks.
	 */
	@Subscribe
	public void onScriptPreFired(ScriptPreFired event) {
		if (event.getScriptId() != DRAW_SCRIPT || !captureActive) {
			return;
		}
		Object[] args = event.getScriptEvent().getArguments();
		if (args == null || args.length < 2 || !(args[1] instanceof Integer)) {
			return;
		}
		obtainedItemIds.add((Integer) args[1]);
		tickScriptFired = client.getTickCount();
	}

	/**
	 * Submit shortly after the last draw-script fire so the whole burst is captured
	 * in one payload (mirrors WikiSync's two-tick settle).
	 */
	@Subscribe
	public void onGameTick(GameTick event) {
		if (tickScriptFired == -1 || tickScriptFired + SYNC_DELAY_TICKS > client.getTickCount()) {
			return;
		}
		tickScriptFired = -1;
		syncCollectionLog();
	}

	/** Opens the in-log search and runs it, forcing a draw of every obtained item. */
	private void triggerFullRead() {
		client.menuAction(-1, InterfaceID.Collection.SEARCH_TOGGLE, MenuAction.CC_OP, 1, -1, "Search", null);
		client.runScript(SEARCH_SCRIPT);
	}

	private void syncCollectionLog() {
		if (!config.syncCollectionLog() || !SyncGuard.hasAppKey(config)) {
			return;
		}
		if (GameModeUtil.isSpecialGameMode(client)) {
			return;
		}
		String playerUsername = SyncGuard.getPlayerUsername(client);
		if (playerUsername == null || obtainedItemIds.isEmpty()) {
			return;
		}

		List<Integer> sortedIds = new ArrayList<>(obtainedItemIds);
		Collections.sort(sortedIds);
		CollectionLogSyncPayload payload = new CollectionLogSyncPayload(playerUsername, sortedIds);
		String json = payload.toJson(gson);

		if (json.equals(lastSyncJson)) {
			log.debug("Collection log unchanged, skipping sync");
			return;
		}
		lastSyncJson = json;
		log.debug("Syncing {} collection log items for player: {}", sortedIds.size(), playerUsername);
		apiClient.sendCollectionLogSync(payload);
	}

	private void resetSessionState() {
		obtainedItemIds.clear();
		captureActive = false;
		fullReadTriggeredThisSession = false;
		tickScriptFired = -1;
	}
}
