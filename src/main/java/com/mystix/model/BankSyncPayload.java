package com.mystix.model;

import com.google.gson.Gson;
import java.util.List;

/**
 * Payload for syncing bank items to the Mystix API.
 * Matches the format expected by POST /api/runelite/bank/
 */
public class BankSyncPayload {
	private final String player_username;
	private final List<BankItem> items;

	public BankSyncPayload(String playerUsername, List<BankItem> items) {
		this.player_username = playerUsername;
		this.items = items;
	}

	public String getPlayerUsername() {
		return player_username;
	}

	public List<BankItem> getItems() {
		return items;
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
