package com.mystix;

import com.google.gson.Gson;
import com.mystix.api.MystixApiClient;
import com.mystix.model.AchievementDiariesSyncPayload;
import com.mystix.model.DiaryTierResult;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.VarbitChanged;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.eventbus.Subscribe;

/**
 * Monitors the player's achievement diary completion and syncs it to the Mystix API.
 *
 * <p>Reads per-task completion from the diary varbits / varplayers (the same source
 * WikiSync uses; see {@link AchievementDiariesReader}) and pushes the WikiSync-shaped
 * {region: {tier: {complete, tasks: [bool, ...]}}} payload, where each tasks array is
 * positional in in-game varbit order so the backend consumes it unchanged.
 *
 * <p>Triggers: a sync on login (after a short delay so diary varps have loaded) and on
 * logout, plus a near-real-time sync when a diary task completes. Diary task completion
 * flips one of the diary varbits/varps, so we mark a re-check only when {@link VarbitChanged}
 * reports one of those (see {@link AchievementDiariesReader#watches}) and read+dedupe on the
 * next {@link GameTick} (throttled). Filtering to the diary vars keeps unrelated varbit churn
 * during normal play from re-reading every few ticks; login/logout remain the completeness
 * backstop. A JSON equality check means an unchanged diary set is never resent.
 */
@Slf4j
@Singleton
public class AchievementDiaryMonitor {
	private static final int LOGIN_SYNC_DELAY_SECONDS = 3;
	// Minimum ticks between varp-driven reads, so continuous varp churn during play
	// doesn't re-read every diary every tick. A diary completion still syncs within
	// a few ticks, and login/logout syncs are the safety net.
	private static final int RESYNC_THROTTLE_TICKS = 3;

	private final Client client;
	private final ClientThread clientThread;
	private final MystixConfig config;
	private final MystixApiClient apiClient;
	private final ScheduledExecutorService executorService;
	private final Gson gson;
	private final AchievementDiariesReader reader;

	private GameState previousGameState = GameState.UNKNOWN;
	private boolean diaryCheckPending;
	private int lastReadTick = -1;
	private String lastSyncJson;

	@Inject
	public AchievementDiaryMonitor(
			Client client,
			ClientThread clientThread,
			MystixConfig config,
			MystixApiClient apiClient,
			ScheduledExecutorService executorService,
			Gson gson,
			AchievementDiariesReader reader) {
		this.client = client;
		this.clientThread = clientThread;
		this.config = config;
		this.apiClient = apiClient;
		this.executorService = executorService;
		this.gson = gson;
		this.reader = reader;
	}

	public void stop() {
		previousGameState = GameState.UNKNOWN;
		diaryCheckPending = false;
		lastReadTick = -1;
		lastSyncJson = null;
	}

	/**
	 * Re-reads and re-pushes the current diary completion on the client thread. Used by
	 * the roadmap panel's "Sync &amp; refresh"; clears the dedup cache so an unchanged set
	 * still sends.
	 */
	public void forceSync() {
		clientThread.invokeLater(() -> {
			lastSyncJson = null;
			syncDiaries();
		});
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event) {
		GameState newState = event.getGameState();
		if (newState == GameState.LOGGED_IN && previousGameState != GameState.LOGGED_IN) {
			// Diary varps load shortly after LOGGED_IN, so wait before reading.
			executorService.schedule(() -> clientThread.invokeLater(this::syncDiaries),
					LOGIN_SYNC_DELAY_SECONDS, TimeUnit.SECONDS);
		} else if (previousGameState == GameState.LOGGED_IN && newState != GameState.LOGGED_IN) {
			// Logging out: flush final state (this handler runs on the client thread).
			syncDiaries();
		}
		previousGameState = newState;
	}

	/**
	 * A diary task completion flips one of the diary varbits / varps; mark a re-check
	 * for the next game tick only when a relevant var changed. Unrelated varbit churn
	 * during normal play no longer triggers a full diary re-read every few ticks.
	 */
	@Subscribe
	public void onVarbitChanged(VarbitChanged event) {
		if (reader.watches(event.getVarbitId(), event.getVarpId())) {
			diaryCheckPending = true;
		}
	}

	@Subscribe
	public void onGameTick(GameTick event) {
		if (!diaryCheckPending) {
			return;
		}
		if (lastReadTick != -1 && client.getTickCount() - lastReadTick < RESYNC_THROTTLE_TICKS) {
			return;
		}
		diaryCheckPending = false;
		lastReadTick = client.getTickCount();
		syncDiaries();
	}

	/** Reads all diary completion, builds the payload, and syncs (deduped). Client thread only. */
	private void syncDiaries() {
		if (!config.syncAchievementDiaries() || !SyncGuard.hasAppKey(config)) {
			return;
		}
		if (GameModeUtil.isSpecialGameMode(client)) {
			return;
		}
		String playerUsername = SyncGuard.getPlayerUsername(client);
		if (playerUsername == null) {
			return;
		}

		Map<String, Map<String, DiaryTierResult>> diaries = reader.read(client);
		if (diaries.isEmpty()) {
			return;  // spec failed to load; nothing to send
		}

		AchievementDiariesSyncPayload payload =
				new AchievementDiariesSyncPayload(playerUsername, diaries);
		String json = payload.toJson(gson);

		if (json.equals(lastSyncJson)) {
			log.debug("Achievement diaries unchanged, skipping sync");
			return;
		}
		lastSyncJson = json;
		log.debug("Syncing achievement diaries for player: {}", playerUsername);
		apiClient.sendAchievementDiariesSync(payload);
	}
}
