package com.mystix.model;

import com.google.gson.Gson;
import java.util.List;
import java.util.Map;

/**
 * Payload for syncing bank/vault items to the Mystix API.
 * Matches the format expected by POST /api/runelite/bank/
 *
 * Items is a map keyed by source (e.g. "bank", "seed_vault") to a list of items.
 */
public class BankSyncPayload {
	private final String player_username;
	private final Map<String, List<BankItem>> items;

	public BankSyncPayload(String playerUsername, Map<String, List<BankItem>> items) {
		this.player_username = playerUsername;
		this.items = items;
	}

	public String getPlayerUsername() {
		return player_username;
	}

	public Map<String, List<BankItem>> getItems() {
		return items;
	}

	public int getTotalItemCount() {
		return items.values().stream().mapToInt(List::size).sum();
	}

	public String toJson(Gson gson) {
		return gson.toJson(this);
	}

	/**
	 * Represents a single bank item with ID and quantity.
	 */
	public static class BankItem {
		private final int item_id;
		private final int quantity;

		public BankItem(int itemId, int quantity) {
			this.item_id = itemId;
			this.quantity = quantity;
		}

		public int getItemId() {
			return item_id;
		}

		public int getQuantity() {
			return quantity;
		}
	}
}
