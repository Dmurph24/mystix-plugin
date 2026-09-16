package com.mystix;

import com.google.gson.Gson;
import com.mystix.model.BankSyncPayload;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;

/**
 * Uploads bank-memory sources (bank, inventory and gear, vaults, cargo
 * holds, pouches, storage-item ledgers) on one shared cadence:
 *
 * <ul>
 *   <li>A source's first contents of the session go up at once.</li>
 *   <li>Later changes are held and collapsed for {@link #DEFAULT_DEBOUNCE_SECONDS},
 *       or {@link #GOAL_DEBOUNCE_SECONDS} when the change touches an item an
 *       active owned-item goal is counting, so a goal the player is working on
 *       confirms quickly while routine skilling costs one request per burst.</li>
 *   <li>Unchanged contents are never re-sent.</li>
 *   <li>{@link #flushPending()} (logout, plugin stop) and an {@code immediate}
 *       submit (a goal completed locally, a container was emptied, the panel's
 *       sync button) skip the wait.</li>
 * </ul>
 *
 * Several sources may share one syncer (the bank monitor sends bank and
 * inventory together); pending contents merge per source. Gates (sync
 * enabled, app key, game mode, username) are supplied as functions so this
 * stays unit-testable.
 */
@Slf4j
final class SourceSyncer {
	static final long GOAL_DEBOUNCE_SECONDS = 30;
	static final long DEFAULT_DEBOUNCE_SECONDS = 180;

	private final String label;
	private final Gson gson;
	private final ScheduledExecutorService executor;
	private final LongSupplier clockMs;
	private final BooleanSupplier syncEnabled;
	private final Supplier<String> username;
	private final Consumer<BankSyncPayload> send;

	/** Item ids active owned-item goals are counting; set by the plugin from the goal tracker. */
	private volatile Supplier<Set<Integer>> goalItems = Set::of;
	/** Runs after every upload; set by the monitor (the bank monitor triggers a roadmap reconcile). */
	private volatile Runnable onSent;

	/** Contents last uploaded this session, per source. A source absent here was never sent. */
	private final Map<String, Map<Integer, Integer>> sentBySource = new HashMap<>();
	private final Map<String, Map<Integer, Integer>> pendingBySource = new LinkedHashMap<>();
	private String pendingUsername;
	private ScheduledFuture<?> pendingSync;
	private long pendingDueMs;

	SourceSyncer(
			String label,
			Gson gson,
			ScheduledExecutorService executor,
			BooleanSupplier syncEnabled,
			Supplier<String> username,
			Consumer<BankSyncPayload> send) {
		this(label, gson, executor, System::currentTimeMillis, syncEnabled, username, send);
	}

	SourceSyncer(
			String label,
			Gson gson,
			ScheduledExecutorService executor,
			LongSupplier clockMs,
			BooleanSupplier syncEnabled,
			Supplier<String> username,
			Consumer<BankSyncPayload> send) {
		this.label = label;
		this.gson = gson;
		this.executor = executor;
		this.clockMs = clockMs;
		this.syncEnabled = syncEnabled;
		this.username = username;
		this.send = send;
	}

	void setGoalItems(Supplier<Set<Integer>> goalItems) {
		this.goalItems = goalItems == null ? Set::of : goalItems;
	}

	void setOnSent(Runnable onSent) {
		this.onSent = onSent;
	}

	/** New contents for the syncer's single source (its label). */
	void submit(Map<Integer, Integer> quantities, boolean immediate) {
		Map<String, Map<Integer, Integer>> bySource = new LinkedHashMap<>();
		bySource.put(label, quantities == null ? Map.of() : quantities);
		submitSources(bySource, immediate);
	}

