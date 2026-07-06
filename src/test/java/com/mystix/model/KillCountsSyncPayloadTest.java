package com.mystix.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import java.util.Map;
import java.util.TreeMap;
import org.junit.Test;

/**
 * Tests for KillCountsSyncPayload JSON serialization.
 */
public class KillCountsSyncPayloadTest {
	private final Gson gson = new Gson();

	@Test
	public void testPayloadStructure() {
		Map<String, Integer> kcs = new TreeMap<>();
		kcs.put("zulrah", 500);
		kcs.put("chambers of xeric", 200);
		KillCountsSyncPayload payload = new KillCountsSyncPayload("TestPlayer", kcs);
		String json = payload.toJson(gson);

		assertNotNull(json);
		assertTrue(json.contains("\"player_username\":\"TestPlayer\""));
		// TreeMap orders keys, and Gson serialises the map with string keys.
		assertTrue(json.contains(
				"\"kill_counts\":{\"chambers of xeric\":200,\"zulrah\":500}"));
	}

	@Test
	public void testGetters() {
		Map<String, Integer> kcs = new TreeMap<>();
		kcs.put("vorkath", 1000);
		KillCountsSyncPayload payload = new KillCountsSyncPayload("Zezima", kcs);
		assertEquals("Zezima", payload.getPlayerUsername());
		assertEquals(1, payload.getKillCounts().size());
		assertEquals(Integer.valueOf(1000), payload.getKillCounts().get("vorkath"));
	}
}
