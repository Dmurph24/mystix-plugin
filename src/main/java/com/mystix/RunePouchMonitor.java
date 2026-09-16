package com.mystix;

import com.google.gson.Gson;
import com.mystix.api.MystixApiClient;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.BiConsumer;
import java.util.function.IntUnaryOperator;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.EnumComposition;
import net.runelite.api.EnumID;
import net.runelite.api.GameState;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.VarbitChanged;
import net.runelite.api.gameval.VarbitID;
import net.runelite.client.eventbus.Subscribe;

/**
 * Tracks the rune pouch (regular, divine and colossal: up to six slots) from
 * its varbits, which the game keeps current whether the pouch is carried or
 * banked. Every change is handed to the goal tracker so rune goals move
 * live, and uploaded as bank-memory source {@code rune_pouch} through a
 * debounced {@link SourceSyncer} (casting drains a rune per tick).
 */
@Slf4j
@Singleton
public class RunePouchMonitor {
	static final String SOURCE = "rune_pouch";

	private static final int[] TYPE_VARBITS = {
		VarbitID.RUNE_POUCH_TYPE_1, VarbitID.RUNE_POUCH_TYPE_2, VarbitID.RUNE_POUCH_TYPE_3,
		VarbitID.RUNE_POUCH_TYPE_4, VarbitID.RUNE_POUCH_TYPE_5, VarbitID.RUNE_POUCH_TYPE_6,
	};
	private static final int[] QUANTITY_VARBITS = {
		VarbitID.RUNE_POUCH_QUANTITY_1, VarbitID.RUNE_POUCH_QUANTITY_2, VarbitID.RUNE_POUCH_QUANTITY_3,
		VarbitID.RUNE_POUCH_QUANTITY_4, VarbitID.RUNE_POUCH_QUANTITY_5, VarbitID.RUNE_POUCH_QUANTITY_6,
	};

	private final Client client;
	private final SourceSyncer syncer;

	private boolean dirty;
	private GameState previousGameState = GameState.UNKNOWN;
	private volatile BiConsumer<String, Map<Integer, Integer>> snapshotListener;

	@Inject
	public RunePouchMonitor(
			Client client,
			MystixConfig config,
			MystixApiClient apiClient,
			Gson gson,
			ScheduledExecutorService executor) {
		this.client = client;
		this.syncer = new SourceSyncer(SOURCE, gson, executor,
				() -> config.syncBankMemory() && SyncGuard.hasAppKey(config) && !GameModeUtil.isSpecialGameMode(client),
				() -> SyncGuard.getPlayerUsername(client),
				apiClient::sendBankSync);
	}

	/** Receives every read (source, item id to quantity), before any sync gate. */
	public void setSnapshotListener(BiConsumer<String, Map<Integer, Integer>> listener) {
		this.snapshotListener = listener;
	}

	/** Item ids active owned-item goals are counting (short debounce for them); set by the plugin. */
	public void setGoalItems(Supplier<Set<Integer>> goalItems) {
		syncer.setGoalItems(goalItems);
	}

	public void stop() {
		syncer.stop();
		dirty = false;
	}

	/** Upload the current contents right away (an owned-item goal just completed). */
	public void syncNow() {
		syncer.invalidate();
		dirty = true;
	}

	@Subscribe
	public void onVarbitChanged(VarbitChanged event) {
		int id = event.getVarbitId();
		for (int i = 0; i < TYPE_VARBITS.length; i++) {
			if (id == TYPE_VARBITS[i] || id == QUANTITY_VARBITS[i]) {
				dirty = true;
				return;
			}
		}
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event) {
		GameState newState = event.getGameState();
		if (newState == GameState.LOGGED_IN && previousGameState != GameState.LOGGED_IN) {
			dirty = true; // push the pouch as it is at login
		} else if (SyncGuard.isLogout(previousGameState, newState)) {
			syncer.flushPending();
		}
		previousGameState = newState;
	}

	/** Reads on the game tick so a burst of varbit changes is read once. */
	@Subscribe
	public void onGameTick(GameTick event) {
		if (!dirty) {
			return;
		}
		dirty = false;
		EnumComposition runes = client.getEnum(EnumID.RUNEPOUCH_RUNE);
		if (runes == null) {
			return;
		}
		int[] types = new int[TYPE_VARBITS.length];
		int[] quantities = new int[QUANTITY_VARBITS.length];
		for (int i = 0; i < TYPE_VARBITS.length; i++) {
			types[i] = client.getVarbitValue(TYPE_VARBITS[i]);
			quantities[i] = client.getVarbitValue(QUANTITY_VARBITS[i]);
		}
		Map<Integer, Integer> contents = contents(types, quantities, runes::getIntValue);
		BiConsumer<String, Map<Integer, Integer>> listener = snapshotListener;
		if (listener != null) {
			listener.accept(SOURCE, contents);
		}
		syncer.submit(contents, false);
	}

	/**
	 * Slot varbits to item id and quantity. A slot's type is a rune-pouch rune
	 * index (0 = empty) resolved through the {@code RUNEPOUCH_RUNE} enum; the
	 * same rune in two slots is merged.
	 */
	static Map<Integer, Integer> contents(int[] types, int[] quantities, IntUnaryOperator runeToItemId) {
		Map<Integer, Integer> contents = new LinkedHashMap<>();
		for (int i = 0; i < types.length && i < quantities.length; i++) {
			if (types[i] <= 0 || quantities[i] <= 0) {
				continue;
			}
			int itemId = runeToItemId.applyAsInt(types[i]);
			if (itemId <= 0) {
				continue;
			}
			contents.merge(itemId, quantities[i], Integer::sum);
		}
		return contents;
	}
}
