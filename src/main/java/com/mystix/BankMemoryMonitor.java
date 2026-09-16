package com.mystix;

import com.google.gson.Gson;
import com.mystix.api.MystixApiClient;
import com.mystix.model.BankSyncPayload;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Consumer;
import java.util.function.Function;
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
import net.runelite.api.events.WidgetClosed;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.InventoryID;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.ItemManager;

/**
 * Syncs the bank proper and the inventory + worn gear as bank-memory
 * sources {@code bank} and {@code inventory}. Both go through one
 * {@link SourceSyncer}: inventory changes while skilling are collapsed on
 * the default cadence, changes to an item an owned-item goal is counting go
 * up on the short one, and logout always flushes. The bank container only
 * updates while the bank is open, so its last-seen contents are cached and
 * re-sent with the inventory (the dedupe makes that free).
 */
@Slf4j
@Singleton
public class BankMemoryMonitor {
	static final String SOURCE_BANK = "bank";
	static final String SOURCE_INVENTORY = "inventory";

	private final Client client;
	private final ClientThread clientThread;
	private final ItemManager itemManager;
	private final SourceSyncer syncer;
	/** Deposits made where the bank container is never sent (deposit box, bank boat). */
	private final BankDepositLedger deposits;
	/** Source to what the server last held for the goal items in it; set by the plugin. */
	private volatile Function<String, Map<Integer, Integer>> seedSupplier;

	private GameState previousGameState = GameState.UNKNOWN;
	/** Inventory + gear as last read, for deposit diffs. */
	private Map<Integer, Integer> lastCarried = new LinkedHashMap<>();

	@Inject
	public BankMemoryMonitor(
			Client client,
			ClientThread clientThread,
			MystixConfig config,
			MystixApiClient apiClient,
			ItemManager itemManager,
			Gson gson,
			ScheduledExecutorService executor) {
		this.client = client;
		this.clientThread = clientThread;
		this.itemManager = itemManager;
		this.syncer = new SourceSyncer("bank memory", gson, executor,
				() -> config.syncBankMemory() && SyncGuard.hasAppKey(config) && !GameModeUtil.isSpecialGameMode(client),
				() -> SyncGuard.getPlayerUsername(client),
				apiClient::sendBankSync);
		this.deposits = new BankDepositLedger(() -> {
			Function<String, Map<Integer, Integer>> s = seedSupplier;
			return s == null ? Map.of() : s.apply(BankDepositLedger.SOURCE);
		});
	}

	public void setSeedSupplier(Function<String, Map<Integer, Integer>> supplier) {
		this.seedSupplier = supplier;
	}

	/** Deposit boxes (and the bank boat) open this interface instead of the bank. */
	@Subscribe
	public void onWidgetLoaded(WidgetLoaded event) {
		if (event.getGroupId() == InterfaceID.BANK_DEPOSITBOX) {
			deposits.onDepositInterface(true);
		}
	}

	@Subscribe
	public void onWidgetClosed(WidgetClosed event) {
		if (event.getGroupId() == InterfaceID.BANK_DEPOSITBOX) {
			deposits.onDepositInterface(false);
		}
	}

	/** Any "Deposit…" option (Deposit-All, Deposit inventory, Deposit worn items) marks the next inventory change as a deposit. */
	@Subscribe
	public void onMenuOptionClicked(MenuOptionClicked event) {
		String option = event.getMenuOption();
		if (option != null && option.startsWith("Deposit")) {
			deposits.onDepositClick(client.getTickCount());
		}
	}

	/** Item ids active owned-item goals are counting (short debounce for them); set by the plugin. */
	public void setGoalItems(Supplier<Set<Integer>> goalItems) {
		syncer.setGoalItems(goalItems);
	}

	/**
	 * Re-reads the bank/inventory/equipment on the client thread and re-pushes
	 * it immediately, even if unchanged. Used by the roadmap panel's
	 * "Sync &amp; refresh" button. The bank container is only populated while
	 * the bank is (or was) open this session; if it is null we simply skip.
	 */
	public void forceSync() {
		clientThread.invokeLater(() -> {
			syncer.invalidate();
			push(false, true);
		});
	}

	/** Invoked after each upload so roadmap progress (net worth) can be re-read; set by the plugin. */
	public void setSyncedListener(Runnable listener) {
		syncer.setOnSent(listener);
	}

	/** Receives every payload the monitor builds (bank + inventory + equipment); set by the plugin. */
	private volatile Consumer<BankSyncPayload> payloadListener;

	public void setPayloadListener(Consumer<BankSyncPayload> listener) {
		this.payloadListener = listener;
	}

