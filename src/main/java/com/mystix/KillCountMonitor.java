package com.mystix;

import com.google.gson.Gson;
import com.mystix.api.MystixApiClient;
import com.mystix.model.KillCountsSyncPayload;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.IntUnaryOperator;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Player;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.VarbitChanged;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.chat.ChatColorType;
import net.runelite.client.chat.ChatMessageBuilder;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.chat.QueuedMessage;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.eventbus.Subscribe;

/**
 * Monitors the player's boss kill counts and syncs them to the Mystix API.
 *
 * <p>Reads RuneLite's persisted {@code killcount} RSProfile config — the store the
 * in-game {@code !kc} command reads, which RuneLite populates from the game's own
 * kill-count chat messages. That makes it the accurate, self-correcting count
 * (unlike loot-tracker-derived KC) and lets us pull the player's whole KC history
 * in one read, instead of waiting to witness a kill.
 *
 * <p>Triggers: a sync on login (after a delay so the RSProfile is resolved) and on
 * logout, plus a near-real-time sync when a kill count changes. RuneLite rewrites
 * the config on each kill, firing {@link ConfigChanged}; we mark a re-check and
 * read + dedupe on the next {@link GameTick} (throttled). A JSON equality check
 * means an unchanged set is never resent.
 *
 * <p>The game's Doom of Mokhaiotl scoreboard counts (completions per delve level and
 * the deepest level reached) ride along as extra entries, re-read whenever the game
 * updates them.
 */
@Slf4j
@Singleton
public class KillCountMonitor {
	private static final String KILLCOUNT_GROUP = "killcount";
	// The RSProfile is set a little after LOGGED_IN, so wait before the first read.
	private static final int LOGIN_SYNC_DELAY_SECONDS = 5;
	private static final int RESYNC_THROTTLE_TICKS = 3;
	// killcount keys that aren't boss KCs (Duel Arena win/loss/streak counters).
	private static final String DUEL_ARENA_PREFIX = "duel arena";
	// The game's Doom of Mokhaiotl scoreboard counts: completions per delve level (1-8)
	// and every level completed past 8.
	private static final int[] DOOM_LEVEL_VARPS = {
			VarPlayerID.DOM_LEVEL_1_COMPLETIONS, VarPlayerID.DOM_LEVEL_2_COMPLETIONS,
			VarPlayerID.DOM_LEVEL_3_COMPLETIONS, VarPlayerID.DOM_LEVEL_4_COMPLETIONS,
			VarPlayerID.DOM_LEVEL_5_COMPLETIONS, VarPlayerID.DOM_LEVEL_6_COMPLETIONS,
			VarPlayerID.DOM_LEVEL_7_COMPLETIONS, VarPlayerID.DOM_LEVEL_8_COMPLETIONS,
			VarPlayerID.DOM_LEVEL_8_PLUS_COMPLETIONS};
	private static final Set<Integer> DOOM_REGIONS = Set.of(5269, 13668, 14180);
	// The scoreboard counts can arrive a moment after reaching Doom.
	private static final int DOOM_REMINDER_DELAY_TICKS = 5;
	static final String DOOM_HISTORY_SYNCED_KEY = "doomHistorySynced";
	static final String DOOM_REMINDER_MESSAGE = "Open the Doom scoreboard once to sync your delve history to Mystix.";

	private final Client client;
	private final ClientThread clientThread;
	private final MystixConfig config;
	private final MystixApiClient apiClient;
	private final ConfigManager configManager;
	private final ScheduledExecutorService executorService;
	private final Gson gson;
	private final ChatMessageManager chatMessageManager;

	private GameState previousGameState = GameState.UNKNOWN;
	private boolean kcCheckPending;
	private int lastReadTick = -1;
	private String lastSyncJson;
	private boolean doomReminderChecked;
	private int ticksAtDoom;

	@Inject
	public KillCountMonitor(
			Client client,
			ClientThread clientThread,
			MystixConfig config,
			MystixApiClient apiClient,
			ConfigManager configManager,
			ScheduledExecutorService executorService,
			Gson gson,
			ChatMessageManager chatMessageManager) {
		this.client = client;
		this.clientThread = clientThread;
		this.config = config;
		this.apiClient = apiClient;
		this.configManager = configManager;
		this.executorService = executorService;
		this.gson = gson;
		this.chatMessageManager = chatMessageManager;
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
		kcCheckPending = false;
		lastReadTick = -1;
		lastSyncJson = null;
		doomReminderChecked = false;
		ticksAtDoom = 0;
	}

	/**
	 * Re-reads and re-pushes the stored kill counts on the client thread. Used by the
	 * roadmap panel's "Sync &amp; refresh"; clears the dedup cache so an unchanged set
	 * still sends.
	 */
	public void forceSync() {
		clientThread.invokeLater(() -> {
			lastSyncJson = null;
			syncKillCounts();
		});
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event) {
		GameState newState = event.getGameState();
		if (newState == GameState.LOGGED_IN && previousGameState != GameState.LOGGED_IN) {
			// The RSProfile the kill counts live under is resolved shortly after login.
			executorService.schedule(() -> clientThread.invokeLater(this::syncKillCounts),
					LOGIN_SYNC_DELAY_SECONDS, TimeUnit.SECONDS);
		} else if (previousGameState == GameState.LOGGED_IN && newState != GameState.LOGGED_IN) {
			// Logging out: flush final state (this handler runs on the client thread).
			syncKillCounts();
		}
		if (newState == GameState.LOGIN_SCREEN) {
			doomReminderChecked = false;
			ticksAtDoom = 0;
		}
		previousGameState = newState;
	}

