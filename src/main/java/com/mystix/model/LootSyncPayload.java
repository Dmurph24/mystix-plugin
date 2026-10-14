package com.mystix.model;

import com.google.gson.Gson;
import java.util.List;

/**
 * Payload for bulk-syncing loot data to the Mystix API.
 * Matches the format expected by POST /api/runelite/loot/
 */
public class LootSyncPayload
{
	private final String player_username;
	private final String source_client;
	private final List<LootRecord> loot_records;

	public LootSyncPayload(String playerUsername, String sourceClient, List<LootRecord> lootRecords)
	{
		this.player_username = playerUsername;
		this.source_client = sourceClient;
		this.loot_records = lootRecords;
	}

	public String getPlayerUsername()
	{
		return player_username;
	}

	public String getSourceClient()
	{
		return source_client;
	}

	public List<LootRecord> getLootRecords()
	{
		return loot_records;
	}

	public String toJson(Gson gson)
	{
		return gson.toJson(this);
	}

	/**
	 * Represents aggregate loot data from a specific NPC.
	 */
	public static class LootRecord
	{
		private final int npc_id;
		private final String npc_name;
		private final int kill_count;
		private final List<LootItem> items;

		public LootRecord(int npcId, String npcName, int killCount, List<LootItem> items)
		{
			this.npc_id = npcId;
			this.npc_name = npcName;
			this.kill_count = killCount;
			this.items = items;
		}

		public int getNpcId()
		{
			return npc_id;
		}

		public String getNpcName()
		{
			return npc_name;
		}

		public int getKillCount()
		{
			return kill_count;
		}

		public List<LootItem> getItems()
		{
			return items;
		}
	}

	/**
	 * Represents a single item in loot data. The quantity is a long: aggregated
	 * loot (e.g. coins summed over every kill in the loot tracker) can exceed
	 * int range now that OSRS has lifted the max-cash cap.
	 */
	public static class LootItem
	{
		private final int item_id;
		private final long quantity;

		public LootItem(int itemId, long quantity)
		{
			this.item_id = itemId;
			this.quantity = quantity;
		}

		public int getItemId()
		{
			return item_id;
		}

		public long getQuantity()
		{
			return quantity;
		}
	}
}
