package com.mystix.model;

import com.google.gson.Gson;
import java.util.Map;

/**
 * Payload for syncing per-boss kill counts to the Mystix API.
 * Matches the format expected by POST /api/runelite/kill-counts/
 *
 * ``kill_counts`` is a {bossName: killCount} map read from RuneLite's persisted
 * ``killcount`` config (the store the in-game {@code !kc} command reads). Boss
 * names are RuneLite's lowercased keys (e.g. "zulrah", "chambers of xeric").
 */
public class KillCountsSyncPayload {
	private final String player_username;
	private final Map<String, Integer> kill_counts;

	public KillCountsSyncPayload(String playerUsername, Map<String, Integer> killCounts) {
		this.player_username = playerUsername;
		this.kill_counts = killCounts;
	}

	public String getPlayerUsername() {
		return player_username;
	}

	public Map<String, Integer> getKillCounts() {
		return kill_counts;
	}

	public String toJson(Gson gson) {
		return gson.toJson(this);
	}
}
