package com.mystix.model;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

/**
 * Tests for BankSyncPayload JSON serialization.
 */
public class BankSyncPayloadTest {

	@Test
	public void testPayloadStructure() {
		List<BankSyncPayload.BankItem> items = Arrays.asList(
				new BankSyncPayload.BankItem(4151, 1),
				new BankSyncPayload.BankItem(385, 500));
		BankSyncPayload payload = new BankSyncPayload("TestPlayer", items);
		String json = payload.toJson();

		assertNotNull(json);
		assertTrue(json.contains("\"player_username\":\"TestPlayer\""));
		assertTrue(json.contains("\"items\""));
		assertTrue(json.contains("\"item_id\":4151"));
		assertTrue(json.contains("\"quantity\":1"));
		assertTrue(json.contains("\"item_id\":385"));
		assertTrue(json.contains("\"quantity\":500"));
	}

	@Test
	public void testEmptyItemsList() {
		BankSyncPayload payload = new BankSyncPayload("EmptyPlayer", Collections.emptyList());
		String json = payload.toJson();

		assertNotNull(json);
		assertTrue(json.contains("\"player_username\":\"EmptyPlayer\""));
		assertTrue(json.contains("\"items\":[]"));
	}

	@Test
	public void testGetters() {
		BankSyncPayload.BankItem item = new BankSyncPayload.BankItem(4151, 10);
		org.junit.Assert.assertEquals(4151, item.getItemId());
		org.junit.Assert.assertEquals(10, item.getQuantity());

		BankSyncPayload payload = new BankSyncPayload("Zezima",
				Collections.singletonList(item));
		org.junit.Assert.assertEquals("Zezima", payload.getPlayerUsername());
		org.junit.Assert.assertEquals(1, payload.getItems().size());
	}

	@Test
	public void testLargeQuantityValues() {
		BankSyncPayload.BankItem item = new BankSyncPayload.BankItem(995, 2147483647);
		BankSyncPayload payload = new BankSyncPayload("RichPlayer",
				Collections.singletonList(item));
		String json = payload.toJson();

		assertNotNull(json);
		assertTrue(json.contains("\"item_id\":995"));
		assertTrue(json.contains("\"quantity\":2147483647"));
	}
}
