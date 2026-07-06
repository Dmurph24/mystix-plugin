package com.mystix;

import com.google.gson.Gson;
import com.mystix.api.MystixApiClient;
import com.mystix.model.CombatAchievementsSyncPayload;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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
 * Monitors the player's completed combat achievements and syncs them to the Mystix API.
 *
 * <p>Combat achievement completion is stored in a fixed set of VarPlayers (the same source
 * WikiSync reads), each a 32-bit field. A task is completed iff its bit is set, where
 * {@code taskId = 32 * varpIndex + bitPosition}. Decoding these varps reproduces the exact
 * task-id list WikiSync's server derives, so the backend consumes the payload unchanged as a
 * flat {@code combat_achievements: [taskId, ...]} list.
 *
 * <p>Triggers: a sync on login (after a short delay so the CA varps have loaded) and on
 * logout, plus a near-real-time sync when a combat achievement completes. Completion flips one
 * of the CA varps, so we mark a re-check on the matching {@link VarbitChanged} and read+dedupe
 * on the next {@link GameTick} (throttled), which collapses churn to at most one read per few
 * ticks. A JSON equality check means an unchanged CA set is never resent.
 */
@Slf4j
@Singleton
public class CombatAchievementMonitor {
	private static final int LOGIN_SYNC_DELAY_SECONDS = 3;
	// Minimum ticks between varp-driven reads, so any varp churn doesn't re-read every
	// tick. A CA completion still syncs within a few ticks, and login/logout syncs are
	// the safety net.
	private static final int RESYNC_THROTTLE_TICKS = 3;

	// The VarPlayers holding combat achievement completion bits, in the order that defines
	// task ids (taskId = 32 * index + bit). Mirrors WikiSync's combatAchievementVarps list;
	// order matters, so this is kept as an ordered array and must not be reordered.
	private static final int[] COMBAT_ACHIEVEMENT_VARPS = {
			3116, 3117, 3118, 3119, 3120, 3121, 3122, 3123, 3124, 3125,
			3126, 3127, 3128, 3387, 3718, 3773, 3774, 4204, 4496, 4721};
	// Membership set for the VarbitChanged filter, so we only re-check on CA varp changes.
	private static final Set<Integer> COMBAT_ACHIEVEMENT_VARP_SET = new HashSet<>();

	static {
		for (int varp : COMBAT_ACHIEVEMENT_VARPS) {
			COMBAT_ACHIEVEMENT_VARP_SET.add(varp);
		}
	}

	private final Client client;
	private final ClientThread clientThread;
	private final MystixConfig config;
	private final MystixApiClient apiClient;
	private final ScheduledExecutorService executorService;
	private final Gson gson;

	private GameState previousGameState = GameState.UNKNOWN;
	private boolean caCheckPending;
	private int lastReadTick = -1;
	private String lastSyncJson;

	@Inject
	public CombatAchievementMonitor(
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

	public void stop() {
		previousGameState = GameState.UNKNOWN;
		caCheckPending = false;
		lastReadTick = -1;
		lastSyncJson = null;
	}

	/**
	 * Re-reads and re-pushes the current combat achievements on the client thread. Used by
	 * the roadmap panel's "Sync &amp; refresh"; clears the dedup cache so an unchanged set
	 * still sends.
	 */
	public void forceSync() {
		clientThread.invokeLater(() -> {
			lastSyncJson = null;
			syncCombatAchievements();
		});
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event) {
		GameState newState = event.getGameState();
		if (newState == GameState.LOGGED_IN && previousGameState != GameState.LOGGED_IN) {
			// CA varps load shortly after LOGGED_IN, so wait before reading.
			executorService.schedule(() -> clientThread.invokeLater(this::syncCombatAchievements),
					LOGIN_SYNC_DELAY_SECONDS, TimeUnit.SECONDS);
		} else if (previousGameState == GameState.LOGGED_IN && newState != GameState.LOGGED_IN) {
			// Logging out: flush final state (this handler runs on the client thread).
			syncCombatAchievements();
		}
		previousGameState = newState;
	}

	/** A CA completion flips one of the CA varps; mark a re-check for the next game tick. */
	@Subscribe
	public void onVarbitChanged(VarbitChanged event) {
		if (COMBAT_ACHIEVEMENT_VARP_SET.contains(event.getVarpId())) {
			caCheckPending = true;
		}
	}

	@Subscribe
	public void onGameTick(GameTick event) {
		if (!caCheckPending) {
			return;
		}
		if (lastReadTick != -1 && client.getTickCount() - lastReadTick < RESYNC_THROTTLE_TICKS) {
			return;
		}
		caCheckPending = false;
		lastReadTick = client.getTickCount();
		syncCombatAchievements();
	}

	/** Reads all completed CAs, builds the payload, and syncs (deduped). Client thread only. */
	private void syncCombatAchievements() {
		if (!config.syncCombatAchievements() || !SyncGuard.hasAppKey(config)) {
			return;
		}
		if (GameModeUtil.isSpecialGameMode(client)) {
			return;
		}
		String playerUsername = SyncGuard.getPlayerUsername(client);
		if (playerUsername == null) {
			return;
		}

		// Ascending (index, bit) order gives a stable JSON ordering so the dedup check is
		// reliable.
		List<Integer> completed = new ArrayList<>();
		for (int index = 0; index < COMBAT_ACHIEVEMENT_VARPS.length; index++) {
			int value = client.getVarpValue(COMBAT_ACHIEVEMENT_VARPS[index]);
			for (int bit = 0; bit < 32; bit++) {
				if ((value & (1 << bit)) != 0) {
					completed.add(32 * index + bit);
				}
			}
		}

		CombatAchievementsSyncPayload payload =
				new CombatAchievementsSyncPayload(playerUsername, completed);
		String json = payload.toJson(gson);

		if (json.equals(lastSyncJson)) {
			log.debug("Combat achievements unchanged, skipping sync");
			return;
		}
		lastSyncJson = json;
		log.debug("Syncing {} combat achievements for player: {}", completed.size(), playerUsername);
		apiClient.sendCombatAchievementsSync(payload);
	}
}
