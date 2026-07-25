package com.mystix.model;

import com.google.gson.Gson;
import java.util.List;
import java.util.Map;

/**
 * Payload for uploading the game-cache slayer DB tables (113-117) to the
 * Mystix API. Matches the format expected by POST /api/runelite/slayer/catalog/.
 *
 * <p>This data is identical for every player on the same cache revision, so
 * the server dedupes on {@code payload_hash} and the client checks
 * GET /api/runelite/slayer/catalog/status/ before uploading.
 */
public class SlayerCatalogPayload {
	/** Response shape of GET /api/runelite/slayer/catalog/status/. */
	public static class Status {
		private boolean needed;

		public boolean isNeeded() {
			return needed;
		}
	}

	private final String player_username;
	private final String cache_revision;
	private final String payload_hash;
	private final Map<String, List<Map<String, Object>>> tables;

	public SlayerCatalogPayload(
			String playerUsername,
			String cacheRevision,
			String payloadHash,
			Map<String, List<Map<String, Object>>> tables) {
		this.player_username = playerUsername;
		this.cache_revision = cacheRevision;
		this.payload_hash = payloadHash;
		this.tables = tables;
	}

	public String getPlayerUsername() {
		return player_username;
	}

	public String getPayloadHash() {
		return payload_hash;
	}

	public Map<String, List<Map<String, Object>>> getTables() {
		return tables;
	}

	public String toJson(Gson gson) {
		return gson.toJson(this);
	}
}
