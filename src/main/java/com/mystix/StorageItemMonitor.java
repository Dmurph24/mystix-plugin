package com.mystix;

import com.google.gson.Gson;
import com.mystix.api.MystixApiClient;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.BiConsumer;
import java.util.function.Supplier;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Item;
import net.runelite.api.ItemComposition;
import net.runelite.api.ItemContainer;
import net.runelite.api.MenuAction;
import net.runelite.api.Skill;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.events.StatChanged;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.ItemManager;

/**
 * Drives a {@link StorageItemLedger} for every {@link StorageItemSpec}
 * (fish barrel, log basket, gem bag, coal bag, herb sack): storage items
 * the game exposes neither as a container nor as varbits. Each ledger is
 * handed to the goal tracker on every change so owned-item goals keep
 * moving while the item swallows the player's gathering, and uploaded as
 * its own bank-memory source through a debounced {@link SourceSyncer}.
 */
@Slf4j
@Singleton
public class StorageItemMonitor {
	/** Ticks after a Fill / Empty / Check click during which the effect is attributed to it. */
	private static final int ACTION_TICKS = 3;
	/** The message-box widget "Check" writes its listing to (interface 193, child 2). */
	private static final int MESSAGEBOX_GROUP = 193;
	private static final int MESSAGEBOX_TEXT = (MESSAGEBOX_GROUP << 16) | 2;

	private enum Action { FILL, EMPTY, CHECK }

	private final class Tracked {
		final StorageItemLedger ledger;
		final SourceSyncer syncer;
		final Map<Action, Integer> actionTicks = new HashMap<>();

		Tracked(StorageItemSpec spec) {
			this.ledger = new StorageItemLedger(spec, StorageItemMonitor.this::itemName, () -> {
				java.util.function.Function<String, Map<Integer, Integer>> s = seedSupplier;
				return s == null ? Map.of() : s.apply(spec.source);
			});
			this.syncer = new SourceSyncer(spec.source, gson, executor,
					() -> config.syncBankMemory() && SyncGuard.hasAppKey(config) && !GameModeUtil.isSpecialGameMode(client),
					() -> SyncGuard.getPlayerUsername(client),
					apiClient::sendBankSync);
		}

		boolean carried() {
			for (int id : ledger.spec.closedIds) {
				if (inventoryIds.contains(id) || equipmentIds.contains(id)) {
					return true;
				}
			}
			return open();
		}

		boolean open() {
			for (int id : ledger.spec.openIds) {
				if (inventoryIds.contains(id) || equipmentIds.contains(id)) {
					return true;
				}
			}
			return false;
		}

		boolean recent(Action action) {
			Integer tick = actionTicks.get(action);
			return tick != null && tick > client.getTickCount() - ACTION_TICKS;
		}
	}

	private final Client client;
	private final ClientThread clientThread;
	private final MystixConfig config;
	private final MystixApiClient apiClient;
	private final ItemManager itemManager;
	private final Gson gson;
	private final ScheduledExecutorService executor;

	private final List<Tracked> tracked = new ArrayList<>();
	private final Set<Integer> inventoryIds = new HashSet<>();
	private final Set<Integer> equipmentIds = new HashSet<>();
	/** Canonical id to quantity, for per-item inventory diffs. */
	private Map<Integer, Integer> inventoryQuantities = new HashMap<>();
	private GameState previousGameState = GameState.UNKNOWN;
	private boolean pushAll;

	private volatile BiConsumer<String, Map<Integer, Integer>> snapshotListener;
	/** Source to what the server last held for the goal items in it; set by the plugin from the goal tracker. */
	private volatile java.util.function.Function<String, Map<Integer, Integer>> seedSupplier;

	public void setSeedSupplier(java.util.function.Function<String, Map<Integer, Integer>> supplier) {
		this.seedSupplier = supplier;
	}

