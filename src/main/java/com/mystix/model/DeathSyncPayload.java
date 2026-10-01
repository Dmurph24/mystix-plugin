package com.mystix.model;

import com.google.gson.Gson;
import java.util.List;

/**
 * Payload for POST /api/runelite/deaths/: the deaths still waiting for the
 * backend to acknowledge them. An empty list is sent on login so the backend
 * knows this account reports deaths, which lets the app call a week without
 * any deaths a deathless week.
 */
public class DeathSyncPayload {
	private final String player_username;
	private final List<DeathEvent> events;

	public DeathSyncPayload(String playerUsername, List<DeathEvent> events) {
		this.player_username = playerUsername;
		this.events = events;
	}

	public String getPlayerUsername() {
		return player_username;
	}

	public List<DeathEvent> getEvents() {
		return events;
	}

	public String toJson(Gson gson) {
		return gson.toJson(this);
	}
}
