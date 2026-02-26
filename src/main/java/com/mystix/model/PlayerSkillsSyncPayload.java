package com.mystix.model;

import com.google.gson.Gson;
import java.util.Map;

/**
 * Payload for syncing player skills to the Mystix API.
 * Matches the format expected by POST /api/runelite/skills/
 */
public class PlayerSkillsSyncPayload {
	private final String player;
	private final Map<String, SkillData> skills;
	private final int total_level;
	private final int combat_level;

	public PlayerSkillsSyncPayload(String player, Map<String, SkillData> skills, int totalLevel, int combatLevel) {
		this.player = player;
		this.skills = skills;
		this.total_level = totalLevel;
		this.combat_level = combatLevel;
	}

	public String getPlayer() {
		return player;
	}

	public Map<String, SkillData> getSkills() {
		return skills;
	}

	public int getTotalLevel() {
		return total_level;
	}

	public int getCombatLevel() {
		return combat_level;
	}

	public String toJson() {
		Gson gson = new Gson();
		return gson.toJson(this);
	}

	/**
	 * Represents skill level and experience data
	 */
	public static class SkillData {
		private final int level;
		private final int current_xp;

		public SkillData(int level, int currentXp) {
			this.level = level;
			this.current_xp = currentXp;
		}

		public int getLevel() {
			return level;
		}

		public int getCurrentXp() {
			return current_xp;
		}
	}
}
