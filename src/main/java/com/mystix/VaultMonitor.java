package com.mystix;

import com.google.gson.Gson;
import com.mystix.api.MystixApiClient;
import com.mystix.model.BankSyncPayload;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
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
 * Monitors vault and storage containers the client exposes as real item
 * containers (Seed Vault, Looting Bag, Sailing cargo holds, seed box, ...)
 * and syncs their contents to the Mystix API using the bank sync endpoint
 * with a per-source key. Every read is also handed to the goal tracker so
 * owned-item goals do not count a deposit as a loss.
 */
@Slf4j
@Singleton
public class VaultMonitor {
	/** Container id to bank-sync source. Each container is its own source:
	 * the backend replaces a whole source per upload and the plugin can only
	 * snapshot a container it has seen, so two containers must not share one. */
	private static final Map<Integer, String> VAULT_SOURCES;

	static {
		Map<Integer, String> m = new HashMap<>();
		m.put(InventoryID.SEED_VAULT, "seed_vault");
		m.put(InventoryID.LOOTING_BAG, "looting_bag");
		m.put(InventoryID.SAILING_BOAT_1_CARGOHOLD, "boat_cargo_hold_1");
		m.put(InventoryID.SAILING_BOAT_2_CARGOHOLD, "boat_cargo_hold_2");
		m.put(InventoryID.SAILING_BOAT_3_CARGOHOLD, "boat_cargo_hold_3");
		m.put(InventoryID.SAILING_BOAT_4_CARGOHOLD, "boat_cargo_hold_4");
		m.put(InventoryID.SAILING_BOAT_5_CARGOHOLD, "boat_cargo_hold_5");
		m.put(InventoryID.SAILING_TRAWLING_NET, "trawling_net");
		m.put(InventoryID.SEED_BOX, "seed_box");
		m.put(InventoryID.TACKLE_BOX, "tackle_box");
		m.put(InventoryID.FORESTRY_KIT, "forestry_kit");
		m.put(InventoryID.HUNTSMANS_KIT, "huntsmans_kit");
		VAULT_SOURCES = Collections.unmodifiableMap(m);
	}

	/** The bank-sync source for a container id, or null when not tracked. */
	static String sourceFor(int containerId) {
		return VAULT_SOURCES.get(containerId);
	}

	private final Client client;
	private final MystixConfig config;
	private final MystixApiClient apiClient;
	private final ItemManager itemManager;
	private final Gson gson;

	private final Map<String, String> lastSyncJsonBySource = new LinkedHashMap<>();

	/** Receives every container read (source, canonical item id to quantity),
	 * before any sync gate, so goal progress moves even with sync disabled. */
	private volatile BiConsumer<String, Map<Integer, Integer>> snapshotListener;

	public void setSnapshotListener(BiConsumer<String, Map<Integer, Integer>> listener) {
		this.snapshotListener = listener;
	}

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
		ItemContainer container = client.getItemContainer(event.getContainerId());
		if (container == null) {
			return;
		}

		Map<Integer, Integer> itemQuantities = new LinkedHashMap<>();
		ItemCollector.collectItems(container, itemManager, itemQuantities);
		log.debug("Container {} ({}) read: {} distinct items", event.getContainerId(), source, itemQuantities.size());

		BiConsumer<String, Map<Integer, Integer>> listener = snapshotListener;
		if (listener != null) {
			listener.accept(source, itemQuantities);
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
