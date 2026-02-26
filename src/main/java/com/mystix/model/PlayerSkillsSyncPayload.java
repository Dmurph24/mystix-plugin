package com.mystix.model;

import com.google.gson.Gson;
import java.util.Map;

/**
 * Payload for syncing player skills to the Mystix API.
 * Matches the format expected by POST /api/runelite/skills/
 */
public class PlayerSkillsSyncPayload
{
	private final String player;
	private final Map<String, Integer> skills;

	public PlayerSkillsSyncPayload(String player, Map<String, Integer> skills)
	{
		this.player = player;
		this.skills = skills;
	}

	public String getPlayer()
	{
		return player;
	}

	public Map<String, Integer> getSkills()
	{
		return skills;
	}

	public String toJson()
	{
		Gson gson = new Gson();
		return gson.toJson(this);
	}
}
