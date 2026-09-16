package com.mystix;

import com.google.gson.Gson;
import com.mystix.model.BankSyncPayload;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;

/**
 * Uploads one bank-memory source (rune pouch, fish barrel, ...) with the
 * same debounce and dedupe rules as {@link BankMemoryMonitor}: a change is
 * held for {@link #DEBOUNCE_SECONDS} and collapsed with later ones so a
 * source that changes every game tick (a rune per cast, a fish per catch)
 * costs one request per burst; identical contents are never re-sent; a
 * flush sends whatever is pending right away. Gates (sync enabled, app key,
 * game mode, username) are supplied as functions so this stays unit-testable.
 */
@Slf4j
final class SourceSyncer {
	static final long DEBOUNCE_SECONDS = 30;

	private final String source;
	private final Gson gson;
	private final ScheduledExecutorService executor;
	private final BooleanSupplier syncEnabled;
	private final Supplier<String> username;
	private final Consumer<BankSyncPayload> send;

	private String lastSyncJson;
	private String pendingJson;
	private BankSyncPayload pendingPayload;
	private ScheduledFuture<?> pendingSync;

	SourceSyncer(
			String source,
			Gson gson,
			ScheduledExecutorService executor,
			BooleanSupplier syncEnabled,
			Supplier<String> username,
			Consumer<BankSyncPayload> send) {
		this.source = source;
		this.gson = gson;
		this.executor = executor;
		this.syncEnabled = syncEnabled;
		this.username = username;
		this.send = send;
	}

	/**
	 * New contents for the source (canonical item id to quantity). Uploaded
	 * after the debounce, or right away when {@code immediate} (an owned-item
	 * goal just completed, or the container was emptied into the bank).
	 */
	synchronized void submit(Map<Integer, Integer> quantities, boolean immediate) {
		if (!syncEnabled.getAsBoolean()) {
			return;
		}
		String playerUsername = username.get();
		if (playerUsername == null) {
			log.debug("{} sync skipped: could not get player username", source);
			return;
		}
		List<BankSyncPayload.BankItem> items = ItemCollector.toBankItemList(quantities == null ? Map.of() : quantities);
		Map<String, List<BankSyncPayload.BankItem>> itemsBySource = new LinkedHashMap<>();
		itemsBySource.put(source, items);
		BankSyncPayload payload = new BankSyncPayload(playerUsername, itemsBySource);
		String json = payload.toJson(gson);
		if (json.equals(lastSyncJson)) {
			// Even an immediate request has nothing new to say.
			cancelPending();
			pendingJson = null;
			pendingPayload = null;
			return;
		}
		pendingJson = json;
		pendingPayload = payload;
		cancelPending();
		if (immediate || lastSyncJson == null) {
			flushPending();
		} else {
			pendingSync = executor.schedule(this::flushPending, DEBOUNCE_SECONDS, TimeUnit.SECONDS);
		}
	}

	/** Sends whatever is pending now (logout, plugin shutdown). */
	synchronized void flushPending() {
		if (pendingPayload == null) {
			return;
		}
		lastSyncJson = pendingJson;
		log.debug("Syncing {} {} items for player: {}", pendingPayload.getTotalItemCount(), source,
				pendingPayload.getPlayerUsername());
		send.accept(pendingPayload);
		pendingPayload = null;
		pendingJson = null;
		pendingSync = null;
	}

	/** Forgets the dedupe cache so the next submit is sent even if unchanged. */
	synchronized void invalidate() {
		lastSyncJson = null;
	}

	synchronized void stop() {
		cancelPending();
		flushPending();
		lastSyncJson = null;
	}

	private void cancelPending() {
		if (pendingSync != null) {
			pendingSync.cancel(false);
			pendingSync = null;
		}
	}
}
