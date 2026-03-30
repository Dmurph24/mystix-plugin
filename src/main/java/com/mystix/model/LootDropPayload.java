package com.mystix.model;

import com.google.gson.Gson;
import java.util.List;

/**
 * Payload for a real-time single loot drop to the Mystix API.
 * Matches the format expected by POST /api/runelite/loot/drop/
 */
public class LootDropPayload
{
	private final String player_username;
	private final String source_client;
	private final int npc_id;
	private final String npc_name;
	private final Integer kill_count;
	private final String dropped_at;
	private final List<LootSyncPayload.LootItem> items;

	public LootDropPayload(String playerUsername, String sourceClient, int npcId, String npcName,
		Integer killCount, String droppedAt, List<LootSyncPayload.LootItem> items)
	{
		this.player_username = playerUsername;
		this.source_client = sourceClient;
		this.npc_id = npcId;
		this.npc_name = npcName;
		this.kill_count = killCount;
		this.dropped_at = droppedAt;
		this.items = items;
	}

	public String getPlayerUsername()
	{
		return player_username;
	}

	public String getSourceClient()
	{
		return source_client;
	}

	public int getNpcId()
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

	public String getDroppedAt()
	{
		return dropped_at;
	}

	public List<LootSyncPayload.LootItem> getItems()
	{
		return items;
	}

	public String toJson(Gson gson)
	{
		return gson.toJson(this);
	}
}
