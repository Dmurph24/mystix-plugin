package com.mystix.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

/**
 * Tests for CollectionLogSyncPayload JSON serialization.
 */
public class CollectionLogSyncPayloadTest {
	private final Gson gson = new Gson();

	@Test
	public void testPayloadStructure() {
		List<Integer> ids = Arrays.asList(12819, 12921, 13265);
		CollectionLogSyncPayload payload = new CollectionLogSyncPayload("TestPlayer", ids);
		String json = payload.toJson(gson);

		assertNotNull(json);
		assertTrue(json.contains("\"player_username\":\"TestPlayer\""));
		assertTrue(json.contains("\"collection_log\":[12819,12921,13265]"));
		assertTrue(json.contains("\"collection_log_item_count\":3"));
	}

	@Test
	public void testItemCountMatchesListSize() {
		CollectionLogSyncPayload payload = new CollectionLogSyncPayload(
				"Zezima", Arrays.asList(1, 2, 3, 4));
		assertEquals(4, payload.getItemCount());
		assertEquals("Zezima", payload.getPlayerUsername());
		assertEquals(4, payload.getCollectionLog().size());
	}

	@Test
	public void testEmptyList() {
		CollectionLogSyncPayload payload = new CollectionLogSyncPayload(
				"EmptyPlayer", Collections.emptyList());
		String json = payload.toJson(gson);

		assertNotNull(json);
		assertTrue(json.contains("\"collection_log\":[]"));
		assertTrue(json.contains("\"collection_log_item_count\":0"));
		assertEquals(0, payload.getItemCount());
	}
}
