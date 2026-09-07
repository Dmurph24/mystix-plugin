package com.mystix;

import com.google.gson.Gson;
import com.mystix.api.MystixApiClient;
import com.mystix.model.HouseLocationSyncPayload;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.VarbitChanged;
import net.runelite.api.gameval.VarbitID;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.eventbus.Subscribe;

/**
 * Syncs where the player's house is ({@link VarbitID#POH_HOUSE_LOCATION}) so Mystix Farm
 * Routes only recommend "Teleport to House" where the portal actually lands.
 *
 * <p>Read timing reuses {@link DiarySyncTrigger}: the varp block is replayed over several
 * ticks after login, so the first read waits for the session to settle (a mid-replay read
 * could report a prior account's house), and a later change (moving house at the estate
 * agent) resyncs a few ticks after its {@link VarbitChanged}. A value of 0 means no house
 * and is never sent, so a location the player set by hand in the app survives a fresh
 * account or a login before the house is bought.
 */
@Slf4j
@Singleton
public class HouseLocationMonitor {
	private final Client client;
	private final ClientThread clientThread;
	private final MystixConfig config;
	private final MystixApiClient apiClient;
	private final Gson gson;
	private final DiarySyncTrigger trigger = new DiarySyncTrigger();

	private GameState previousGameState = GameState.UNKNOWN;
	private String lastSyncJson;

	@Inject
	public HouseLocationMonitor(
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
		previousGameState = GameState.UNKNOWN;
		trigger.reset();
		lastSyncJson = null;
	}

	/**
	 * Re-reads and re-pushes the house location on the client thread (the panel's
	 * "Sync &amp; refresh"); clears the dedup so an unchanged value still sends. No-op until
	 * the session has settled, so it can never sample the post-login replay window.
	 */
	public void forceSync() {
		clientThread.invokeLater(() -> {
			if (client.getGameState() != GameState.LOGGED_IN || !trigger.isBaselineSynced()) {
				return;
			}
			lastSyncJson = null;
			syncHouseLocation();
		});
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event) {
		GameState newState = event.getGameState();
		boolean wasLoggedIn = previousGameState == GameState.LOGGED_IN;
		boolean nowLoggedIn = newState == GameState.LOGGED_IN;
		if (wasLoggedIn && !nowLoggedIn) {
			if (trigger.leaveAndShouldFlush()) {
				syncHouseLocation();
			}
		}
		previousGameState = newState;
	}

	/** Only the house-location varbit matters here; every other change is ignored. */
	@Subscribe
	public void onVarbitChanged(VarbitChanged event) {
		if (event.getVarbitId() == VarbitID.POH_HOUSE_LOCATION) {
			trigger.varbitChanged();
		}
	}

	@Subscribe
	public void onGameTick(GameTick event) {
		boolean loggedIn = client.getGameState() == GameState.LOGGED_IN;
		if (trigger.tick(client.getTickCount(), loggedIn) == DiarySyncTrigger.Decision.SYNC) {
			syncHouseLocation();
		}
	}

	/** Reads the varbit and syncs it (deduped). Client thread only. */
	private void syncHouseLocation() {
		if (!config.syncHouseLocation() || !SyncGuard.hasAppKey(config)) {
			return;
		}
		if (GameModeUtil.isSpecialGameMode(client)) {
			return;
		}
		String playerUsername = SyncGuard.getPlayerUsername(client);
		if (playerUsername == null) {
			return;
		}

		int value = client.getVarbitValue(VarbitID.POH_HOUSE_LOCATION);
		if (value == 0) {
			// No house (or not loaded yet): never overwrite a manual pick with "unknown".
			return;
		}
		HouseLocationSyncPayload payload = new HouseLocationSyncPayload(playerUsername, value);
		String json = payload.toJson(gson);
		// Dedup on the whole payload (username included), so switching to another
		// account whose house happens to share the same id still syncs.
		if (json.equals(lastSyncJson)) {
			log.debug("House location unchanged, skipping sync");
			return;
		}
		lastSyncJson = json;
		log.debug("Syncing house location {} for player: {}", value, playerUsername);
		apiClient.sendHouseLocationSync(payload);
	}
}