	/** RuneLite rewrites a killcount config key on each kill; mark a re-check. */
	@Subscribe
	public void onConfigChanged(ConfigChanged event) {
		if (KILLCOUNT_GROUP.equals(event.getGroup())) {
			kcCheckPending = true;
		}
	}

	/** The game updates the Doom scoreboard counts as each delve level is completed. */
	@Subscribe
	public void onVarbitChanged(VarbitChanged event) {
		int varp = event.getVarpId();
		if (varp >= VarPlayerID.DOM_DEEPEST_LEVEL && varp <= VarPlayerID.DOM_LEVEL_8_PLUS_COMPLETIONS) {
			kcCheckPending = true;
		}
	}

	@Subscribe
	public void onGameTick(GameTick event) {
		checkDoomReminder();
		if (!kcCheckPending) {
			return;
		}
		if (lastReadTick != -1 && client.getTickCount() - lastReadTick < RESYNC_THROTTLE_TICKS) {
			return;
		}
		kcCheckPending = false;
		lastReadTick = client.getTickCount();
		syncKillCounts();
	}

	/** Reads RuneLite's stored kill counts, builds the payload, and syncs (deduped). */
	private void syncKillCounts() {
		if (!config.syncKillCounts() || !SyncGuard.hasAppKey(config)) {
			return;
		}
		if (GameModeUtil.isSpecialGameMode(client)) {
			return;
		}
		String playerUsername = SyncGuard.getPlayerUsername(client);
		if (playerUsername == null) {
			return;
		}
		String profile = configManager.getRSProfileKey();
		if (profile == null) {
			return;  // RSProfile not resolved yet
		}

		// TreeMap gives a stable JSON key order so the dedup check is reliable.
		Map<String, Integer> killCounts = new TreeMap<>();
		for (String boss : configManager.getRSProfileConfigurationKeys(KILLCOUNT_GROUP, profile, "")) {
			if (boss == null || boss.startsWith(DUEL_ARENA_PREFIX)) {
				continue;
			}
			Integer kc = configManager.getRSProfileConfiguration(KILLCOUNT_GROUP, boss, int.class);
			if (kc != null) {
				killCounts.put(boss, kc);
			}
		}

		Map<String, Integer> doomDelves = doomDelves(client::getVarpValue);
		killCounts.putAll(doomDelves);

		if (killCounts.isEmpty()) {
			return;
		}

		KillCountsSyncPayload payload = new KillCountsSyncPayload(playerUsername, killCounts);
		String json = payload.toJson(gson);

		if (json.equals(lastSyncJson)) {
			log.debug("Kill counts unchanged, skipping sync");
			return;
		}
		lastSyncJson = json;
		log.debug("Syncing {} kill counts for player: {}", killCounts.size(), playerUsername);
		apiClient.sendKillCountsSync(payload);
		if (!doomDelves.isEmpty() && !isDoomHistorySynced()) {
			configManager.setRSProfileConfiguration(MystixConfig.CONFIG_GROUP, DOOM_HISTORY_SYNCED_KEY, true);
		}
		notifySynced();
	}

	/**
	 * The Doom scoreboard counts above zero, keyed "doom delve 1" to "doom delve 8",
	 * "doom delve 8+" (levels completed past 8) and "doom deepest delve".
	 */
	static Map<String, Integer> doomDelves(IntUnaryOperator varps) {
		Map<String, Integer> delves = new TreeMap<>();
		for (int i = 0; i < DOOM_LEVEL_VARPS.length; i++) {
			int completions = varps.applyAsInt(DOOM_LEVEL_VARPS[i]);
			if (completions > 0) {
				delves.put("doom delve " + (i < 8 ? String.valueOf(i + 1) : "8+"), completions);
			}
		}
		int deepest = varps.applyAsInt(VarPlayerID.DOM_DEEPEST_LEVEL);
		if (deepest > 0) {
			delves.put("doom deepest delve", deepest);
		}
		return delves;
	}

	/**
	 * Once per login, a few ticks after reaching Doom of Mokhaiotl, asks the player to open
	 * the scoreboard when it still reads empty and no delve history has been sent before.
	 */
	private void checkDoomReminder() {
		if (doomReminderChecked) {
			return;
		}
		if (!isAtDoom()) {
			ticksAtDoom = 0;
			return;
		}
		if (++ticksAtDoom < DOOM_REMINDER_DELAY_TICKS || configManager.getRSProfileKey() == null) {
			return;
		}
		doomReminderChecked = true;
		if (!config.syncKillCounts() || !SyncGuard.hasAppKey(config) || GameModeUtil.isSpecialGameMode(client)) {
			return;
		}
		if (!doomDelves(client::getVarpValue).isEmpty() || isDoomHistorySynced()) {
			return;
		}
		chatMessageManager.queue(QueuedMessage.builder()
				.type(ChatMessageType.CONSOLE)
				.runeLiteFormattedMessage(new ChatMessageBuilder()
						.append(ChatColorType.HIGHLIGHT)
						.append(DOOM_REMINDER_MESSAGE)
						.build())
				.build());
	}

	private boolean isDoomHistorySynced() {
		return Boolean.TRUE.equals(configManager.getRSProfileConfiguration(
				MystixConfig.CONFIG_GROUP, DOOM_HISTORY_SYNCED_KEY, Boolean.class));
	}

	private boolean isAtDoom() {
		Player local = client.getLocalPlayer();
		if (local == null || local.getLocalLocation() == null) {
			return false;
		}
		return DOOM_REGIONS.contains(WorldPoint.fromLocalInstance(client, local.getLocalLocation()).getRegionID());
	}
}
