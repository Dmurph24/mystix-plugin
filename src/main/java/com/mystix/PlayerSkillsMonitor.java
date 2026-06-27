package com.mystix;

import com.mystix.api.MystixApiClient;
import com.mystix.model.PlayerSkillsSyncPayload;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Player;
import net.runelite.api.Skill;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.StatChanged;
import net.runelite.client.eventbus.Subscribe;

/**
 * Keeps the Mystix backend in sync with the player's skill levels and XP.
 *
 * <p>Skills are <em>captured while logged in</em> — on each {@link StatChanged}
 * (XP change) and shortly after login — into a cached payload, then sent to the
 * API by a periodic flush and on logout. We deliberately never read the client
 * on logout: by the time the game state leaves {@code LOGGED_IN} the local
 * player and skill data are being torn down, which made the old logout-time read
 * unreliable (and could NPE on {@code getCombatLevel()}). Sending the snapshot
 * captured during play makes logout sync dependable.
 */
@Slf4j
@Singleton
public class PlayerSkillsMonitor {
	private static final int LOGIN_SYNC_DELAY_SECONDS = 3;
	private static final int FLUSH_INTERVAL_SECONDS = 60;

	private final Client client;
	private final MystixConfig config;
	private final MystixApiClient apiClient;
	private final ScheduledExecutorService executorService;

	private GameState previousGameState = GameState.UNKNOWN;
	private volatile PlayerSkillsSyncPayload cachedPayload;
	private volatile boolean dirty;
	private ScheduledFuture<?> flushTask;

	@Inject
	public PlayerSkillsMonitor(
			Client client,
			MystixConfig config,
			MystixApiClient apiClient,
			ScheduledExecutorService executorService) {
		this.client = client;
		this.config = config;
		this.apiClient = apiClient;
		this.executorService = executorService;
	}

	public void start() {
		flushTask = executorService.scheduleAtFixedRate(
				this::flushIfDirty, FLUSH_INTERVAL_SECONDS, FLUSH_INTERVAL_SECONDS, TimeUnit.SECONDS);
	}

	public void stop() {
		if (flushTask != null) {
			flushTask.cancel(false);
			flushTask = null;
		}
		flushIfDirty();
		previousGameState = GameState.UNKNOWN;
		cachedPayload = null;
		dirty = false;
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event) {
		GameState newState = event.getGameState();

		if (newState == GameState.LOGGED_IN && previousGameState != GameState.LOGGED_IN) {
			// Skill data isn't available the instant we log in — capture (and
			// send) once it's loaded.
			log.debug("Player logged in, scheduling skills capture in {}s", LOGIN_SYNC_DELAY_SECONDS);
			executorService.schedule(() -> {
				captureSkills();
				flushIfDirty();
			}, LOGIN_SYNC_DELAY_SECONDS, TimeUnit.SECONDS);
		} else if (previousGameState == GameState.LOGGED_IN && newState != GameState.LOGGED_IN) {
			// Logging out: send the snapshot captured while we were still logged
			// in, rather than re-reading the now-clearing client.
			log.debug("Player logged out, flushing cached skills");
			flushIfDirty();
		}

		previousGameState = newState;
	}

	@Subscribe
	public void onStatChanged(StatChanged event) {
		// Fires on the client thread while logged in, so reading skill data here
		// is safe and always current.
		captureSkills();
	}

	/**
	 * Reads the player's current skills from the client into the cached payload.
	 * No-op (and never throws) when not logged in or otherwise guarded out.
	 */
	private void captureSkills() {
		if (!SyncGuard.hasAppKey(config)) {
			return;
		}
		if (GameModeUtil.isSpecialGameMode(client)) {
			return;
		}
		if (client.getGameState() != GameState.LOGGED_IN) {
			return;
		}
		String playerUsername = SyncGuard.getPlayerUsername(client);
		if (playerUsername == null) {
			return;
		}
		Player localPlayer = client.getLocalPlayer();
		if (localPlayer == null) {
			return;
		}

		Map<String, PlayerSkillsSyncPayload.SkillData> skills = new HashMap<>();
		int totalLevel = 0;
		for (Skill skill : Skill.values()) {
			int level = client.getRealSkillLevel(skill);
			int xp = client.getSkillExperience(skill);
			skills.put(skill.getName(), new PlayerSkillsSyncPayload.SkillData(level, xp));
			totalLevel += level;
		}
		int combatLevel = localPlayer.getCombatLevel();

		cachedPayload = new PlayerSkillsSyncPayload(playerUsername, skills, totalLevel, combatLevel);
		dirty = true;
	}

	/** Sends the cached snapshot if it has changed since the last send. */
	private void flushIfDirty() {
		PlayerSkillsSyncPayload payload = cachedPayload;
		if (!dirty || payload == null) {
			return;
		}
		dirty = false;
		log.debug("Syncing {} skills for player: {} (Total Level: {}, Combat Level: {})",
				payload.getSkills().size(), payload.getPlayer(),
				payload.getTotalLevel(), payload.getCombatLevel());
		apiClient.sendPlayerSkillsSync(payload);
	}
}