	/**
	 * New contents for one or more sources (canonical item id to quantity).
	 * Uploaded after the debounce, or right away when {@code immediate}.
	 */
	synchronized void submitSources(Map<String, Map<Integer, Integer>> bySource, boolean immediate) {
		if (bySource == null || bySource.isEmpty() || !syncEnabled.getAsBoolean()) {
			return;
		}
		String playerUsername = username.get();
		if (playerUsername == null) {
			log.debug("{} sync skipped: could not get player username", label);
			return;
		}
		boolean neverSent = false;
		boolean goalRelevant = false;
		boolean anyChange = false;
		Set<Integer> goals = goalItems.get();
		for (Map.Entry<String, Map<Integer, Integer>> e : bySource.entrySet()) {
			String source = e.getKey();
			Map<Integer, Integer> contents = new LinkedHashMap<>(e.getValue());
			Map<Integer, Integer> baseline = pendingBySource.containsKey(source)
					? pendingBySource.get(source) : sentBySource.get(source);
			Map<Integer, Integer> sent = sentBySource.get(source);
			if (sent != null && sent.equals(contents)) {
				pendingBySource.remove(source); // back to what the server has
				continue;
			}
			if (baseline != null && baseline.equals(contents)) {
				continue; // already pending as-is
			}
			anyChange = true;
			if (sent == null) {
				neverSent = true;
			}
			if (touchesGoalItem(baseline == null ? Map.of() : baseline, contents, goals)) {
				goalRelevant = true;
			}
			pendingBySource.put(source, contents);
		}
		if (pendingBySource.isEmpty()) {
			cancelPending();
			return;
		}
		pendingUsername = playerUsername;
		if (immediate || neverSent) {
			flushPending();
			return;
		}
		if (!anyChange && pendingSync != null) {
			return;
		}
		long delayMs = TimeUnit.SECONDS.toMillis(goalRelevant ? GOAL_DEBOUNCE_SECONDS : DEFAULT_DEBOUNCE_SECONDS);
		long now = clockMs.getAsLong();
		if (pendingSync != null) {
			// A shorter wait already running (goal change) is never extended.
			delayMs = Math.min(delayMs, Math.max(0, pendingDueMs - now));
			cancelPending();
		}
		pendingDueMs = now + delayMs;
		log.debug("{} change detected, debouncing sync for {}s", label, delayMs / 1000);
		pendingSync = executor.schedule(this::flushPending, delayMs, TimeUnit.MILLISECONDS);
	}

	/** Sends whatever is pending now (logout, plugin shutdown, forced sync). */
	synchronized void flushPending() {
		cancelPending();
		if (pendingBySource.isEmpty() || pendingUsername == null) {
			return;
		}
		Map<String, List<BankSyncPayload.BankItem>> itemsBySource = new LinkedHashMap<>();
		for (Map.Entry<String, Map<Integer, Integer>> e : pendingBySource.entrySet()) {
			itemsBySource.put(e.getKey(), ItemCollector.toBankItemList(e.getValue()));
			sentBySource.put(e.getKey(), e.getValue());
		}
		BankSyncPayload payload = new BankSyncPayload(pendingUsername, itemsBySource);
		pendingBySource.clear();
		log.debug("Syncing {} {} items ({}) for player: {}", payload.getTotalItemCount(), label,
				String.join(",", itemsBySource.keySet()), pendingUsername);
		send.accept(payload);
		Runnable listener = onSent;
		if (listener != null) {
			listener.run();
		}
	}

	/** Forgets what was sent so the next submit uploads even unchanged contents. */
	synchronized void invalidate() {
		sentBySource.clear();
	}

	synchronized void stop() {
		flushPending();
		sentBySource.clear();
	}

	private static boolean touchesGoalItem(Map<Integer, Integer> before, Map<Integer, Integer> after, Set<Integer> goals) {
		if (goals == null || goals.isEmpty()) {
			return false;
		}
		Set<Integer> ids = new HashSet<>(before.keySet());
		ids.addAll(after.keySet());
		for (int id : ids) {
			if (goals.contains(id) && !before.getOrDefault(id, 0).equals(after.getOrDefault(id, 0))) {
				return true;
			}
		}
		return false;
	}

	private void cancelPending() {
		if (pendingSync != null) {
			pendingSync.cancel(false);
			pendingSync = null;
		}
	}
}
