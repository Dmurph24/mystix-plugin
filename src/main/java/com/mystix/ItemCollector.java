package com.mystix;

import com.mystix.model.BankSyncPayload;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.runelite.api.Item;
import net.runelite.api.ItemComposition;
import net.runelite.api.ItemContainer;
import net.runelite.client.game.ItemManager;

/**
 * Shared utilities for collecting and canonicalizing items from RuneLite containers.
 *
 * <p>Quantities are summed as {@code long}: OSRS lifted the 2,147,483,647
 * max-cash cap, so stacks folded onto one canonical id (and totals across
 * containers downstream) can exceed int range. {@link Item#getQuantity()} is
 * read into a {@code long}, which also keeps this source-compatible should
 * RuneLite widen it later.
 */
final class ItemCollector {
	static final int EMPTY_SLOT_ID = -1;
	private static final int NO_PLACEHOLDER = -1;

	private ItemCollector() {
	}

	/**
	 * Collects items from a container, canonicalizing IDs and merging quantities.
	 * Skips empty slots and items with quantity <= 0.
	 */
	static void collectItems(ItemContainer container, ItemManager itemManager,
			Map<Integer, Long> itemQuantities) {
		for (Item item : container.getItems()) {
			int itemId = item.getId();
			long quantity = item.getQuantity();

			if (itemId == EMPTY_SLOT_ID || quantity <= 0) {
				continue;
			}

			int canonicalId = itemManager.canonicalize(itemId);
			itemQuantities.merge(canonicalId, quantity, Long::sum);
		}
	}

	/**
	 * Collects items from a bank container, additionally skipping placeholder items.
	 */
	static void collectBankItems(ItemContainer bankContainer, ItemManager itemManager,
			Map<Integer, Long> itemQuantities) {
		for (Item item : bankContainer.getItems()) {
			int itemId = item.getId();
			long quantity = item.getQuantity();

			if (itemId == EMPTY_SLOT_ID || quantity <= 0) {
				continue;
			}

			ItemComposition comp = itemManager.getItemComposition(itemId);
			if (comp.getPlaceholderTemplateId() != NO_PLACEHOLDER) {
				continue;
			}

			int canonicalId = itemManager.canonicalize(itemId);
			itemQuantities.merge(canonicalId, quantity, Long::sum);
		}
	}

	/**
	 * Converts an item quantity map into a list of BankItem payload objects, sorted by item ID.
	 * Sorting makes the payload canonical so identical contents always serialize identically,
	 * regardless of container slot order — a reorder alone won't look like a change to dedup.
	 */
	static List<BankSyncPayload.BankItem> toBankItemList(Map<Integer, Long> itemQuantities) {
		List<BankSyncPayload.BankItem> items = new ArrayList<>();
		itemQuantities.entrySet().stream()
				.sorted(Map.Entry.comparingByKey())
				.forEach(e -> items.add(new BankSyncPayload.BankItem(e.getKey(), e.getValue())));
		return items;
	}

	/**
	 * Widens a small, bounded container's contents (rune pouch, plank sack,
	 * storage-item ledgers) to the long quantities the bank-memory and goal
	 * pipeline carries. Order is kept; null keys or values are dropped.
	 */
	static Map<Integer, Long> widen(Map<Integer, Integer> quantities) {
		Map<Integer, Long> out = new LinkedHashMap<>();
		if (quantities != null) {
			quantities.forEach((id, qty) -> {
				if (id != null && qty != null) {
					out.put(id, qty.longValue());
				}
			});
		}
		return out;
	}

	/**
	 * Narrows long quantities for a bounded container ledger that counts in
	 * ints (a fish barrel holds 28). Saturates rather than wrapping, so a
	 * figure beyond int range can never turn negative.
	 */
	static Map<Integer, Integer> narrowSaturated(Map<Integer, Long> quantities) {
		Map<Integer, Integer> out = new LinkedHashMap<>();
		if (quantities != null) {
			quantities.forEach((id, qty) -> {
				if (id != null && qty != null) {
					out.put(id, (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, qty)));
				}
			});
		}
		return out;
	}
}
