package com.mystix;

import com.google.gson.Gson;
import com.mystix.api.MystixApiClient;
import com.mystix.model.QuestsSyncPayload;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Quest;
import net.runelite.api.QuestState;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.ScriptCallbackEvent;
import net.runelite.api.events.VarbitChanged;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.api.ScriptID;
import net.runelite.api.gameval.DBTableID;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.eventbus.Subscribe;

/**
 * Monitors the player's quest progress and syncs it to the Mystix API.
 *
 * <p>Reads every quest's state via RuneLite's {@link Quest} enum + {@link QuestState}
 * (the same source WikiSync uses) and pushes a {questName: status} map where status is
 * 0 = not started, 1 = in progress, 2 = completed. Using {@link Quest#getName()} for the
 * keys reproduces WikiSync's exact names (including the Recipe for Disaster subquests),
 * so the backend consumes the payload unchanged.
 *
 * <p>Triggers: a sync on login (after a short delay so quest varps have loaded) and on
 * logout, plus a near-real-time sync when a quest completes. Quest state changes flip a
 * varp, so we mark a re-check on {@link VarbitChanged} and read+dedupe on the next
 * {@link GameTick} (throttled), which collapses varp churn to at most one read per few
 * ticks. A JSON equality check means an unchanged quest set is never resent.
 *
 * <p>A quest RuneLite's {@link Quest} enum doesn't list yet (a brand-new release) is
 * invisible to that read, so two more sources fill the gap. The game builds its quest
 * list from a database table and RuneLite's client fires a {@code questFilter} script
 * callback for each row it lays out; the row ids collected there give every quest's
 * display name and, through the same script the enum uses, its state. And the
 * quest-complete scroll names any quest as it finishes, so a name read from it is
 * carried as completed for the rest of the login session. The scroll text is parsed
 * the way RuneLite's own screenshot plugin parses it.
 */
@Slf4j
@Singleton
public class QuestMonitor {
	private static final int LOGIN_SYNC_DELAY_SECONDS = 3;
	// Minimum ticks between varp-driven reads, so continuous varp churn during play
	// doesn't re-read every quest every tick. A quest completion still syncs within
	// a few ticks, and login/logout syncs are the safety net.
	private static final int RESYNC_THROTTLE_TICKS = 3;

	// "You have completed The Corsair Curse!" / "'One Small Favour' completed!"
	private static final Pattern QUEST_PATTERN_1 = Pattern.compile(
			".+?ve\\.*? (?<verb>been|rebuilt|.+?ed)? ?(?:the )?'?(?<quest>.+?)'?(?: [Qq]uest)?[!.]?$");
	private static final Pattern QUEST_PATTERN_2 = Pattern.compile(
			"'?(?<quest>.+?)'?(?: [Qq]uest)? (?<verb>[a-z]\\w+?ed)?(?: f.*?)?[!.]?$");
	// Recipe for Disaster subquests announce "You have freed/defeated/saved ...".
	private static final List<String> RFD_TAGS = Arrays.asList("Another Cook", "freed", "defeated", "saved");
	// Quests whose name ends in "Quest", which the patterns strip.
	private static final List<String> WORD_QUEST_IN_NAME_TAGS = Arrays.asList(
			"Another Cook", "Doric", "Heroes", "Legends", "Observatory", "Olaf", "Waterfall");

	private final Client client;
	private final ClientThread clientThread;
	private final MystixConfig config;
	private final MystixApiClient apiClient;
	private final ScheduledExecutorService executorService;
	private final Gson gson;

	private GameState previousGameState = GameState.UNKNOWN;
	private boolean questCheckPending;
	private boolean questScrollPending;
	/** Quest names read from the quest-complete scroll this login session. */
	private final Set<String> completedFromScroll = new HashSet<>();
	/** Quest table row ids seen while the game laid out its quest list. */
	private final Set<Integer> questRows = new HashSet<>();
	private int lastReadTick = -1;
	private String lastSyncJson;

	@Inject
	public QuestMonitor(
			Client client,
			ClientThread clientThread,
			MystixConfig config,
			MystixApiClient apiClient,
			ScheduledExecutorService executorService,
			Gson gson) {
		this.client = client;
		this.clientThread = clientThread;
		this.config = config;
		this.apiClient = apiClient;
		this.executorService = executorService;
		this.gson = gson;
	}

