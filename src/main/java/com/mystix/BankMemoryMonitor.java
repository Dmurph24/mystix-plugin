package com.mystix;

import com.google.gson.Gson;
import com.mystix.api.MystixApiClient;
import com.mystix.model.BankSyncPayload;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Item;
import net.runelite.api.ItemComposition;
import net.runelite.api.ItemContainer;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.gameval.InventoryID;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.ItemManager;

@Slf4j
@Singleton
public class BankMemoryMonitor {
	private static final int EMPTY_SLOT_ID = -1;
	private static final int NO_PLACEHOLDER = -1;
	private static final long DEBOUNCE_SECONDS = 30;

	private final Client client;
	private final MystixConfig config;
	private final MystixApiClient apiClient;
	private final EventBus eventBus;
	private final ItemManager itemManager;
	private final Gson gson;
	private final ScheduledExecutorService executor;

	private String lastSyncJson = null;
	private String pendingJson = null;
	private BankSyncPayload pendingPayload = null;
	private ScheduledFuture<?> pendingSync = null;

	@Inject
	public BankMemoryMonitor(
			Client client,
			MystixConfig config,
			MystixApiClient apiClient,
			EventBus eventBus,
			ItemManager itemManager,
			Gson gson,
			ScheduledExecutorService executor) {
		this.client = client;
		this.config = config;
		this.apiClient = apiClient;
		this.eventBus = eventBus;
		this.itemManager = itemManager;
		this.gson = gson;
		this.executor = executor;
	}

	public void start() {
		eventBus.register(this);
		log.info("BankMemoryMonitor started");
	}

	public void stop() {
		eventBus.unregister(this);
		if (pendingSync != null) {
			pendingSync.cancel(false);
		}
		flushPending();
		lastSyncJson = null;
		pendingJson = null;
		pendingPayload = null;
		log.debug("BankMemoryMonitor stopped");
	}

	/**
	 * Handles bank container changes by aggregating all bank, inventory, and equipment
	 * items into a deduplicated payload and syncing to the Mystix API.
	 */
	@Subscribe
	public void onItemContainerChanged(ItemContainerChanged event) {
		if (event.getContainerId() != InventoryID.BANK) {
			return;
		}
		if (!config.syncBankMemory()) {
			return;
		}
		if (!SyncGuard.hasAppKey(config)) {
			log.debug("Bank sync skipped: no App Key configured");
			return;
		}
		if (GameModeUtil.isSpecialGameMode(client)) {
			log.debug("Bank sync skipped: special game mode detected");
			return;
		}

		String playerUsername = SyncGuard.getPlayerUsername(client);
		if (playerUsername == null) {
			log.warn("Bank sync skipped: could not get player username");
			return;
		}

		ItemContainer bankContainer = client.getItemContainer(InventoryID.BANK);
		if (bankContainer == null) {
			return;
		}

		Map<Integer, Integer> itemQuantities = new LinkedHashMap<>();
		collectBankItems(bankContainer, itemQuantities);
		collectContainerItems(InventoryID.INV, itemQuantities);
		collectContainerItems(InventoryID.WORN, itemQuantities);

		List<BankSyncPayload.BankItem> bankItems = new ArrayList<>();
		itemQuantities.forEach((id, qty) -> bankItems.add(new BankSyncPayload.BankItem(id, qty)));

		BankSyncPayload payload = new BankSyncPayload(playerUsername, bankItems);
		String json = payload.toJson(gson);

		if (json.equals(lastSyncJson)) {
			log.debug("Bank contents unchanged, skipping sync");
			return;
		}

		pendingJson = json;
		pendingPayload = payload;

		if (pendingSync != null) {
			pendingSync.cancel(false);
		}

		if (lastSyncJson == null) {
			// First sync since start — send immediately
			flushPending();
		} else {
			log.debug("Bank change detected, debouncing sync for {}s", DEBOUNCE_SECONDS);
			pendingSync = executor.schedule(this::flushPending, DEBOUNCE_SECONDS, TimeUnit.SECONDS);
		}
	}

	private synchronized void flushPending() {
		if (pendingPayload == null) {
			return;
		}
		lastSyncJson = pendingJson;
		log.info("Syncing {} bank items for player: {}", pendingPayload.getItems().size(), pendingPayload.getPlayerUsername());
		apiClient.sendBankSync(pendingPayload);
		pendingPayload = null;
		pendingJson = null;
		pendingSync = null;
	}

	/**
	 * Collects items from the bank container, skipping empty slots and placeholders,
	 * canonicalizing item IDs, and merging quantities into the aggregated map.
	 */
	private void collectBankItems(ItemContainer bankContainer, Map<Integer, Integer> itemQuantities) {
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

	/**
	 * Collects items from the given container (inventory or equipment), canonicalizing
	 * item IDs and merging quantities into the aggregated map.
	 */
	private void collectContainerItems(int containerId, Map<Integer, Integer> itemQuantities) {
		ItemContainer container = client.getItemContainer(containerId);
		if (container == null) {
			return;
		}
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
}
