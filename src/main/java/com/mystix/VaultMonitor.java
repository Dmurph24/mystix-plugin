package com.mystix;

import com.google.gson.Gson;
import com.mystix.api.MystixApiClient;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.BiConsumer;
import java.util.function.Supplier;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.ItemContainer;
import net.runelite.api.events.GameStateChanged;
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
	private final ItemManager itemManager;
	private final SourceSyncer syncer;
	private GameState previousGameState = GameState.UNKNOWN;

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
			Gson gson,
			ScheduledExecutorService executor) {
		this.client = client;
		this.itemManager = itemManager;
		this.syncer = new SourceSyncer("vaults", gson, executor,
				() -> config.syncBankMemory() && SyncGuard.hasAppKey(config) && !GameModeUtil.isSpecialGameMode(client),
				() -> SyncGuard.getPlayerUsername(client),
				apiClient::sendBankSync);
	}

	/** Item ids active owned-item goals are counting (short debounce for them); set by the plugin. */
	public void setGoalItems(Supplier<Set<Integer>> goalItems) {
		syncer.setGoalItems(goalItems);
	}

	public void stop() {
		syncer.stop();
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event) {
		GameState newState = event.getGameState();
		if (previousGameState == GameState.LOGGED_IN && newState != GameState.LOGGED_IN) {
			syncer.flushPending();
		}
		previousGameState = newState;
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

		Map<String, Map<Integer, Integer>> bySource = new LinkedHashMap<>();
		bySource.put(source, itemQuantities);
		syncer.submitSources(bySource, false);
	}
}
