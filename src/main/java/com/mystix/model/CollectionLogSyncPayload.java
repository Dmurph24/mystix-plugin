package com.mystix.model;

import com.google.gson.Gson;
import java.util.List;
import java.util.Map;

/**
 * Payload for syncing collection log data to the Mystix API.
 * Matches the format expected by POST /api/runelite/collection-log/
 *
 * Mirrors the WikiSync collection log shape so the backend reuses the existing
 * processor: a flat list of obtained OSRS item IDs plus the obtained count.
 * ``collection_log_quantities`` is an additional {itemId: quantity} map read from
 * the clog item widgets (WikiSync has no quantities); the backend treats it as
 * optional, so an empty map is harmless.
 */
public class CollectionLogSyncPayload {
	private final String player_username;
	private final List<Integer> collection_log;
	private final int collection_log_item_count;
	private final Map<Integer, Integer> collection_log_quantities;

	public CollectionLogSyncPayload(
			String playerUsername,
			List<Integer> obtainedItemIds,
			Map<Integer, Integer> quantities) {
		this.player_username = playerUsername;
		this.collection_log = obtainedItemIds;
		this.collection_log_item_count = obtainedItemIds.size();
		this.collection_log_quantities = quantities;
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

	public Map<Integer, Integer> getCollectionLogQuantities() {
		return collection_log_quantities;
	}

	public String toJson(Gson gson) {
		return gson.toJson(this);
	}
}
