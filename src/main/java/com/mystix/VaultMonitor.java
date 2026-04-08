package com.mystix;

import com.google.gson.Gson;
import com.mystix.api.MystixApiClient;
import com.mystix.model.BankSyncPayload;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.ItemContainer;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.gameval.InventoryID;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.ItemManager;

/**
 * Monitors vault and storage containers (Seed Vault, Looting Bag) and syncs
 * their contents to the Mystix API using the bank sync endpoint with a
 * per-source key.
 */
@Slf4j
@Singleton
public class VaultMonitor {
	private static final Map<Integer, String> VAULT_SOURCES = Map.of(
		InventoryID.SEED_VAULT, "seed_vault",
		InventoryID.LOOTING_BAG, "looting_bag"
	);

	private final Client client;
	private final MystixConfig config;
	private final MystixApiClient apiClient;
	private final ItemManager itemManager;
	private final Gson gson;

	private final Map<String, String> lastSyncJsonBySource = new LinkedHashMap<>();

	@Inject
	public VaultMonitor(
			Client client,
			MystixConfig config,
			MystixApiClient apiClient,
			ItemManager itemManager,
			Gson gson) {
		this.client = client;
		this.config = config;
		this.apiClient = apiClient;
		this.itemManager = itemManager;
		this.gson = gson;
	}

	public void stop() {
		lastSyncJsonBySource.clear();
	}

	@Subscribe
	public void onItemContainerChanged(ItemContainerChanged event) {
		String source = VAULT_SOURCES.get(event.getContainerId());
		if (source == null) {
			return;
		}
		if (!config.syncBankMemory()) {
			return;
		}
		if (!SyncGuard.hasAppKey(config)) {
			log.debug("Vault sync skipped: no App Key configured");
			return;
		}
		if (GameModeUtil.isSpecialGameMode(client)) {
			log.debug("Vault sync skipped: special game mode detected");
			return;
		}

		String playerUsername = SyncGuard.getPlayerUsername(client);
		if (playerUsername == null) {
			log.warn("Vault sync skipped: could not get player username");
			return;
		}

		ItemContainer container = client.getItemContainer(event.getContainerId());
		if (container == null) {
			return;
		}

		Map<Integer, Integer> itemQuantities = new LinkedHashMap<>();
		ItemCollector.collectItems(container, itemManager, itemQuantities);

		List<BankSyncPayload.BankItem> items = ItemCollector.toBankItemList(itemQuantities);

		Map<String, List<BankSyncPayload.BankItem>> itemsBySource = new LinkedHashMap<>();
		itemsBySource.put(source, items);

		BankSyncPayload payload = new BankSyncPayload(playerUsername, itemsBySource);
		String json = payload.toJson(gson);

		if (json.equals(lastSyncJsonBySource.get(source))) {
			log.debug("{} contents unchanged, skipping sync", source);
			return;
		}

		lastSyncJsonBySource.put(source, json);
		log.debug("Syncing {} {} items for player: {}", items.size(), source, playerUsername);
		apiClient.sendBankSync(payload);
	}
}
