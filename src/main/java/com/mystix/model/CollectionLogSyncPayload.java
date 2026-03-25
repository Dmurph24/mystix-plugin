package com.mystix.model;

import com.google.gson.Gson;
import java.util.List;

/**
 * Payload for bulk-syncing collection log data to the Mystix API.
 * Matches the format expected by POST /api/runelite/collection-log/
 */
public class CollectionLogSyncPayload
{
	private final String player_username;
	private final List<Group> groups;

	public CollectionLogSyncPayload(String playerUsername, List<Group> groups)
	{
		this.player_username = playerUsername;
		this.groups = groups;
	}

	public String getPlayerUsername()
	{
		return player_username;
	}

	public List<Group> getGroups()
	{
		return groups;
	}

	public String toJson(Gson gson)
	{
		return gson.toJson(this);
	}

	/**
	 * Represents a single collection log page/entry (e.g. "Zulrah", "Barrows Chests").
	 */
	public static class Group
	{
		private final String name;
		private final String tab;
		private final Integer npc_id;
		private final String npc_name;
		private final Integer kill_count;
		private final int total_obtained;
		private final int total_items;
		private final List<Item> items;

		public Group(String name, String tab, Integer npcId, String npcName,
			Integer killCount, int totalObtained, int totalItems, List<Item> items)
		{
			this.name = name;
			this.tab = tab;
			this.npc_id = npcId;
			this.npc_name = npcName;
			this.kill_count = killCount;
			this.total_obtained = totalObtained;
			this.total_items = totalItems;
			this.items = items;
		}

		public String getName()
		{
			return name;
		}

		public String getTab()
		{
			return tab;
		}

		public Integer getNpcId()
		{
			return npc_id;
		}

		public String getNpcName()
		{
			return npc_name;
		}

		public Integer getKillCount()
		{
			return kill_count;
		}

		public int getTotalObtained()
		{
			return total_obtained;
		}

		public int getTotalItems()
		{
			return total_items;
		}

		public List<Item> getItems()
		{
			return items;
		}
	}

	/**
	 * Represents a single item in a collection log entry.
	 */
	public static class Item
	{
		private final int item_id;
		private final int quantity;
		private final boolean obtained;

		public Item(int itemId, int quantity, boolean obtained)
		{
			this.item_id = itemId;
			this.quantity = quantity;
			this.obtained = obtained;
		}

		public int getItemId()
		{
			return item_id;
		}

		public int getQuantity()
		{
			return quantity;
		}

		public boolean isObtained()
		{
			return obtained;
		}
	}
}
