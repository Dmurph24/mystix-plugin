package com.mystix.model;

import com.google.gson.Gson;
import java.util.Map;

/**
 * Payload for syncing achievement diary completion to the Mystix API.
 * Matches the format expected by POST /api/runelite/achievement-diaries/
 *
 * <p>Mirrors the WikiSync {@code achievement_diaries} shape so the backend
 * reuses the existing processor: a nested
 * {@code {region: {tier: {complete, tasks: [bool, ...]}}}} map where each
 * {@code tasks} array is positional in in-game varbit order.
 */
public class AchievementDiariesSyncPayload {
	private final String player_username;
	private final Map<String, Map<String, DiaryTierResult>> achievement_diaries;

	public AchievementDiariesSyncPayload(
			String playerUsername,
			Map<String, Map<String, DiaryTierResult>> achievementDiaries) {
		this.player_username = playerUsername;
		this.achievement_diaries = achievementDiaries;
	}

	public String getPlayerUsername() {
		return player_username;
	}

	public Map<String, Map<String, DiaryTierResult>> getAchievementDiaries() {
		return achievement_diaries;
	}

	public String toJson(Gson gson) {
		return gson.toJson(this);
	}
}
