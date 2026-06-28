package com.mystix;

import static org.junit.Assert.assertEquals;

import com.mystix.model.BankSyncPayload;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

/**
 * Tests that ItemCollector produces canonical, order-independent item lists.
 */
public class ItemCollectorTest {

	@Test
	public void toBankItemListSortsByItemId() {
		Map<Integer, Integer> quantities = new LinkedHashMap<>();
		quantities.put(995, 100);
		quantities.put(385, 2);
		quantities.put(4151, 1);

		List<BankSyncPayload.BankItem> items = ItemCollector.toBankItemList(quantities);

		assertEquals(385, items.get(0).getItemId());
		assertEquals(995, items.get(1).getItemId());
		assertEquals(4151, items.get(2).getItemId());
	}

	@Test
	public void toBankItemListOrderIndependentOfInsertionOrder() {
		/* Same contents inserted in different order must yield the same canonical list,
		   so a pure reorder is dedup-identical and won't trigger a redundant sync. */
		Map<Integer, Integer> insertedOneWay = new LinkedHashMap<>();
		insertedOneWay.put(995, 100);
		insertedOneWay.put(385, 2);
		insertedOneWay.put(4151, 1);

		Map<Integer, Integer> insertedAnotherWay = new LinkedHashMap<>();
		insertedAnotherWay.put(4151, 1);
		insertedAnotherWay.put(995, 100);
		insertedAnotherWay.put(385, 2);

		List<BankSyncPayload.BankItem> listA = ItemCollector.toBankItemList(insertedOneWay);
		List<BankSyncPayload.BankItem> listB = ItemCollector.toBankItemList(insertedAnotherWay);

		assertEquals(listA.size(), listB.size());
		for (int i = 0; i < listA.size(); i++) {
			assertEquals(listA.get(i).getItemId(), listB.get(i).getItemId());
			assertEquals(listA.get(i).getQuantity(), listB.get(i).getQuantity());
		}
	}
}