	/** Receives every quest read (RuneLite quest name to status 0/1/2); set by the plugin. */
	private volatile Consumer<Map<String, Integer>> statesListener;

	public void setStatesListener(Consumer<Map<String, Integer>> listener) {
		this.statesListener = listener;
	}

	/** Invoked after each upload so roadmap progress can be re-read; set by the plugin. */
	private volatile Runnable syncedListener;

	public void setSyncedListener(Runnable listener) {
		this.syncedListener = listener;
	}

	private void notifySynced() {
		Runnable listener = syncedListener;
		if (listener != null) {
			listener.run();
		}
	}

	public void stop() {
		previousGameState = GameState.UNKNOWN;
		questCheckPending = false;
		questScrollPending = false;
		completedFromScroll.clear();
		questRows.clear();
		lastReadTick = -1;
		lastSyncJson = null;
	}

	/**
	 * Re-reads and re-pushes the current quest states on the client thread. Used by the
	 * roadmap panel's "Sync &amp; refresh"; clears the dedup cache so an unchanged set
	 * still sends.
	 */
	public void forceSync() {
		clientThread.invokeLater(() -> {
			lastSyncJson = null;
			syncQuests();
		});
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event) {
		GameState newState = event.getGameState();
		if (newState == GameState.LOGGED_IN && previousGameState != GameState.LOGGED_IN) {
			// Quest varps load shortly after LOGGED_IN, so wait before reading.
			executorService.schedule(() -> clientThread.invokeLater(this::syncQuests),
					LOGIN_SYNC_DELAY_SECONDS, TimeUnit.SECONDS);
		} else if (previousGameState == GameState.LOGGED_IN && newState != GameState.LOGGED_IN) {
			// Logging out: flush final state (this handler runs on the client thread).
			syncQuests();
		}
		if (newState == GameState.LOGIN_SCREEN) {
			// Scroll completions belong to the account that was logged in.
			completedFromScroll.clear();
		}
		previousGameState = newState;
	}

	/**
	 * The game lays out its quest list from the quest table, and RuneLite's client
	 * calls back once per row with the row id on top of the int stack (the row below it
	 * is the hide flag, left untouched). Rows are only collected here; names and states
	 * are read on the next sync.
	 */
	@Subscribe
	public void onScriptCallbackEvent(ScriptCallbackEvent event) {
		if (!"questFilter".equals(event.getEventName())) {
			return;
		}
		int[] intStack = client.getIntStack();
		int size = client.getIntStackSize();
		if (size < 1) {
			return;
		}
		if (questRows.add(intStack[size - 1])) {
			questCheckPending = true;
		}
	}

	/** The quest-complete scroll opened; read its title once its text is set. */
	@Subscribe
	public void onWidgetLoaded(WidgetLoaded event) {
		if (event.getGroupId() == InterfaceID.QUESTSCROLL) {
			questScrollPending = true;
		}
	}

	/** A quest state change flips a varp; mark a re-check for the next game tick. */
	@Subscribe
	public void onVarbitChanged(VarbitChanged event) {
		questCheckPending = true;
	}

	@Subscribe
	public void onGameTick(GameTick event) {
		if (questScrollPending) {
			questScrollPending = false;
			readQuestScroll();
		}
		if (!questCheckPending) {
			return;
		}
		if (lastReadTick != -1 && client.getTickCount() - lastReadTick < RESYNC_THROTTLE_TICKS) {
			return;
		}
		questCheckPending = false;
		lastReadTick = client.getTickCount();
		syncQuests();
	}

	/** Reads the quest name off the quest-complete scroll and syncs it as completed. */
	private void readQuestScroll() {
		Widget title = client.getWidget(InterfaceID.Questscroll.QUEST_TITLE);
		String name = title == null ? null : parseQuestCompletedScroll(title.getText());
		if (name == null) {
			return;
		}
		log.debug("Quest complete scroll names '{}'", name);
		if (completedFromScroll.add(name)) {
			questCheckPending = false;
			lastReadTick = client.getTickCount();
			syncQuests();
		}
	}

