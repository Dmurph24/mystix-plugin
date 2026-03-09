package com.mystix;

import com.google.gson.Gson;
import com.mystix.api.MystixApiClient;
import com.mystix.model.BankSyncPayload;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.InventoryID;
import net.runelite.api.Item;
import net.runelite.api.ItemComposition;
import net.runelite.api.ItemContainer;
import net.runelite.api.Player;
import net.runelite.api.WorldType;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.ItemManager;

/**
 * Monitors bank open events and syncs bank contents to the Mystix API.
 * Listens for ItemContainerChanged events on InventoryID.BANK, captures
 * all items, and sends them to the backend. Deduplicates via JSON snapshot
 * comparison to avoid redundant API calls.
 */
@Slf4j
@Singleton
public class BankMemoryMonitor {
	private final Client client;
	private final MystixConfig config;
	private final MystixApiClient apiClient;
	private final EventBus eventBus;
	private final ItemManager itemManager;
	private final Gson gson;

	private String lastSyncJson = null;

	@Inject
	public BankMemoryMonitor(
			Client client,
			MystixConfig config,
			MystixApiClient apiClient,
			EventBus eventBus,
			ItemManager itemManager,
			Gson gson) {
		this.client = client;
		this.config = config;
		this.apiClient = apiClient;
		this.eventBus = eventBus;
		this.itemManager = itemManager;
		this.gson = gson;
	}

	public void start() {
		eventBus.register(this);
		log.info("BankMemoryMonitor started");
	}

	public void stop() {
		eventBus.unregister(this);
		lastSyncJson = null;
		log.debug("BankMemoryMonitor stopped");
	}

	@Subscribe
	public void onItemContainerChanged(ItemContainerChanged event) {
		if (event.getContainerId() != InventoryID.BANK.getId()) {
			return;
		}

		if (!config.syncBankMemory()) {
			return;
		}
		if (config.mystixAppKey() == null || config.mystixAppKey().isBlank()) {
			log.debug("Bank sync skipped: no App Key configured");
			return;
		}
		if (isSpecialGameMode()) {
			log.debug("Bank sync skipped: special game mode detected");
			return;
		}

		Player localPlayer = client.getLocalPlayer();
		String playerUsername = localPlayer != null ? localPlayer.getName() : null;
		if (playerUsername == null || playerUsername.isBlank()) {
			log.warn("Bank sync skipped: could not get player username");
			return;
		}

		ItemContainer bankContainer = client.getItemContainer(InventoryID.BANK);
		if (bankContainer == null) {
			return;
		}

		// Aggregate quantities by canonical item ID (handles bank + inventory merge)
		Map<Integer, Integer> itemQuantities = new LinkedHashMap<>();

		// Process bank items
		for (Item item : bankContainer.getItems()) {
			int itemId = item.getId();
			int quantity = item.getQuantity();

			// Skip empty slots and placeholders (quantity 0)
			if (itemId == -1 || quantity <= 0) {
				continue;
			}

			// Skip bank placeholders
			ItemComposition comp = itemManager.getItemComposition(itemId);
			if (comp.getPlaceholderTemplateId() != -1) {
				continue;
			}

			// Canonicalize noted items to their un-noted ID
			int canonicalId = itemManager.canonicalize(itemId);
			itemQuantities.merge(canonicalId, quantity, Integer::sum);
		}

		// Include inventory items in the bank payload
		collectContainerItems(InventoryID.INVENTORY, itemQuantities);

		// Include equipped items in the bank payload
		collectContainerItems(InventoryID.EQUIPMENT, itemQuantities);

		// Build the payload from aggregated items
		List<BankSyncPayload.BankItem> bankItems = new ArrayList<>();
		itemQuantities.forEach((id, qty) -> bankItems.add(new BankSyncPayload.BankItem(id, qty)));

		BankSyncPayload payload = new BankSyncPayload(playerUsername, bankItems);
		String json = payload.toJson(gson);

		// Deduplicate: only send if the bank contents changed
		if (json.equals(lastSyncJson)) {
			log.debug("Bank contents unchanged, skipping sync");
			return;
		}

		lastSyncJson = json;
		log.info("Syncing {} bank items for player: {}", bankItems.size(), playerUsername);
		apiClient.sendBankSync(payload);
	}

	/**
	 * Collects items from the given container and merges them into the
	 * aggregated quantities map, canonicalising item IDs and summing
	 * duplicates.
	 */
	private void collectContainerItems(InventoryID containerId, Map<Integer, Integer> itemQuantities) {
		ItemContainer container = client.getItemContainer(containerId);
		if (container == null) {
			return;
		}
		for (Item item : container.getItems()) {
			int itemId = item.getId();
			int quantity = item.getQuantity();

			if (itemId == -1 || quantity <= 0) {
				continue;
			}

			int canonicalId = itemManager.canonicalize(itemId);
			itemQuantities.merge(canonicalId, quantity, Integer::sum);
		}
	}

	/**
	 * Checks if the player is on a special game mode world.
	 */
	private boolean isSpecialGameMode() {
		EnumSet<WorldType> worldTypes = client.getWorldType();
		return worldTypes.contains(WorldType.SEASONAL)
				|| worldTypes.contains(WorldType.DEADMAN)
				|| worldTypes.contains(WorldType.FRESH_START_WORLD)
				|| worldTypes.contains(WorldType.TOURNAMENT_WORLD)
				|| worldTypes.contains(WorldType.BETA_WORLD)
				|| worldTypes.contains(WorldType.NOSAVE_MODE)
				|| worldTypes.contains(WorldType.QUEST_SPEEDRUNNING);
	}
}
