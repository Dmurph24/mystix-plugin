package com.mystix.model;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.*;

public class PlayerSkillsSyncPayloadTest
{
	@Test
	public void testPayloadCreation()
	{
		Map<String, Integer> skills = new HashMap<>();
		skills.put("Attack", 75);
		skills.put("Defence", 70);
		skills.put("Strength", 80);

		PlayerSkillsSyncPayload payload = new PlayerSkillsSyncPayload("TestPlayer", skills);

		assertEquals("TestPlayer", payload.getPlayer());
		assertEquals(3, payload.getSkills().size());
		assertEquals(Integer.valueOf(75), payload.getSkills().get("Attack"));
	}

	@Test
	public void testJsonSerialization()
	{
		Map<String, Integer> skills = new HashMap<>();
		skills.put("Attack", 75);
		skills.put("Defence", 70);

		PlayerSkillsSyncPayload payload = new PlayerSkillsSyncPayload("TestPlayer", skills);
		String json = payload.toJson();

		assertNotNull(json);
		assertTrue(json.contains("TestPlayer"));
		assertTrue(json.contains("Attack"));
		assertTrue(json.contains("Defence"));

		Gson gson = new Gson();
		JsonObject jsonObject = gson.fromJson(json, JsonObject.class);
		assertEquals("TestPlayer", jsonObject.get("player").getAsString());
		assertTrue(jsonObject.has("skills"));
		JsonObject skillsObject = jsonObject.getAsJsonObject("skills");
		assertEquals(75, skillsObject.get("Attack").getAsInt());
		assertEquals(70, skillsObject.get("Defence").getAsInt());
	}

	@Test
	public void testEmptySkills()
	{
		Map<String, Integer> skills = new HashMap<>();
		PlayerSkillsSyncPayload payload = new PlayerSkillsSyncPayload("EmptyPlayer", skills);

		assertEquals("EmptyPlayer", payload.getPlayer());
		assertTrue(payload.getSkills().isEmpty());
	}
}
