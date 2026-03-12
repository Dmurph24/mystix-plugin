package com.mystix;

import com.mystix.api.MystixApiClient;
import com.mystix.model.PlayerSkillsSyncPayload;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Player;
import net.runelite.api.Skill;
import net.runelite.api.events.GameStateChanged;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;

@Slf4j
@Singleton
public class PlayerSkillsMonitor {
	private static final int LOGIN_SYNC_DELAY_SECONDS = 3;

	private final Client client;
	private final MystixConfig config;
	private final MystixApiClient apiClient;
	private final EventBus eventBus;
	private final ScheduledExecutorService executorService;

	private GameState previousGameState = GameState.UNKNOWN;

	@Inject
	public PlayerSkillsMonitor(
			Client client,
			MystixConfig config,
			MystixApiClient apiClient,
			EventBus eventBus,
			ScheduledExecutorService executorService) {
		this.client = client;
		this.config = config;
		this.apiClient = apiClient;
		this.eventBus = eventBus;
		this.executorService = executorService;
	}

	public void start() {
		eventBus.register(this);
		log.info("PlayerSkillsMonitor started");
	}

	public void stop() {
		eventBus.unregister(this);
		previousGameState = GameState.UNKNOWN;
		log.debug("PlayerSkillsMonitor stopped");
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event) {
		GameState newState = event.getGameState();

		if (newState == GameState.LOGGED_IN && previousGameState != GameState.LOGGED_IN) {
			log.debug("Player logged in, scheduling skills sync in {}s", LOGIN_SYNC_DELAY_SECONDS);
			executorService.schedule(this::syncPlayerSkills, LOGIN_SYNC_DELAY_SECONDS, TimeUnit.SECONDS);
		} else if (previousGameState == GameState.LOGGED_IN && newState != GameState.LOGGED_IN) {
			log.debug("Player logged out, syncing skills");
			syncPlayerSkills();
		}

		previousGameState = newState;
	}

	/**
	 * Reads all skill levels and XP from the client, builds a payload with
	 * total and combat levels, and sends it to the Mystix API.
	 */
	private void syncPlayerSkills() {
		if (!SyncGuard.hasAppKey(config)) {
			log.debug("Player skills sync skipped: no App Key configured");
			return;
		}
		if (GameModeUtil.isSpecialGameMode(client)) {
			log.debug("Player skills sync skipped: special game mode detected (Leagues, DMM, etc.)");
			return;
		}

		String playerUsername = SyncGuard.getPlayerUsername(client);
		if (playerUsername == null) {
			log.warn("Player skills sync skipped: could not get player username");
			return;
		}

		Player localPlayer = client.getLocalPlayer();
		Map<String, PlayerSkillsSyncPayload.SkillData> skills = new HashMap<>();
		int totalLevel = 0;

		for (Skill skill : Skill.values()) {
			int level = client.getRealSkillLevel(skill);
			int xp = client.getSkillExperience(skill);
			skills.put(skill.getName(), new PlayerSkillsSyncPayload.SkillData(level, xp));
			totalLevel += level;
		}

		int combatLevel = localPlayer.getCombatLevel();

		PlayerSkillsSyncPayload payload = new PlayerSkillsSyncPayload(playerUsername, skills, totalLevel, combatLevel);
		log.info("Syncing {} skills for player: {} (Total Level: {}, Combat Level: {})",
				skills.size(), playerUsername, totalLevel, combatLevel);
		apiClient.sendPlayerSkillsSync(payload);
	}
}
