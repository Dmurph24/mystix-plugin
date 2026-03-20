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
			Map<Integer, Integer> itemQuantities) {
		for (Item item : container.getItems()) {
			int itemId = item.getId();
			int quantity = item.getQuantity();

			if (itemId == EMPTY_SLOT_ID || quantity <= 0) {
				continue;
			}

			int canonicalId = itemManager.canonicalize(itemId);
			itemQuantities.merge(canonicalId, quantity, Integer::sum);
		}
	}

	/**
	 * Collects items from a bank container, additionally skipping placeholder items.
	 */
	static void collectBankItems(ItemContainer bankContainer, ItemManager itemManager,
			Map<Integer, Integer> itemQuantities) {
		for (Item item : bankContainer.getItems()) {
			int itemId = item.getId();
			int quantity = item.getQuantity();

			if (itemId == EMPTY_SLOT_ID || quantity <= 0) {
				continue;
			}

			ItemComposition comp = itemManager.getItemComposition(itemId);
			if (comp.getPlaceholderTemplateId() != NO_PLACEHOLDER) {
				continue;
			}

			int canonicalId = itemManager.canonicalize(itemId);
			itemQuantities.merge(canonicalId, quantity, Integer::sum);
		}
	}

	/** Converts an item quantity map into a list of BankItem payload objects. */
	static List<BankSyncPayload.BankItem> toBankItemList(Map<Integer, Integer> itemQuantities) {
		List<BankSyncPayload.BankItem> items = new ArrayList<>();
		itemQuantities.forEach((id, qty) -> items.add(new BankSyncPayload.BankItem(id, qty)));
		return items;
	}
}
