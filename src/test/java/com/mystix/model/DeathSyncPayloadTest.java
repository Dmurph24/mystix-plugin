package com.mystix.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;

/**
 * Tests for DeathSyncPayload JSON serialization.
 */
public class DeathSyncPayloadTest {
	private final Gson gson = new Gson();

	@Test
	public void testPayloadStructure() {
		DeathEvent death = new DeathEvent("uuid-1", "2026-09-27T21:14:00Z", DeathEvent.KILLER_NPC,
				12223, "Vardorvis", 4405, 1129, 3417, 0, true, 330, Arrays.asList("MEMBERS"));
		String json = new DeathSyncPayload("Zezima", Collections.singletonList(death)).toJson(gson);

		assertTrue(json.contains("\"player_username\":\"Zezima\""));
		assertTrue(json.contains("\"event_uuid\":\"uuid-1\""));
		assertTrue(json.contains("\"died_at\":\"2026-09-27T21:14:00Z\""));
		assertTrue(json.contains("\"killer_type\":\"npc\""));
		assertTrue(json.contains("\"npc_id\":12223"));
		assertTrue(json.contains("\"npc_name\":\"Vardorvis\""));
		assertTrue(json.contains("\"region_id\":4405"));
		assertTrue(json.contains("\"instanced\":true"));
		assertTrue(json.contains("\"world_types\":[\"MEMBERS\"]"));
	}

	@Test
	public void testUnknownKillerOmitsTheNpc() {
		DeathEvent death = new DeathEvent("uuid-2", "2026-09-27T21:14:00Z", DeathEvent.KILLER_UNKNOWN,
				null, null, 12342, 3093, 3493, 0, false, 330, new ArrayList<>());
		String json = gson.toJson(death);
		assertTrue(json.contains("\"killer_type\":\"unknown\""));
		assertFalse(json.contains("npc_id"));
		assertFalse(json.contains("npc_name"));
	}

	@Test
	public void testEmptyHandshake() {
		DeathSyncPayload payload = new DeathSyncPayload("Zezima", new ArrayList<>());
		assertEquals("{\"player_username\":\"Zezima\",\"events\":[]}", payload.toJson(gson));
	}
}
