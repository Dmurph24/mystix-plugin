package com.mystix.model;

import com.google.gson.Gson;
import java.util.List;

/**
 * Payload for syncing completed combat achievements to the Mystix API.
 * Matches the format expected by POST /api/runelite/combat-achievements/
 *
 * Mirrors the WikiSync combat achievements shape so the backend reuses the
 * existing processor: a flat list of completed in-game combat achievement task
 * ids.
 */
public class CombatAchievementsSyncPayload {
	private final String player_username;
	private final List<Integer> combat_achievements;

	public CombatAchievementsSyncPayload(String playerUsername, List<Integer> completedTaskIds) {
		this.player_username = playerUsername;
		this.combat_achievements = completedTaskIds;
	}

	public String getPlayerUsername() {
		return player_username;
	}

	public List<Integer> getCombatAchievements() {
		return combat_achievements;
	}

	public String toJson(Gson gson) {
		return gson.toJson(this);
	}
}