	@Inject
	public StorageItemMonitor(
			Client client,
			ClientThread clientThread,
			MystixConfig config,
			MystixApiClient apiClient,
			ItemManager itemManager,
			Gson gson,
			ScheduledExecutorService executor) {
		this.client = client;
		this.clientThread = clientThread;
		this.config = config;
		this.apiClient = apiClient;
		this.itemManager = itemManager;
		this.gson = gson;
		this.executor = executor;
		for (StorageItemSpec spec : StorageItemSpec.all()) {
			tracked.add(new Tracked(spec));
		}
	}

	/** Receives every ledger change (source, item id to quantity), before any sync gate. */
	public void setSnapshotListener(BiConsumer<String, Map<Integer, Integer>> listener) {
		this.snapshotListener = listener;
	}

	/** Item ids active owned-item goals are counting (short debounce for them); set by the plugin. */
	public void setGoalItems(Supplier<Set<Integer>> goalItems) {
		for (Tracked t : tracked) {
			t.syncer.setGoalItems(goalItems);
		}
	}

	public void stop() {
		for (Tracked t : tracked) {
			t.syncer.stop();
		}
		inventoryIds.clear();
		equipmentIds.clear();
		inventoryQuantities = new HashMap<>();
	}

	/** Upload every ledger right away (an owned-item goal just completed locally). */
	public void syncNow() {
		for (Tracked t : tracked) {
			t.syncer.invalidate();
		}
		pushAll = true;
	}

	private String itemName(int itemId) {
		ItemComposition c = itemManager.getItemComposition(itemId);
		return c == null ? null : c.getName();
	}

	// ------------------------------------------------------------ events

	@Subscribe
	public void onGameStateChanged(GameStateChanged event) {
		GameState newState = event.getGameState();
		if (newState == GameState.LOGGED_IN && previousGameState != GameState.LOGGED_IN) {
			clientThread.invokeLater(() -> {
				readContainer(InventoryID.INV);
				readContainer(InventoryID.WORN);
			});
		} else if (SyncGuard.isLogout(previousGameState, newState)) {
			for (Tracked t : tracked) {
				t.syncer.flushPending();
				t.ledger.resetSession();
			}
		}
		previousGameState = newState;
	}

	@Subscribe
	public void onItemContainerChanged(ItemContainerChanged event) {
		if (event.getContainerId() == InventoryID.INV || event.getContainerId() == InventoryID.WORN) {
			readContainer(event.getContainerId());
		}
	}

	private void readContainer(int containerId) {
		ItemContainer container = client.getItemContainer(containerId);
		Set<Integer> ids = containerId == InventoryID.INV ? inventoryIds : equipmentIds;
		ids.clear();
		Map<Integer, Integer> quantities = new HashMap<>();
		if (container != null) {
			for (Item item : container.getItems()) {
				if (item == null || item.getId() <= 0 || item.getQuantity() <= 0) {
					continue;
				}
				ids.add(item.getId());
				quantities.merge(itemManager.canonicalize(item.getId()), item.getQuantity(), Integer::sum);
			}
		}
		if (containerId != InventoryID.INV) {
			return;
		}
		Map<Integer, Integer> previous = inventoryQuantities;
		inventoryQuantities = quantities;
		for (Tracked t : tracked) {
			if (!t.carried()) {
				continue;
			}
			Map<Integer, Integer> added = new LinkedHashMap<>();
			Map<Integer, Integer> removed = new LinkedHashMap<>();
			for (int id : t.ledger.spec.acceptedIds) {
				int delta = quantities.getOrDefault(id, 0) - previous.getOrDefault(id, 0);
				if (delta > 0) {
					added.put(id, delta);
				} else if (delta < 0) {
					removed.put(id, -delta);
				}
			}
			if (!added.isEmpty() || !removed.isEmpty()) {
				t.ledger.onInventoryDiff(added, removed, t.recent(Action.FILL), t.recent(Action.EMPTY));
			}
		}
	}