	/**
	 * Uploads the inventory and equipment right away (and the bank too when it
	 * has been opened this session). The backend replaces sources one at a
	 * time, so an inventory-only upload leaves the stored bank untouched. Used
	 * when an owned-item goal completes locally.
	 */
	public void syncInventoryNow() {
		clientThread.invokeLater(() -> push(true, true));
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event) {
		GameState newState = event.getGameState();
		if (SyncGuard.isLogout(previousGameState, newState)) {
			// A final sync, always: whatever changed since the last upload was
			// captured while logged in. Do not re-read the client here: the
			// player name is already gone and the containers may read empty.
			syncer.flushPending();
			deposits.resetSession();
			lastCarried = new LinkedHashMap<>();
		}
		previousGameState = newState;
	}

	public void stop() {
		syncer.stop();
	}

	/** Bank, inventory and worn-gear changes all feed the same debounced upload. */
	@Subscribe
	public void onItemContainerChanged(ItemContainerChanged event) {
		int id = event.getContainerId();
		if (id != InventoryID.BANK && id != InventoryID.INV && id != InventoryID.WORN) {
			return;
		}
		push(true, false);
	}

	/**
	 * Reads bank + inventory + equipment, tells the goal tracker, and submits
	 * to the syncer. Must run on the client thread.
	 *
	 * @param inventoryOnlyOk when the bank has not been opened this session,
	 *                        still push just inventory + gear (otherwise a
	 *                        missing bank pushes nothing)
	 * @param immediate       skip the debounce
	 */
	private void push(boolean inventoryOnlyOk, boolean immediate) {
		Map<String, Map<Integer, Integer>> bySource = readSources(inventoryOnlyOk);
		if (bySource == null) {
			return;
		}
		trackDeposits(bySource);
		notifyPayload(bySource);
		syncer.submitSources(bySource, immediate);
	}

	/**
	 * Items that left the inventory or gear in a deposit context are banked
	 * even though no bank container was sent. Once the bank proper is read it
	 * holds everything, so the deposits go back to empty alongside it.
	 */
	private void trackDeposits(Map<String, Map<Integer, Integer>> bySource) {
		Map<Integer, Integer> carried = bySource.get(SOURCE_INVENTORY);
		Map<Integer, Integer> removed = new LinkedHashMap<>();
		for (Map.Entry<Integer, Integer> e : lastCarried.entrySet()) {
			int delta = e.getValue() - carried.getOrDefault(e.getKey(), 0);
			if (delta > 0) {
				removed.put(e.getKey(), delta);
			}
		}
		lastCarried = new LinkedHashMap<>(carried);
		boolean changed = false;
		if (bySource.containsKey(SOURCE_BANK)) {
			changed = deposits.onBankRead();
			bySource.put(BankDepositLedger.SOURCE, deposits.contents());
		} else if (deposits.onItemsRemoved(removed, client.getTickCount())) {
			changed = true;
		}
		if (changed || (!deposits.contents().isEmpty() && !bySource.containsKey(BankDepositLedger.SOURCE))) {
			bySource.put(BankDepositLedger.SOURCE, deposits.contents());
		}
	}

	private Map<String, Map<Integer, Integer>> readSources(boolean inventoryOnlyOk) {
		if (GameModeUtil.isSpecialGameMode(client)) {
			log.debug("Bank sync skipped: special game mode detected");
			return null;
		}
		ItemContainer bankContainer = client.getItemContainer(InventoryID.BANK);
		if (bankContainer == null && !inventoryOnlyOk) {
			return null;
		}
		Map<String, Map<Integer, Integer>> bySource = new LinkedHashMap<>();
		if (bankContainer != null) {
			Map<Integer, Integer> bankQuantities = new LinkedHashMap<>();
			ItemCollector.collectBankItems(bankContainer, itemManager, bankQuantities);
			bySource.put(SOURCE_BANK, bankQuantities);
		}
		Map<Integer, Integer> invQuantities = new LinkedHashMap<>();
		collectContainerItems(InventoryID.INV, invQuantities);
		collectContainerItems(InventoryID.WORN, invQuantities);
		bySource.put(SOURCE_INVENTORY, invQuantities);
		return bySource;
	}

	private void notifyPayload(Map<String, Map<Integer, Integer>> bySource) {
		Consumer<BankSyncPayload> listener = payloadListener;
		if (listener == null) {
			return;
		}
		String playerUsername = SyncGuard.getPlayerUsername(client);
		if (playerUsername == null) {
			return;
		}
		Map<String, List<BankSyncPayload.BankItem>> itemsBySource = new LinkedHashMap<>();
		bySource.forEach((source, quantities) -> itemsBySource.put(source, ItemCollector.toBankItemList(quantities)));
		listener.accept(new BankSyncPayload(playerUsername, itemsBySource));
	}

	private void collectContainerItems(int containerId, Map<Integer, Integer> itemQuantities) {
		ItemContainer container = client.getItemContainer(containerId);
		if (container == null) {
			return;
		}
		ItemCollector.collectItems(container, itemManager, itemQuantities);
	}
}
