package com.mystix;

import com.mystix.api.MystixApiClient;
import com.mystix.model.PlayerSkillsSyncPayload;
import java.util.HashMap;
import java.util.Map;
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

/**
 * Monitors player login/logout events and syncs all skill levels to the Mystix API.
 * Listens for GameStateChanged events and sends skill data when:
 * - Player logs in (GameState.LOGGED_IN)
 * - Player logs out (GameState transitions away from LOGGED_IN)
 */
@Slf4j
@Singleton
public class PlayerSkillsMonitor
{
	private final Client client;
	private final MystixConfig config;
	private final MystixApiClient apiClient;
	private final EventBus eventBus;

	private GameState previousGameState = GameState.UNKNOWN;

	@Inject
	public PlayerSkillsMonitor(
		Client client,
		MystixConfig config,
		MystixApiClient apiClient,
		EventBus eventBus)
	{
		this.client = client;
		this.config = config;
		this.apiClient = apiClient;
		this.eventBus = eventBus;
	}

	public void start()
	{
		eventBus.register(this);
		log.info("PlayerSkillsMonitor started");
	}

	public void stop()
	{
		eventBus.unregister(this);
		previousGameState = GameState.UNKNOWN;
		log.debug("PlayerSkillsMonitor stopped");
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		GameState newState = event.getGameState();

		if (newState == GameState.LOGGED_IN && previousGameState != GameState.LOGGED_IN)
		{
			log.debug("Player logged in, syncing skills");
			syncPlayerSkills();
		}
		else if (previousGameState == GameState.LOGGED_IN && newState != GameState.LOGGED_IN)
		{
			log.debug("Player logged out, syncing skills");
			syncPlayerSkills();
		}

		previousGameState = newState;
	}

	private void syncPlayerSkills()
	{
		if (!config.syncPlayerSkills())
		{
			log.debug("Player skills sync skipped: sync disabled");
			return;
		}

		if (config.mystixAppKey() == null || config.mystixAppKey().isBlank())
		{
			log.debug("Player skills sync skipped: no App Key configured");
			return;
		}

		Player localPlayer = client.getLocalPlayer();
		String playerUsername = localPlayer != null ? localPlayer.getName() : null;
		if (playerUsername == null || playerUsername.isBlank())
		{
			log.warn("Player skills sync skipped: could not get player username");
			return;
		}

		Map<String, PlayerSkillsSyncPayload.SkillData> skills = new HashMap<>();
		int totalLevel = 0;
		
		for (Skill skill : Skill.values())
		{
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
