package com.mystix;

import com.google.gson.Gson;
import com.mystix.api.MystixApiClient;
import com.mystix.model.AchievementDiariesSyncPayload;
import com.mystix.model.DiaryTierResult;
import java.util.Map;
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
 * <p>Read timing is governed by {@link DiarySyncTrigger}. The client's varp array is not
 * reset atomically on login / world-hop / account-switch; the server replays it over
 * several ticks after {@code LOGGED_IN}, and that replay is itself a flood of
 * {@link VarbitChanged}. Reading during the flood can mirror a prior session's
 * fully-complete diary bits onto the current player (a permanent, unrecoverable write on
 * the backend). So the trigger holds the first (baseline) read until the session has
 * settled and never lets the varbit-driven resync fire before that baseline. After the
 * baseline, a diary completion still syncs within a few ticks; logout flushes the final
 * loaded state; the panel's {@link #forceSync()} re-sends on demand once settled.
 */
@Slf4j
@Singleton
public class AchievementDiaryMonitor {
	private final Client client;
	private final ClientThread clientThread;
	private final MystixConfig config;
	private final MystixApiClient apiClient;
	private final Gson gson;
	private final AchievementDiariesReader reader;
	private final DiarySyncTrigger trigger = new DiarySyncTrigger();

	private GameState previousGameState = GameState.UNKNOWN;
	private String lastSyncJson;

	@Inject
	public AchievementDiaryMonitor(
			Client client,
			ClientThread clientThread,
			MystixConfig config,
			MystixApiClient apiClient,
			Gson gson,
			AchievementDiariesReader reader) {
		this.client = client;
		this.clientThread = clientThread;
		this.config = config;
		this.apiClient = apiClient;
		this.gson = gson;
		this.reader = reader;
	}

	public void stop() {
		previousGameState = GameState.UNKNOWN;
		trigger.reset();
		lastSyncJson = null;
	}

	/**
	 * Re-reads and re-pushes the current diary completion on the client thread. Used by the
	 * roadmap panel's "Sync &amp; refresh"; clears the dedup cache so an unchanged set still
	 * sends. No-op until the session has settled (the baseline read will send the current
	 * state shortly), so the button can never sample the post-login replay window.
	 */
	public void forceSync() {
		clientThread.invokeLater(() -> {
			if (client.getGameState() != GameState.LOGGED_IN || !trigger.isBaselineSynced()) {
				return;
			}
			lastSyncJson = null;
			syncDiaries();
		});
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event) {
		GameState newState = event.getGameState();
		boolean wasLoggedIn = previousGameState == GameState.LOGGED_IN;
		boolean nowLoggedIn = newState == GameState.LOGGED_IN;
		if (wasLoggedIn && !nowLoggedIn) {
			// Logging out: the client varps still hold this player's fully-loaded values, so
			// flush the final state (this handler runs on the client thread) — but only if
			// the session ever produced a trustworthy baseline read.
			if (trigger.leaveAndShouldFlush()) {
				syncDiaries();
			}
		}
		previousGameState = newState;
	}

	/** A diary task completion flips a varp; request a resync (honoured after the baseline). */
	@Subscribe
	public void onVarbitChanged(VarbitChanged event) {
		trigger.varbitChanged();
	}

	@Subscribe
	public void onGameTick(GameTick event) {
		boolean loggedIn = client.getGameState() == GameState.LOGGED_IN;
		if (trigger.tick(client.getTickCount(), loggedIn) == DiarySyncTrigger.Decision.SYNC) {
			syncDiaries();
		}
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
