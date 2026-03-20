package com.mystix.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

/**
 * Tests for BankSyncPayload JSON serialization.
 */
public class BankSyncPayloadTest {
	private final Gson gson = new Gson();

	@Test
	public void testPayloadStructure() {
		List<BankSyncPayload.BankItem> items = Arrays.asList(
				new BankSyncPayload.BankItem(4151, 1),
				new BankSyncPayload.BankItem(385, 500));
		Map<String, List<BankSyncPayload.BankItem>> itemsBySource = new LinkedHashMap<>();
		itemsBySource.put("bank", items);
		BankSyncPayload payload = new BankSyncPayload("TestPlayer", itemsBySource);
		String json = payload.toJson(gson);

		assertNotNull(json);
		assertTrue(json.contains("\"player_username\":\"TestPlayer\""));
		assertTrue(json.contains("\"bank\""));
		assertTrue(json.contains("\"item_id\":4151"));
		assertTrue(json.contains("\"quantity\":1"));
		assertTrue(json.contains("\"item_id\":385"));
		assertTrue(json.contains("\"quantity\":500"));
	}

	@Test
	public void testEmptyItemsMap() {
		BankSyncPayload payload = new BankSyncPayload("EmptyPlayer", Collections.emptyMap());
		String json = payload.toJson(gson);

		assertNotNull(json);
		assertTrue(json.contains("\"player_username\":\"EmptyPlayer\""));
		assertTrue(json.contains("\"items\":{}"));
	}

	@Test
	public void testEmptySourceList() {
		Map<String, List<BankSyncPayload.BankItem>> itemsBySource = new LinkedHashMap<>();
		itemsBySource.put("bank", Collections.emptyList());
		BankSyncPayload payload = new BankSyncPayload("EmptyBank", itemsBySource);
		String json = payload.toJson(gson);

		assertNotNull(json);
		assertTrue(json.contains("\"bank\":[]"));
	}

	@Test
	public void testGetters() {
		BankSyncPayload.BankItem item = new BankSyncPayload.BankItem(4151, 10);
		assertEquals(4151, item.getItemId());
		assertEquals(10, item.getQuantity());

		Map<String, List<BankSyncPayload.BankItem>> itemsBySource = new LinkedHashMap<>();
		itemsBySource.put("bank", Collections.singletonList(item));
		BankSyncPayload payload = new BankSyncPayload("Zezima", itemsBySource);
		assertEquals("Zezima", payload.getPlayerUsername());
		assertEquals(1, payload.getItems().size());
		assertEquals(1, payload.getTotalItemCount());
	}

	@Test
	public void testMultipleSources() {
		List<BankSyncPayload.BankItem> bankItems = Arrays.asList(
				new BankSyncPayload.BankItem(4151, 1));
		List<BankSyncPayload.BankItem> vaultItems = Arrays.asList(
				new BankSyncPayload.BankItem(385, 200));
		Map<String, List<BankSyncPayload.BankItem>> itemsBySource = new LinkedHashMap<>();
		itemsBySource.put("bank", bankItems);
		itemsBySource.put("seed_vault", vaultItems);
		BankSyncPayload payload = new BankSyncPayload("MultiPlayer", itemsBySource);

		assertEquals(2, payload.getTotalItemCount());
		String json = payload.toJson(gson);
		assertTrue(json.contains("\"bank\""));
		assertTrue(json.contains("\"seed_vault\""));
	}

	@Test
	public void testLargeQuantityValues() {
		BankSyncPayload.BankItem item = new BankSyncPayload.BankItem(995, 2147483647);
		Map<String, List<BankSyncPayload.BankItem>> itemsBySource = new LinkedHashMap<>();
		itemsBySource.put("bank", Collections.singletonList(item));
		BankSyncPayload payload = new BankSyncPayload("RichPlayer", itemsBySource);
		String json = payload.toJson(gson);

		assertNotNull(json);
		assertTrue(json.contains("\"item_id\":995"));
		assertTrue(json.contains("\"quantity\":2147483647"));
	}
}
