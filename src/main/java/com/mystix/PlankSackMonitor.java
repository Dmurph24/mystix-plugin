package com.mystix;

import com.google.gson.Gson;
import com.mystix.api.MystixApiClient;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.BiConsumer;
import java.util.function.IntUnaryOperator;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.VarbitChanged;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.client.eventbus.Subscribe;

/**
 * Tracks the plank sack from its per-plank varbits (exact, no interface
 * needed), feeding the goal tracker live and uploading bank-memory source
 * {@code plank_sack} through a debounced {@link SourceSyncer}.
 */
@Slf4j
@Singleton
public class PlankSackMonitor {
	static final String SOURCE = "plank_sack";

	/** Varbit to the plank item it counts. */
	static final Map<Integer, Integer> PLANK_VARBITS;

	static {
		Map<Integer, Integer> m = new LinkedHashMap<>();
		m.put(VarbitID.PLANK_SACK_PLAIN, ItemID.WOODPLANK);
		m.put(VarbitID.PLANK_SACK_OAK, ItemID.PLANK_OAK);
		m.put(VarbitID.PLANK_SACK_TEAK, ItemID.PLANK_TEAK);
		m.put(VarbitID.PLANK_SACK_MAHOGANY, ItemID.PLANK_MAHOGANY);
		m.put(VarbitID.PLANK_SACK_CAMPHOR, ItemID.PLANK_CAMPHOR);
		m.put(VarbitID.PLANK_SACK_IRONWOOD, ItemID.PLANK_IRONWOOD);
		m.put(VarbitID.PLANK_SACK_ROSEWOOD, ItemID.PLANK_ROSEWOOD);
		PLANK_VARBITS = java.util.Collections.unmodifiableMap(m);
	}

	private final Client client;
	private final SourceSyncer syncer;

	private boolean dirty;
	private GameState previousGameState = GameState.UNKNOWN;
	private volatile BiConsumer<String, Map<Integer, Integer>> snapshotListener;

	@Inject
	public PlankSackMonitor(
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

	public void setSnapshotListener(BiConsumer<String, Map<Integer, Integer>> listener) {
		this.snapshotListener = listener;
	}

	public void stop() {
		syncer.stop();
		dirty = false;
	}

	public void syncNow() {
		syncer.invalidate();
		dirty = true;
	}

	@Subscribe
	public void onVarbitChanged(VarbitChanged event) {
		if (PLANK_VARBITS.containsKey(event.getVarbitId())) {
			dirty = true;
		}
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event) {
		GameState newState = event.getGameState();
		if (newState == GameState.LOGGED_IN && previousGameState != GameState.LOGGED_IN) {
			dirty = true;
		} else if (previousGameState == GameState.LOGGED_IN && newState != GameState.LOGGED_IN) {
			syncer.flushPending();
		}
		previousGameState = newState;
	}

	@Subscribe
	public void onGameTick(GameTick event) {
		if (!dirty) {
			return;
		}
		dirty = false;
		Map<Integer, Integer> contents = contents(client::getVarbitValue);
		BiConsumer<String, Map<Integer, Integer>> listener = snapshotListener;
		if (listener != null) {
			listener.accept(SOURCE, contents);
		}
		syncer.submit(contents, false);
	}

	/** Plank item id to count from the varbit values. */
	static Map<Integer, Integer> contents(IntUnaryOperator varbitValue) {
		Map<Integer, Integer> contents = new LinkedHashMap<>();
		for (Map.Entry<Integer, Integer> e : PLANK_VARBITS.entrySet()) {
			int count = varbitValue.applyAsInt(e.getKey());
			if (count > 0) {
				contents.put(e.getValue(), count);
			}
		}
		return contents;
	}
}
