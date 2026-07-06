package com.mystix.model;

import com.google.gson.Gson;
import java.util.Map;

/**
 * Payload for syncing quest states to the Mystix API.
 * Matches the format expected by POST /api/runelite/quests/
 *
 * Mirrors the WikiSync quests shape so the backend reuses the existing
 * processor: a {questName: status} map where status is 0 = not started,
 * 1 = in progress, 2 = completed.
 */
public class QuestsSyncPayload {
	private final String player_username;
	private final Map<String, Integer> quests;

	public QuestsSyncPayload(String playerUsername, Map<String, Integer> quests) {
		this.player_username = playerUsername;
		this.quests = quests;
	}

	public String getPlayerUsername() {
		return player_username;
	}

	public Map<String, Integer> getQuests() {
		return quests;
	}

	public String toJson(Gson gson) {
		return gson.toJson(this);
	}
}
