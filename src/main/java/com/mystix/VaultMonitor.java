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
import java.util.function.Consumer;
import java.util.function.Supplier;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.ItemContainer;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.gameval.VarPlayerID;
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

	/** Set on container ids the client reports for interface-owned inventories (a boat's hold arrives as 965 | 0x8000). */
	static final int REMOTE_CONTAINER_FLAG = 0x8000;

	/** The bank-sync source for a container id, or null when not tracked. */
	static String sourceFor(int containerId) {
		String source = VAULT_SOURCES.get(containerId);
		if (source == null && (containerId & REMOTE_CONTAINER_FLAG) != 0) {
			int base = containerId & ~REMOTE_CONTAINER_FLAG;
			if (base >= InventoryID.SAILING_BOAT_1_CARGOHOLD && base <= InventoryID.SAILING_TRAWLING_NET) {
				source = VAULT_SOURCES.get(base);
			}
		}
		return source;
	}

	/** Crew member option that banks the current boat's hold without any container event reaching the client. */
	static final String BANK_CARGO_OPTION = "Bank-cargo";

	private final Client client;
	private final ItemManager itemManager;
	private final SourceSyncer syncer;
	private GameState previousGameState = GameState.UNKNOWN;
	/** Contents last read this session, per source. */
	private final Map<String, Map<Integer, Integer>> lastRead = new HashMap<>();
	/** Receives the hold's contents when a crew member banks them; set by the plugin. */
	private volatile Consumer<Map<Integer, Integer>> cargoBankedListener;

	public void setCargoBankedListener(Consumer<Map<Integer, Integer>> listener) {
		this.cargoBankedListener = listener;
	}

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
		lastRead.clear();
	}

	/** The source of the current boat's hold, from the Sailing varp holding its inventory id, or null. */
	static String currentHoldSource(int holdInventoryId) {
		String source = sourceFor(holdInventoryId);
		return source != null && source.startsWith("boat_cargo_hold_") ? source : null;
	}

	/**
	 * "Bank-cargo" on a crew member moves the hold into the bank with no
	 * container event, so the bank would count the items again on its next
	 * read while the hold snapshot still held them. Hand the hold's contents
	 * to the bank-deposits overlay (cleared when the bank is read) and empty
	 * the hold locally and on the server.
	 */
	@Subscribe
	public void onMenuOptionClicked(MenuOptionClicked event) {
		if (!BANK_CARGO_OPTION.equalsIgnoreCase(event.getMenuOption())) {
			return;
		}
		String current = currentHoldSource(client.getVarpValue(VarPlayerID.SAILING_BOAT_CARGOHOLD_INV));
		Map<Integer, Integer> banked = new LinkedHashMap<>();
		Map<String, Map<Integer, Integer>> emptied = new LinkedHashMap<>();
		for (Map.Entry<String, Map<Integer, Integer>> e : lastRead.entrySet()) {
			boolean hold = e.getKey().startsWith("boat_cargo_hold_");
			if (!hold || e.getValue().isEmpty() || (current != null && !current.equals(e.getKey()))) {
				continue;
			}
			e.getValue().forEach((id, qty) -> banked.merge(id, qty, Integer::sum));
			emptied.put(e.getKey(), new LinkedHashMap<>());
		}
		log.debug("Bank-cargo: current hold {} -> banking {} from {}", current, banked, emptied.keySet());
		if (emptied.isEmpty()) {
			return;
		}
		BiConsumer<String, Map<Integer, Integer>> listener = snapshotListener;
		for (String source : emptied.keySet()) {
			lastRead.put(source, new LinkedHashMap<>());
			if (listener != null) {
				listener.accept(source, new LinkedHashMap<>());
			}
		}
		Consumer<Map<Integer, Integer>> bankedListener = cargoBankedListener;
		if (bankedListener != null) {
			bankedListener.accept(banked);
		}
		syncer.submitSources(emptied, true);
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event) {
		GameState newState = event.getGameState();
		if (SyncGuard.isLogout(previousGameState, newState)) {
			syncer.flushPending();
		}
		previousGameState = newState;
	}

	@Subscribe
	public void onItemContainerChanged(ItemContainerChanged event) {
		String source = sourceFor(event.getContainerId());
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
		lastRead.put(source, new LinkedHashMap<>(itemQuantities));

		BiConsumer<String, Map<Integer, Integer>> listener = snapshotListener;
		if (listener != null) {
			listener.accept(source, itemQuantities);
		}

		Map<String, Map<Integer, Integer>> bySource = new LinkedHashMap<>();
		bySource.put(source, itemQuantities);
		syncer.submitSources(bySource, false);
	}
}