	/**
	 * The quest named by the quest-complete scroll's title text, or null when the text
	 * isn't a completion. Mirrors RuneLite's screenshot plugin: Recipe for Disaster
	 * subquests are prefixed, and names the scroll shortens get their "Quest" back.
	 */
	static String parseQuestCompletedScroll(String text) {
		if (text == null) {
			return null;
		}
		Matcher m1 = QUEST_PATTERN_1.matcher(text);
		Matcher m2 = QUEST_PATTERN_2.matcher(text);
		Matcher match = m1.matches() ? m1 : m2;
		if (!match.matches()) {
			return null;
		}
		String quest = match.group("quest");
		String verb = match.group("verb") != null ? match.group("verb") : "";
		if (verb.contains("kind of")) {
			return null; // a partial completion is not a completion
		}
		if (verb.contains("completely")) {
			quest += " II";
		}
		String questAndVerb = quest + verb;
		if (RFD_TAGS.stream().anyMatch(questAndVerb::contains)) {
			quest = "Recipe for Disaster - " + quest;
		}
		if (WORD_QUEST_IN_NAME_TAGS.stream().anyMatch(quest::contains)) {
			quest += " Quest";
		}
		return quest;
	}

	/** Reads all quest states, builds the payload, and syncs (deduped). Client thread only. */
	private void syncQuests() {
		if (!config.syncQuests() || !SyncGuard.hasAppKey(config)) {
			return;
		}
		if (GameModeUtil.isSpecialGameMode(client)) {
			return;
		}
		String playerUsername = SyncGuard.getPlayerUsername(client);
		if (playerUsername == null) {
			return;
		}

		// Sorted keys give a stable JSON ordering so the dedup check is reliable.
		Map<String, Integer> questStates = new TreeMap<>();
		for (Quest quest : Quest.values()) {
			questStates.put(quest.getName(), toStatus(quest.getState(client)));
		}
		// Quests the enum doesn't list, read from the game's own quest table; the
		// enum's read wins for names it has.
		for (int row : questRows) {
			String name = questRowName(row);
			if (name != null && !questStates.containsKey(name)) {
				questStates.put(name, questRowStatus(row));
			}
		}
		// Completions the enum can't see yet; the enum's own read wins for names it has.
		for (String name : completedFromScroll) {
			questStates.putIfAbsent(name, 2);
		}

		Consumer<Map<String, Integer>> states = statesListener;
		if (states != null) {
			states.accept(questStates);
		}

		QuestsSyncPayload payload = new QuestsSyncPayload(playerUsername, questStates);
		String json = payload.toJson(gson);

		if (json.equals(lastSyncJson)) {
			log.debug("Quests unchanged, skipping sync");
			return;
		}
		lastSyncJson = json;
		log.debug("Syncing {} quests for player: {}", questStates.size(), playerUsername);
		apiClient.sendQuestsSync(payload);
		notifySynced();
	}

	/** A quest row's display name, or null when the row can't be read. */
	private String questRowName(int row) {
		try {
			Object[] field = client.getDBTableField(row, DBTableID.Quest.COL_DISPLAYNAME, 0);
			return field != null && field.length > 0 && field[0] instanceof String ? (String) field[0] : null;
		} catch (RuntimeException e) {
			log.debug("Quest row {} has no readable name", row, e);
			return null;
		}
	}

	/** A quest row's status (0/1/2), read the same way {@link Quest#getState} does. */
	private int questRowStatus(int row) {
		client.runScript(ScriptID.QUEST_STATUS_GET, row);
		return statusFromScript(client.getIntStack()[0]);
	}

	/**
	 * Maps the raw {@code QUEST_STATUS_GET} result to the WikiSync status code, mirroring
	 * {@link Quest#getState}: 2 is finished, 1 is not started, anything else in progress.
	 */
	static int statusFromScript(int raw) {
		if (raw == 2) {
			return 2;
		}
		return raw == 1 ? 0 : 1;
	}

	/** Maps a RuneLite {@link QuestState} to the WikiSync status code (0/1/2). */
	private static int toStatus(QuestState state) {
		if (state == QuestState.FINISHED) {
			return 2;
		}
		if (state == QuestState.IN_PROGRESS) {
			return 1;
		}
		return 0;
	}
}
