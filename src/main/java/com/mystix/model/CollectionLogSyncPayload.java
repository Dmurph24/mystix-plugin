package com.mystix.model;

import com.google.gson.Gson;
import java.util.List;

/**
 * Payload for syncing collection log data to the Mystix API.
 * Matches the format expected by POST /api/runelite/collection-log/
 *
 * Mirrors the WikiSync collection log shape so the backend reuses the existing
 * processor: a flat list of obtained OSRS item IDs plus the obtained count.
 */
public class CollectionLogSyncPayload {
	private final String player_username;
	private final List<Integer> collection_log;
	private final int collection_log_item_count;

	public CollectionLogSyncPayload(String playerUsername, List<Integer> obtainedItemIds) {
		this.player_username = playerUsername;
		this.collection_log = obtainedItemIds;
		this.collection_log_item_count = obtainedItemIds.size();
	}

	public String getPlayerUsername() {
		return player_username;
	}

	public List<Integer> getCollectionLog() {
		return collection_log;
	}

	public int getItemCount() {
		return collection_log_item_count;
	}

	public String toJson(Gson gson) {
		return gson.toJson(this);
	}
}
