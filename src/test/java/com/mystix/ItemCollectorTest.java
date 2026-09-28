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
		Map<Integer, Long> quantities = new LinkedHashMap<>();
		quantities.put(995, 100L);
		quantities.put(385, 2L);
		quantities.put(4151, 1L);

		List<BankSyncPayload.BankItem> items = ItemCollector.toBankItemList(quantities);

		assertEquals(385, items.get(0).getItemId());
		assertEquals(995, items.get(1).getItemId());
		assertEquals(4151, items.get(2).getItemId());
	}

	@Test
	public void toBankItemListOrderIndependentOfInsertionOrder() {
		/* Same contents inserted in different order must yield the same canonical list,
		   so a pure reorder is dedup-identical and won't trigger a redundant sync. */
		Map<Integer, Long> insertedOneWay = new LinkedHashMap<>();
		insertedOneWay.put(995, 100L);
		insertedOneWay.put(385, 2L);
		insertedOneWay.put(4151, 1L);

		Map<Integer, Long> insertedAnotherWay = new LinkedHashMap<>();
		insertedAnotherWay.put(4151, 1L);
		insertedAnotherWay.put(995, 100L);
		insertedAnotherWay.put(385, 2L);

		List<BankSyncPayload.BankItem> listA = ItemCollector.toBankItemList(insertedOneWay);
		List<BankSyncPayload.BankItem> listB = ItemCollector.toBankItemList(insertedAnotherWay);

		assertEquals(listA.size(), listB.size());
		for (int i = 0; i < listA.size(); i++) {
			assertEquals(listA.get(i).getItemId(), listB.get(i).getItemId());
			assertEquals(listA.get(i).getQuantity(), listB.get(i).getQuantity());
		}
	}

	@Test
	public void toBankItemListCarriesQuantitiesPastMaxCash() {
		Map<Integer, Long> quantities = new LinkedHashMap<>();
		quantities.put(995, 5_000_000_000L);
		List<BankSyncPayload.BankItem> items = ItemCollector.toBankItemList(quantities);
		assertEquals(5_000_000_000L, items.get(0).getQuantity());
	}

	@Test
	public void widenAndNarrowSaturated() {
		Map<Integer, Integer> small = new LinkedHashMap<>();
		small.put(13439, 28);
		small.put(383, null);
		Map<Integer, Long> wide = ItemCollector.widen(small);
		assertEquals(1, wide.size());
		assertEquals(Long.valueOf(28), wide.get(13439));

		Map<Integer, Long> big = new LinkedHashMap<>();
		big.put(995, 5_000_000_000L);
		big.put(13439, 28L);
		Map<Integer, Integer> narrow = ItemCollector.narrowSaturated(big);
		assertEquals(Integer.valueOf(Integer.MAX_VALUE), narrow.get(995));
		assertEquals(Integer.valueOf(28), narrow.get(13439));
	}
}
