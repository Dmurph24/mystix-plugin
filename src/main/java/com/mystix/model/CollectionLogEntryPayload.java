package com.mystix.model;

import com.google.gson.Gson;

/**
 * Payload for a real-time collection log item obtained event.
 * Matches the format expected by POST /api/runelite/collection-log/entry/
 */
public class CollectionLogEntryPayload
{
	private final String player_username;
	private final int item_id;
	private final String group_name;
	private final String tab;

	public CollectionLogEntryPayload(String playerUsername, int itemId, String groupName, String tab)
	{
		this.player_username = playerUsername;
		this.item_id = itemId;
		this.group_name = groupName;
		this.tab = tab;
	}

	public String getPlayerUsername()
	{
		return player_username;
	}

	public int getItemId()
	{
		return item_id;
	}

	public String getGroupName()
	{
		return group_name;
	}

	public String getTab()
	{
		return tab;
	}

	public String toJson(Gson gson)
	{
		return gson.toJson(this);
	}
}