	@Subscribe
	public void onChatMessage(ChatMessage event) {
		String message = event.getMessage();
		if (event.getType() == ChatMessageType.SPAM) {
			for (Tracked t : tracked) {
				if (t.open()) {
					t.ledger.onGatherMessage(message);
				}
			}
		} else if (event.getType() == ChatMessageType.GAMEMESSAGE) {
			// Every carried ledger sees the line: "You empty all of your
			// containers into the bank." applies to all of them at once.
			for (Tracked t : tracked) {
				if (t.carried()) {
					t.ledger.onGameMessage(message);
				}
			}
		}
	}

	@Subscribe
	public void onStatChanged(StatChanged event) {
		Skill skill = event.getSkill();
		int gained = event.getXp() - lastXp.getOrDefault(skill, event.getXp());
		lastXp.put(skill, event.getXp());
		if (gained <= 0) {
			return;
		}
		for (Tracked t : tracked) {
			if (t.carried()) {
				t.ledger.onXp(skill, gained);
			}
		}
	}

	private final Map<Skill, Integer> lastXp = new HashMap<>();

	@Subscribe
	public void onMenuOptionClicked(MenuOptionClicked event) {
		int itemId = -1;
		Action action = null;
		MenuAction menuAction = event.getMenuAction();
		if (menuAction == MenuAction.WIDGET_TARGET_ON_WIDGET) {
			// Item used on the container (or the container on an item): a fill.
			Widget target = event.getWidget();
			Widget selected = client.getSelectedWidget();
			int targetId = target == null ? -1 : target.getItemId();
			int selectedId = selected == null ? -1 : selected.getItemId();
			for (Tracked t : tracked) {
				if (t.ledger.spec.isContainerItem(targetId) || t.ledger.spec.isContainerItem(selectedId)) {
					t.actionTicks.put(Action.FILL, client.getTickCount());
				}
			}
			return;
		}
		if (menuAction == MenuAction.CC_OP || menuAction == MenuAction.CC_OP_LOW_PRIORITY) {
			Widget widget = event.getWidget();
			if (widget != null) {
				itemId = widget.getItemId();
			}
		}
		String option = event.getMenuOption();
		if (option == null) {
			return;
		}
		if (option.equalsIgnoreCase("Fill")) {
			action = Action.FILL;
		} else if (option.equalsIgnoreCase("Empty") || option.equalsIgnoreCase("Empty-to-bank")) {
			action = Action.EMPTY;
		} else if (option.equalsIgnoreCase("Check")) {
			action = Action.CHECK;
		} else {
			return;
		}
		for (Tracked t : tracked) {
			if (t.ledger.spec.isContainerItem(itemId)) {
				t.actionTicks.put(action, client.getTickCount());
			}
		}
	}

	/** The Check listing for barrels, baskets and the coal bag arrives in a message box. */
	@Subscribe
	public void onWidgetLoaded(WidgetLoaded event) {
		if (event.getGroupId() != MESSAGEBOX_GROUP) {
			return;
		}
		clientThread.invokeLater(() -> {
			Widget widget = client.getWidget(MESSAGEBOX_TEXT);
			if (widget == null || widget.getText() == null) {
				return;
			}
			String text = widget.getText();
			for (Tracked t : tracked) {
				if (t.recent(Action.CHECK) && t.ledger.onCheckText(text)) {
					return;
				}
			}
		});
	}

	@Subscribe
	public void onGameTick(GameTick event) {
		boolean push = pushAll;
		pushAll = false;
		for (Tracked t : tracked) {
			boolean changed = t.ledger.settle(t.open());
			// Nothing is said about a container this session has not observed:
			// the server's figure stands until there is something better.
			if (!changed && !(push && t.ledger.isKnown())) {
				continue;
			}
			Map<Integer, Integer> contents = t.ledger.contents();
			BiConsumer<String, Map<Integer, Integer>> listener = snapshotListener;
			if (listener != null) {
				listener.accept(t.ledger.spec.source, contents);
			}
			// An emptied container is worth telling the server about at once:
			// its items are about to show up in the bank snapshot.
			t.syncer.submit(contents, push || contents.isEmpty());
		}
	}
}
