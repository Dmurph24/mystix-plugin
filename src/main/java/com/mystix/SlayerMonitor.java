/*
 * Task and area name resolution in resolveTaskName()/resolveAreaName() is
 * adapted from RuneLite's SlayerPlugin (BSD 2-Clause):
 * Copyright (c) 2017, Tyler <https://github.com/tylerthardy>
 * Copyright (c) 2018, Shaun Dreclin <shaundreclin@gmail.com>
 * All rights reserved. See https://github.com/runelite/runelite for terms.
 */
package com.mystix;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.mystix.api.MystixApiClient;
import com.mystix.model.SlayerSyncPayload;
import com.mystix.model.SlayerTaskEvent;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Point;
import net.runelite.api.Skill;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.ScriptPostFired;
import net.runelite.api.events.VarbitChanged;
import net.runelite.api.gameval.DBTableID;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;

/**
 * Monitors slayer state (assignment, points, streaks, block list, unlocks,
 * the Task Storage slot) and task transitions, and syncs them to the Mystix
 * API.
 *
 * <p>State is read directly from the slayer varps/varbits (no dependency on
 * RuneLite's Slayer plugin being enabled), with task and area names resolved
 * client-side through the game-cache DB tables. Task outcomes are detected by
 * comparing consecutive snapshots: the streak counter is authoritative for
 * completion, the block list gaining the old task id is authoritative for
 * blocks, and the completion chat message is attached as a corroborating
 * label. The backend re-derives outcomes from the same signals, so the claim
 * sent here never decides anything alone.
 *
 * <p>Unsent transition events are persisted to RSProfile config so a crash or
 * failed POST loses nothing; {@code event_uuid} makes replays idempotent.
 */
@Slf4j
@Singleton
public class SlayerMonitor {
	private static final int LOGIN_SYNC_DELAY_SECONDS = 5;
	private static final int RESYNC_THROTTLE_TICKS = 3;
	/** Minimum interval between amount-only syncs (task progress ticks down every kill). */
	private static final long AMOUNT_SYNC_MIN_INTERVAL_MS = 60_000L;
	/** How many ticks a completion chat line stays attachable to a transition. */
	private static final int CHAT_ATTACH_WINDOW_TICKS = 10;
	/**
	 * Grace ticks between detecting a transition and finalizing its event. The
	 * completion chat message can arrive a tick after the varp flips (observed
	 * live: a real completion finalized with chat_matched=false), so the event
	 * waits briefly for trailing chat before it is built and sent.
	 */
	private static final int EVENT_FINALIZE_DELAY_TICKS = 3;
	/**
	 * Max gap between kills that still counts toward active grind time. Slow
	 * tasks (on-task bosses, banking trips) have legitimate multi-minute gaps;
	 * anything longer reads as a break and contributes nothing.
	 */
	private static final long ACTIVE_GAP_CAP_MS = 5 * 60_000L;
	private static final String TIMING_CONFIG_KEY = "slayerTaskTiming";
	/** SLAYER_TARGET value meaning "a boss task"; resolve via the sublist table. */
	private static final int BOSS_TASK_SENTINEL = 98;
	private static final String PENDING_EVENTS_CONFIG_KEY = "pendingSlayerEvents";
	/**
	 * The slayer task-offer dialog (Mortimer's 2-3 choices, each with a name,
	 * an amount range and a modifier). It raises no WidgetLoaded event, being
	 * a nested child of the game frame rather than its own interface, so it is
	 * polled instead. A wrong id costs nothing beyond a null lookup: the parse
	 * only accepts offer-shaped rows, so it can never yield bad data.
	 *
	 * <p>The whole subtree under the container is read, not one child list.
	 * Reading only the dynamic children captured every name and amount but
	 * never a modifier from any live dialog, so the modifier rows do not live
	 * where the names do.
	 */
	private static final int TASK_OFFER_GROUP = 236;
	private static final int TASK_OFFER_CHILD = 3;
	private static final int OFFER_SCAN_INTERVAL_TICKS = 5;
	/** How deep under the dialog container its text rows may sit. */
	private static final int OFFER_SCAN_MAX_DEPTH = 5;
	/**
	 * How long a capture stays the "same dialog" for downgrade purposes. A
	 * re-read of the open dialog must never replace a richer capture with a
	 * thinner one; the same two tasks offered again later is a new dialog and
	 * replaces it whatever it carries.
	 */
	private static final long OFFER_REFRESH_WINDOW_MS = 60_000L;
	/** How long captured offers ride along in the state sync before expiring. */
	private static final long OFFER_RETENTION_MS = 30 * 60_000L;
	private static final String STORED_TASK_CONFIG_KEY = "storedSlayerTask";

	/**
	 * Clientscripts that draw the rewards-interface Tasks tab: 328 runs once at
	 * interface setup, 421 re-runs on every var retransmit while it stays open.
	 * Both render the current and stored task from the IF1-IF6 transfer varps,
	 * so firing right after them reads values the server just served.
	 */
	private static final int SLAYER_REWARDS_TASKS_INIT_SCRIPT = 328;
	private static final int SLAYER_REWARDS_TASKS_REDRAW_SCRIPT = 421;

	// Loose on purpose: matches the known completion message variants ("You've
	// completed X tasks in a row...", points and Wilderness forms). The backend
	// treats chat as corroboration, so a miss degrades gracefully.
	private static final java.util.regex.Pattern COMPLETION_CHAT_PATTERN =
			java.util.regex.Pattern.compile("completed.{0,40}?\\d[\\d,]* tasks?",
					java.util.regex.Pattern.CASE_INSENSITIVE);

	private static final Set<Integer> WATCHED_VARPS = Set.of(
			VarPlayerID.SLAYER_COUNT,
			VarPlayerID.SLAYER_TARGET,
			VarPlayerID.SLAYER_AREA,
			VarPlayerID.SLAYER_COUNT_ORIGINAL,
			VarPlayerID.SLAYER_REWARDS_UNLOCKS,
			VarPlayerID.SLAYER_REWARDS_UNLOCKS1,
			VarPlayerID.SLAYER_REWARDS_UNLOCKS2,
			VarPlayerID.SLAYER_REWARDS_BLOCKED);
	private static final Set<Integer> WATCHED_VARBITS = Set.of(
			VarbitID.SLAYER_MASTER,
			VarbitID.SLAYER_POINTS,
			VarbitID.SLAYER_TASKS_COMPLETED,
			VarbitID.SLAYER_WILDERNESS_TASKS_COMPLETED,
			VarbitID.SLAYER_TARGET_BOSSID);

	// Ordered block list varps: the shared 15 slots (1 + 12 + 2 diary).
	private static final int[] BLOCK_LIST_VARPS = {
			VarPlayerID.SLAYER_REWARDS_BLOCKED,
			VarPlayerID.SLAYER_REWARDS_BLOCKED_2,
			VarPlayerID.SLAYER_REWARDS_BLOCKED_3,
			VarPlayerID.SLAYER_REWARDS_BLOCKED_4,
			VarPlayerID.SLAYER_REWARDS_BLOCKED_5,
			VarPlayerID.SLAYER_REWARDS_BLOCKED_6,
			VarPlayerID.SLAYER_REWARDS_BLOCKED_7,
			VarPlayerID.SLAYER_REWARDS_BLOCKED_8,
			VarPlayerID.SLAYER_REWARDS_BLOCKED_9,
			VarPlayerID.SLAYER_REWARDS_BLOCKED_10,
			VarPlayerID.SLAYER_REWARDS_BLOCKED_11,
			VarPlayerID.SLAYER_REWARDS_BLOCKED_12,
			VarPlayerID.SLAYER_REWARDS_BLOCKED_13,
			VarPlayerID.SLAYER_REWARDS_BLOCKED_DIARY_1,
			VarPlayerID.SLAYER_REWARDS_BLOCKED_DIARY_2};

	/** Snapshot of the slayer varp/varbit state at one read. */
	static class Snapshot {
		Integer taskId;
		String taskName;
		Integer bossTaskId;
		Integer areaId;
		Integer masterId;
		int amountRemaining;
		int amountOriginal;
		int points;
		int streak;
		int wildernessStreak;
		List<Integer> blockList = new ArrayList<>();
		Map<String, Integer> unlockBitfields = new LinkedHashMap<>();
		Integer slayerLevel;
		Long slayerXp;

		boolean hasTask() {
			return amountRemaining > 0 && (taskId != null && taskId != 0);
		}

		/**
		 * Blocked task ids, decoded from the raw block varps. Each varp packs
		 * up to four ids as bytes (verified live: one varp held Blue dragons /
		 * Cave horrors / Spiritual creatures / Metal dragons as 25|80|89|127).
		 * The raw values still go to the server unchanged; this decode exists
		 * only for the membership check in transition detection.
		 */
		java.util.Set<Integer> decodedBlockIds() {
			java.util.Set<Integer> ids = new java.util.HashSet<>();
			for (int raw : blockList) {
				for (int shift = 0; shift <= 24; shift += 8) {
					int id = (raw >> shift) & 0xFF;
					if (id != 0) {
						ids.add(id);
					}
				}
			}
			return ids;
		}

		/**
		 * Task identity, ignoring progress. Boss tasks differ by sublist id.
		 * The master is part of identity: a different master can reassign the
		 * SAME monster in one interaction (task id unchanged, amount and
		 * master varbit updated together), and without the master in the
		 * comparison that transition is invisible: the old row never closes
		 * and the skip goes unrecorded. The same master can never deal the
		 * same monster back-to-back (the current task is excluded from the
		 * assignment pool), so including it costs nothing.
		 */
		boolean sameTask(Snapshot other) {
			return java.util.Objects.equals(taskId, other.taskId)
					&& java.util.Objects.equals(bossTaskId, other.bossTaskId)
					&& java.util.Objects.equals(areaId, other.areaId)
					&& java.util.Objects.equals(masterId, other.masterId);
		}
	}

	/**
	 * The Task Storage slot, captured opportunistically. No persistent varp
	 * carries the banked task: the server holds it and only copies it into the
	 * shared IF4/IF5/IF6 interface-transfer varps (amount, task id, boss
	 * sublist id) while the slayer rewards screen is open. Captures persist to
	 * RSProfile config so the last known slot survives restarts.
	 */
	static class StoredTaskCapture {
		int taskId;
		int amount;
		int bossTaskId;
		String taskName;
		String capturedAt;
	}

	private final Client client;
	private final ClientThread clientThread;
	private final MystixConfig config;
	private final MystixApiClient apiClient;
	private final ScheduledExecutorService executorService;
	private final Gson gson;
	private final ConfigManager configManager;

	/** A detected transition waiting out the chat grace window. */
	private static class PendingTransition {
		final Snapshot before;
		final Snapshot after;
		final boolean blockListGained;
		final String assignedAt;
		final int detectedTick;
		// Timing captured at stage time, so a new assignment arriving during
		// the grace window can't wipe it before the event is built.
		final long firstKillMs;
		final long lastKillMs;
		final long activeMs;
		final long pendingZeroMs;

		PendingTransition(Snapshot before, Snapshot after, boolean blockListGained,
				String assignedAt, int detectedTick,
				long firstKillMs, long lastKillMs, long activeMs, long pendingZeroMs) {
			this.before = before;
			this.after = after;
			this.blockListGained = blockListGained;
			this.assignedAt = assignedAt;
			this.detectedTick = detectedTick;
			this.firstKillMs = firstKillMs;
			this.lastKillMs = lastKillMs;
			this.activeMs = activeMs;
			this.pendingZeroMs = pendingZeroMs;
		}
	}

	private GameState previousGameState = GameState.UNKNOWN;
	private boolean checkPending;
	private int lastReadTick = -1;
	private String lastSyncJson;
	private long lastAmountSyncMs;
	private StoredTaskCapture storedTask;
	private List<Map<String, Object>> pendingOffers = new ArrayList<>();
	private List<String> pendingOfferRows;
	private long offersCapturedMs;
	private String lastOffersKey;
	private int lastOfferScanTick = -1;
	private String offersCapturedAt;
	private boolean storedTaskDirty;
	private Snapshot previous;
	private String currentTaskAssignedAt;
	private String lastCompletionChatText;
	private int lastCompletionChatTick = -1;
	private PendingTransition draft;
	private final List<SlayerTaskEvent> pendingEvents = new ArrayList<>();

	/**
	 * Active-time tracking for the current task, fed by SLAYER_COUNT
	 * decrements ("first monster kill to last", with AFK gaps capped out).
	 * Persisted per profile so a task spanning sessions keeps its timing.
	 */
	private int lastCountSeen = -1;
	private long firstKillMs;
	private long lastKillMs;
	private long activeMs;
	/** Candidate final-kill moment: count hit 0, outcome not yet known. */
	private long pendingZeroMs;

	private void recordKill(long nowMs) {
		if (previous != null && previous.hasTask()) {
			timingTaskKey = taskKey(previous);
		}
		if (firstKillMs == 0) {
			firstKillMs = nowMs;
		} else if (nowMs - lastKillMs <= ACTIVE_GAP_CAP_MS) {
			activeMs += nowMs - lastKillMs;
		}
		lastKillMs = nowMs;
		persistTiming();
	}

	private void resetTiming() {
		timingTaskKey = 0;
		firstKillMs = 0;
		lastKillMs = 0;
		activeMs = 0;
		pendingZeroMs = 0;
		configManager.unsetRSProfileConfiguration("mystix", TIMING_CONFIG_KEY);
	}

	private long timingTaskKey;

	/** Identity key for timing ownership: task id + boss sublist id. */
	private static long taskKey(Snapshot s) {
		long task = s.taskId == null ? 0 : s.taskId;
		long boss = s.bossTaskId == null ? 0 : s.bossTaskId;
		return (task << 32) | boss;
	}

	private void persistTiming() {
		Map<String, Long> stored = new LinkedHashMap<>();
		stored.put("task", timingTaskKey);
		stored.put("first", firstKillMs);
		stored.put("last", lastKillMs);
		stored.put("active", activeMs);
		configManager.setRSProfileConfiguration("mystix", TIMING_CONFIG_KEY, gson.toJson(stored));
	}

	/**
	 * Restores persisted timing, but only when it belongs to the task the
	 * session actually has: the task can change while the plugin is not
	 * running (finished or skipped on another client), and stale timing must
	 * not attach to the new assignment.
	 */
	private void restoreTiming(Snapshot now) {
		String stored = configManager.getRSProfileConfiguration("mystix", TIMING_CONFIG_KEY);
		if (stored == null || stored.isEmpty()) {
			return;
		}
		try {
			Map<String, Double> parsed = gson.fromJson(
					stored, new TypeToken<Map<String, Double>>() { }.getType());
			if (parsed == null) {
				return;
			}
			long storedKey = parsed.getOrDefault("task", 0.0).longValue();
			if (!now.hasTask() || storedKey != taskKey(now)) {
				log.debug("Discarding slayer timing for a different task");
				resetTiming();
				return;
			}
			timingTaskKey = storedKey;
			firstKillMs = parsed.getOrDefault("first", 0.0).longValue();
			lastKillMs = parsed.getOrDefault("last", 0.0).longValue();
			activeMs = parsed.getOrDefault("active", 0.0).longValue();
		} catch (RuntimeException e) {
			log.warn("Discarding unparseable slayer timing state", e);
			resetTiming();
		}
	}

	private static String isoOrNull(long epochMs) {
		return epochMs == 0 ? null : Instant.ofEpochMilli(epochMs).toString();
	}

	@Inject
	public SlayerMonitor(
			Client client,
			ClientThread clientThread,
			MystixConfig config,
			MystixApiClient apiClient,
			ScheduledExecutorService executorService,
			Gson gson,
			ConfigManager configManager) {
		this.client = client;
		this.clientThread = clientThread;
		this.config = config;
		this.apiClient = apiClient;
		this.executorService = executorService;
		this.gson = gson;
		this.configManager = configManager;
	}

	public void stop() {
		previousGameState = GameState.UNKNOWN;
		checkPending = false;
		lastReadTick = -1;
		lastSyncJson = null;
		lastAmountSyncMs = 0;
		previous = null;
		currentTaskAssignedAt = null;
		lastCompletionChatText = null;
		lastCompletionChatTick = -1;
		draft = null;
		// Drop the in-memory capture but keep the persisted copy: the stored
		// task is only readable while the rewards screen is open, so the last
		// capture is restored on the next login.
		storedTask = null;
		storedTaskDirty = false;
		// Zero in-memory timing but keep the persisted copy: a task spanning a
		// client restart restores it on the next login.
		lastCountSeen = -1;
		firstKillMs = 0;
		lastKillMs = 0;
		activeMs = 0;
		pendingZeroMs = 0;
	}

	/** Re-reads and re-pushes slayer state; clears the dedup cache. */
	public void forceSync() {
		clientThread.invokeLater(() -> {
			lastSyncJson = null;
			readAndSync(true);
		});
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event) {
		GameState newState = event.getGameState();
		if (newState == GameState.LOGGED_IN && previousGameState != GameState.LOGGED_IN) {
			// Slayer varps and DB tables settle shortly after login.
			executorService.schedule(() -> clientThread.invokeLater(() -> {
				previous = null; // don't fabricate a transition across sessions
				restorePendingEvents();
				readAndSync(true);
			}), LOGIN_SYNC_DELAY_SECONDS, TimeUnit.SECONDS);
		} else if (previousGameState == GameState.LOGGED_IN && newState != GameState.LOGGED_IN) {
			readAndSync(true);
		}
		previousGameState = newState;
	}

	@Subscribe
	public void onChatMessage(ChatMessage event) {
		if (event.getType() != ChatMessageType.GAMEMESSAGE) {
			return;
		}
		String message = net.runelite.client.util.Text.removeTags(event.getMessage());
		if (COMPLETION_CHAT_PATTERN.matcher(message).find()) {
			lastCompletionChatText = message;
			lastCompletionChatTick = client.getTickCount();
			checkPending = true;
		}
	}

	@Subscribe
	public void onScriptPostFired(ScriptPostFired event) {
		if (event.getScriptId() == SLAYER_REWARDS_TASKS_INIT_SCRIPT
				|| event.getScriptId() == SLAYER_REWARDS_TASKS_REDRAW_SCRIPT) {
			captureStoredTask();
		}
	}

	/**
	 * Reads the Task Storage slot from the IF transfer varps. Client thread
	 * only (script events fire there).
	 *
	 * <p>The IF varps are scratch shared by many interfaces (bank, colosseum,
	 * bond conversion, ...). The tasks-tab script also mirrors the ACTIVE task
	 * into IF1/IF2; requiring those to match the live task varps proves the
	 * scratch currently serves the slayer rewards screen.
	 */
	private void captureStoredTask() {
		if (!config.syncSlayer() || !SyncGuard.hasAppKey(config)) {
			return;
		}
		if (client.getVarpValue(VarPlayerID.IF1) != client.getVarpValue(VarPlayerID.SLAYER_COUNT)
				|| client.getVarpValue(VarPlayerID.IF2)
						!= client.getVarpValue(VarPlayerID.SLAYER_TARGET)) {
			log.debug("Skipping stored-task capture: IF varps serve another interface");
			return;
		}
		int amount = client.getVarpValue(VarPlayerID.IF4);
		int taskId = client.getVarpValue(VarPlayerID.IF5);
		int bossTaskId = client.getVarpValue(VarPlayerID.IF6);
		if (!hasStoredTask(taskId, amount)) {
			// Normalize "no stored task" so residual junk in the scratch varps
			// can't masquerade as a banked assignment.
			amount = 0;
			taskId = 0;
			bossTaskId = 0;
		}
		if (storedTask != null && storedTask.taskId == taskId
				&& storedTask.amount == amount && storedTask.bossTaskId == bossTaskId) {
			return; // unchanged; keep the original capture timestamp
		}
		StoredTaskCapture capture = new StoredTaskCapture();
		capture.taskId = taskId;
		capture.amount = amount;
		capture.bossTaskId = bossTaskId;
		capture.taskName = taskId == 0
				? null
				: resolveTaskName(taskId, bossTaskId == 0 ? null : bossTaskId);
		capture.capturedAt = Instant.now().toString();
		storedTask = capture;
		storedTaskDirty = true;
		configManager.setRSProfileConfiguration(
				"mystix", STORED_TASK_CONFIG_KEY, gson.toJson(capture));
		checkPending = true;
		log.debug("Captured stored slayer task: {} x {}", capture.amount, capture.taskName);
	}

	/**
	 * Whether the raw IF varp pair encodes a banked task, mirroring the render
	 * conditions in the rewards-interface script (which draws name-only for a
	 * boss slot with amount -1).
	 */
	static boolean hasStoredTask(int taskId, int amount) {
		if (taskId == BOSS_TASK_SENTINEL) {
			return amount > 0 || amount == -1;
		}
		return taskId != 0 && amount > 0;
	}

	@Subscribe
	public void onVarbitChanged(VarbitChanged event) {
		if (event.getVarpId() == VarPlayerID.SLAYER_COUNT) {
			trackKillMoment(event.getValue());
		}
		if (WATCHED_VARPS.contains(event.getVarpId())
				|| WATCHED_VARBITS.contains(event.getVarbitId())) {
			checkPending = true;
		}
	}

	/**
	 * Feeds active-time tracking from SLAYER_COUNT changes. A decrement to a
	 * positive value is kills landing; a drop to exactly 0 is either the final
	 * kill or a cancel, so it is held as a candidate until the transition's
	 * outcome is known. Increments (assignment, extend) only move the
	 * reference value.
	 */
	private void trackKillMoment(int newValue) {
		int prev = lastCountSeen;
		lastCountSeen = newValue;
		if (prev <= 0 || newValue >= prev) {
			return;
		}
		long nowMs = System.currentTimeMillis();
		if (newValue == 0) {
			pendingZeroMs = nowMs;
		} else {
			recordKill(nowMs);
		}
	}

	@Subscribe
	public void onGameTick(GameTick event) {
		if (lastOfferScanTick == -1
				|| client.getTickCount() - lastOfferScanTick >= OFFER_SCAN_INTERVAL_TICKS) {
			lastOfferScanTick = client.getTickCount();
			scanForTaskOffers();
		}
		if (draft != null
				&& client.getTickCount() - draft.detectedTick >= EVENT_FINALIZE_DELAY_TICKS) {
			finalizeDraft();
			readAndSync(false);
			return;
		}
		if (!checkPending) {
			return;
		}
		if (lastReadTick != -1 && client.getTickCount() - lastReadTick < RESYNC_THROTTLE_TICKS) {
			return;
		}
		checkPending = false;
		lastReadTick = client.getTickCount();
		readAndSync(false);
	}

	/** Reads the snapshot, detects transitions, and syncs. Client thread only. */
	private void readAndSync(boolean force) {
		if (!config.syncSlayer() || !SyncGuard.hasAppKey(config)) {
			return;
		}
		if (GameModeUtil.isSpecialGameMode(client)) {
			return;
		}
		String playerUsername = SyncGuard.getPlayerUsername(client);
		if (playerUsername == null) {
			return;
		}

		if (force) {
			// Logout/force flush: don't leave a transition waiting on chat.
			finalizeDraft();
		}

		Snapshot now = readSnapshot();
		if (previous == null) {
			// First read of the session: adopt persisted timing only if it
			// belongs to this task, and the last stored-task capture unless a
			// live capture already replaced it.
			restoreTiming(now);
			if (storedTask == null) {
				restoreStoredTask();
			}
		}
		detectTransition(previous, now);
		boolean taskChanged = previous == null
				|| previous.hasTask() != now.hasTask()
				|| (now.hasTask() && !now.sameTask(previous));
		if (taskChanged && now.hasTask()) {
			currentTaskAssignedAt = Instant.now().toString();
		}
		previous = now;

		if (draft != null && !force) {
			// A transition is waiting out the chat grace window; the finalize
			// pass in onGameTick sends everything together in a moment.
			return;
		}
		if (force) {
			finalizeDraft();
		}

		boolean amountWindowOpen =
				System.currentTimeMillis() - lastAmountSyncMs >= AMOUNT_SYNC_MIN_INTERVAL_MS;
		if (!force && !taskChanged && pendingEvents.isEmpty() && !amountWindowOpen
				&& !storedTaskDirty) {
			return;
		}

		SlayerSyncPayload payload = buildPayload(playerUsername, now);
		storedTaskDirty = false;
		String json = payload.toJson(gson);
		if (json.equals(lastSyncJson)) {
			log.debug("Slayer state unchanged, skipping sync");
			return;
		}
		lastSyncJson = json;
		lastAmountSyncMs = System.currentTimeMillis();

		List<SlayerTaskEvent> sent = new ArrayList<>(pendingEvents);
		log.debug("Syncing slayer state ({} events) for player: {}", sent.size(), playerUsername);
		apiClient.sendSlayerSync(payload, () -> onEventsAcknowledged(sent));
	}

	private Snapshot readSnapshot() {
		Snapshot s = new Snapshot();
		int taskId = client.getVarpValue(VarPlayerID.SLAYER_TARGET);
		s.taskId = taskId == 0 ? null : taskId;
		s.amountRemaining = client.getVarpValue(VarPlayerID.SLAYER_COUNT);
		s.amountOriginal = client.getVarpValue(VarPlayerID.SLAYER_COUNT_ORIGINAL);
		int areaId = client.getVarpValue(VarPlayerID.SLAYER_AREA);
		s.areaId = areaId == 0 ? null : areaId;
		int bossId = client.getVarbitValue(VarbitID.SLAYER_TARGET_BOSSID);
		s.bossTaskId = bossId == 0 ? null : bossId;
		int masterId = client.getVarbitValue(VarbitID.SLAYER_MASTER);
		s.masterId = masterId == 0 ? null : masterId;
		s.points = client.getVarbitValue(VarbitID.SLAYER_POINTS);
		s.streak = client.getVarbitValue(VarbitID.SLAYER_TASKS_COMPLETED);
		s.wildernessStreak = client.getVarbitValue(VarbitID.SLAYER_WILDERNESS_TASKS_COMPLETED);
		for (int varp : BLOCK_LIST_VARPS) {
			s.blockList.add(client.getVarpValue(varp));
		}
		s.unlockBitfields.put(String.valueOf(VarPlayerID.SLAYER_REWARDS_UNLOCKS),
				client.getVarpValue(VarPlayerID.SLAYER_REWARDS_UNLOCKS));
		s.unlockBitfields.put(String.valueOf(VarPlayerID.SLAYER_REWARDS_UNLOCKS1),
				client.getVarpValue(VarPlayerID.SLAYER_REWARDS_UNLOCKS1));
		s.unlockBitfields.put(String.valueOf(VarPlayerID.SLAYER_REWARDS_UNLOCKS2),
				client.getVarpValue(VarPlayerID.SLAYER_REWARDS_UNLOCKS2));
		s.slayerLevel = client.getRealSkillLevel(Skill.SLAYER);
		s.slayerXp = (long) client.getSkillExperience(Skill.SLAYER);
		if (s.hasTask()) {
			s.taskName = resolveTaskName(s.taskId, s.bossTaskId);
		}
		return s;
	}

	/** Detects a task ending/changing between snapshots and stages a draft event. */
	private void detectTransition(Snapshot before, Snapshot now) {
		if (before == null || !before.hasTask()) {
			return;
		}
		boolean ended = !now.hasTask() || !now.sameTask(before);
		if (!ended) {
			return;
		}
		if (draft != null) {
			// A second transition before the first finalized (fast skip chain);
			// close the first with whatever chat state exists now.
			finalizeDraft();
		}

		// Membership is checked on the byte-decoded ids, and as a before/after
		// delta so stray non-block bytes in these varps can't false-positive.
		boolean blockListGained = before.taskId != null
				&& !before.decodedBlockIds().contains(before.taskId)
				&& now.decodedBlockIds().contains(before.taskId);
		draft = new PendingTransition(
				before, now, blockListGained, currentTaskAssignedAt, client.getTickCount(),
				firstKillMs, lastKillMs, activeMs, pendingZeroMs);
		resetTiming();
		currentTaskAssignedAt = null;
		log.debug("Slayer task transition staged: {}", before.taskName);
	}

	/** Builds and queues the event for the staged transition, if any. */
	private void finalizeDraft() {
		if (draft == null) {
			return;
		}
		Snapshot before = draft.before;
		Snapshot after = draft.after;
		// The window spans both directions around the detection tick: chat can
		// precede the varp flip or trail it by a tick or two (observed live: a
		// real completion's message landed after detection and was missed).
		boolean chatRecent = lastCompletionChatTick != -1
				&& Math.abs(lastCompletionChatTick - draft.detectedTick) <= CHAT_ATTACH_WINDOW_TICKS;

		// The count reaching 0 is only evidence of a last kill once a charge has
		// been ruled out: a cancel clears the same varp to 0.
		boolean countedDownToZero = draft.pendingZeroMs > 0;

		String outcome = claimOutcome(
				before.streak, after.streak,
				before.wildernessStreak, after.wildernessStreak,
				before.points, after.points,
				chatRecent, draft.blockListGained, countedDownToZero);
		// A completed task by definition reached 0; the before-snapshot can lag
		// the final kill (observed live: a completion closed with 1 remaining).
		int amountAtEnd = "completed".equals(outcome) ? 0 : before.amountRemaining;

		// The drop to 0 was the final kill only if the task completed; on a
		// cancel it is just the varp clearing and contributes nothing.
		long lastKill = draft.lastKillMs;
		long active = draft.activeMs;
		if ("completed".equals(outcome) && draft.pendingZeroMs > 0) {
			if (lastKill > 0 && draft.pendingZeroMs - lastKill <= ACTIVE_GAP_CAP_MS) {
				active += draft.pendingZeroMs - lastKill;
			}
			lastKill = draft.pendingZeroMs;
		}
		long first = draft.firstKillMs;

		SlayerTaskEvent taskEvent = new SlayerTaskEvent(
				UUID.randomUUID().toString(),
				outcome,
				before.taskId,
				before.taskName,
				before.bossTaskId,
				before.areaId,
				before.masterId,
				before.amountOriginal,
				amountAtEnd,
				before.streak,
				after.streak,
				before.wildernessStreak,
				after.wildernessStreak,
				before.points,
				after.points,
				countedDownToZero,
				chatRecent,
				chatRecent ? lastCompletionChatText : null,
				draft.blockListGained,
				draft.assignedAt,
				Instant.now().toString(),
				isoOrNull(first),
				isoOrNull(lastKill),
				first == 0 ? null : active / 1000);
		pendingEvents.add(taskEvent);
		persistPendingEvents();
		lastCompletionChatTick = -1;
		lastCompletionChatText = null;
		draft = null;
		log.debug("Slayer task transition: {} -> {}", taskEvent.getTaskName(), outcome);
	}

	/**
	 * This client's outcome claim, mirroring the backend decision table: streak
	 * advance (either counter) or a completion chat line means completed; the
	 * block list gaining the task means blocked; a points drop with neither is
	 * a skip. Point AMOUNTS never classify (Mortimer's skip costs the classic
	 * block price), the backend checks them against per-master costs.
	 *
	 * <p>Once a charge is ruled out, the SIGN still carries: a gain can only be
	 * an award, and an observed countdown to 0 with nothing charged can only be
	 * a last kill. Mortimer needs both. It keeps its own streak, which no varbit
	 * exposes, and its tasks pay only their offer modifier, so one offered
	 * without a modifier completes with no points movement at all.
	 */
	static String claimOutcome(
			int streakBefore, int streakAfter,
			int wildStreakBefore, int wildStreakAfter,
			int pointsBefore, int pointsAfter,
			boolean chatMatched, boolean blockListGained, boolean countedDownToZero) {
		if (streakAfter > streakBefore || wildStreakAfter > wildStreakBefore || chatMatched) {
			return "completed";
		}
		if (blockListGained) {
			return "blocked";
		}
		if (streakAfter == 0 && streakBefore > 0) {
			return "reset";
		}
		if (pointsAfter < pointsBefore) {
			return "skipped";
		}
		if (pointsAfter > pointsBefore || countedDownToZero) {
			return "completed";
		}
		return "unknown";
	}

	/**
	 * Resolves the task display name via the game-cache DB tables, handling the
	 * boss-task sentinel. Adapted from RuneLite SlayerPlugin.updateTask().
	 */
	private String resolveTaskName(int taskId, Integer bossTaskId) {
		try {
			int taskDbRow;
			if (taskId == BOSS_TASK_SENTINEL && bossTaskId != null) {
				List<Integer> bossRows = client.getDBRowsByValue(
						DBTableID.SlayerTaskSublist.ID,
						DBTableID.SlayerTaskSublist.COL_TASK_SUBTABLE_ID,
						0,
						bossTaskId);
				if (bossRows.isEmpty()) {
					return null;
				}
				taskDbRow = (Integer) client.getDBTableField(
						bossRows.get(0), DBTableID.SlayerTaskSublist.COL_TASK, 0)[0];
			} else {
				List<Integer> taskRows = client.getDBRowsByValue(
						DBTableID.SlayerTask.ID, DBTableID.SlayerTask.COL_ID, 0, taskId);
				if (taskRows.isEmpty()) {
					return null;
				}
				taskDbRow = taskRows.get(0);
			}
			return (String) client.getDBTableField(
					taskDbRow, DBTableID.SlayerTask.COL_NAME_UPPERCASE, 0)[0];
		} catch (RuntimeException e) {
			log.debug("Slayer task name resolution failed for task {}", taskId, e);
			return null;
		}
	}


	/** Reads the task-offer dialog if it is open. Client thread only. */
	private void scanForTaskOffers() {
		if (!config.syncSlayer() || !SyncGuard.hasAppKey(config)) {
			return;
		}
		if (GameModeUtil.isSpecialGameMode(client)) {
			return;
		}
		if (SyncGuard.getPlayerUsername(client) == null) {
			return;
		}
		Widget container = client.getWidget(TASK_OFFER_GROUP, TASK_OFFER_CHILD);
		if (container == null) {
			return;
		}
		List<OfferRow> rows = new ArrayList<>();
		collectRows(container, 0, rows);
		if (rows.isEmpty()) {
			return;
		}
		List<String> texts = readingOrder(rows);
		List<Map<String, Object>> offers = parseTaskOffers(texts);
		if (!offers.isEmpty()) {
			acceptOffers(offers, texts);
		}
	}

	/**
	 * Every text-bearing widget under the dialog, whichever child list holds
	 * it. The static, dynamic and nested lists are all walked because the
	 * modifier row is not in the same one as the name and amount rows.
	 */
	private static void collectRows(Widget widget, int depth, List<OfferRow> rows) {
		if (widget == null || depth > OFFER_SCAN_MAX_DEPTH) {
			return;
		}
		String text = widget.getText();
		if (text != null && !text.isEmpty()) {
			Point at = widget.getCanvasLocation();
			rows.add(at == null
					? new OfferRow(text, null, null)
					: new OfferRow(text, at.getX(), at.getY()));
		}
		for (Widget[] set : new Widget[][] {
				widget.getStaticChildren(), widget.getDynamicChildren(),
				widget.getNestedChildren()}) {
			if (set == null) {
				continue;
			}
			for (Widget child : set) {
				collectRows(child, depth + 1, rows);
			}
		}
	}

	/** One text row of the offer dialog, with where on screen it drew. */
	static final class OfferRow {
		final String text;
		final Integer x;
		final Integer y;

		OfferRow(String text, Integer x, Integer y) {
			this.text = text;
			this.x = x;
			this.y = y;
		}

		boolean positioned() {
			return x != null && y != null && y >= 0;
		}
	}

	/**
	 * The rows top to bottom as the player reads them. The dialog is a
	 * vertical list, so screen position restores the reading order even when
	 * the rows are gathered from different child lists, which walk order
	 * groups rather than interleaves. Positions are only trusted when every
	 * row has one; otherwise walk order stands.
	 */
	static List<String> readingOrder(List<OfferRow> rows) {
		List<OfferRow> ordered = new ArrayList<>(rows);
		boolean positioned = true;
		for (OfferRow row : rows) {
			positioned &= row.positioned();
		}
		if (positioned) {
			ordered.sort(Comparator.comparingInt((OfferRow row) -> row.y)
					.thenComparingInt(row -> row.x));
		}
		List<String> texts = new ArrayList<>(ordered.size());
		for (OfferRow row : ordered) {
			texts.add(row.text);
		}
		return texts;
	}

	/** Stores newly seen offers and ships them with the next state sync. */
	private void acceptOffers(List<Map<String, Object>> offers, List<String> rows) {
		String key = gson.toJson(offers);
		if (key.equals(lastOffersKey)) {
			return; // same dialog still open
		}
		if (isThinnerReread(offers)) {
			return;
		}
		lastOffersKey = key;
		pendingOffers = offers;
		// The rows only ride along when a modifier is missing: they are what
		// says where the missing row actually lives, without a release to ask.
		pendingOfferRows = modifierCount(offers) < offers.size() ? rows : null;
		offersCapturedMs = System.currentTimeMillis();
		offersCapturedAt = Instant.now().toString();
		log.debug("Captured {} slayer task offers, {} with a modifier",
				offers.size(), modifierCount(offers));
		readAndSync(true);
	}

	/**
	 * Whether this read is the dialog already captured, seen with fewer
	 * modifiers than last time. A row that populates late (or blanks as the
	 * dialog closes) makes the same dialog read differently on either side of
	 * it, and the fuller read is the true one.
	 */
	private boolean isThinnerReread(List<Map<String, Object>> offers) {
		if (pendingOffers.isEmpty()
				|| System.currentTimeMillis() - offersCapturedMs > OFFER_REFRESH_WINDOW_MS) {
			return false;
		}
		return offerNames(offers).equals(offerNames(pendingOffers))
				&& modifierCount(offers) < modifierCount(pendingOffers);
	}

	private static List<Object> offerNames(List<Map<String, Object>> offers) {
		List<Object> names = new ArrayList<>(offers.size());
		for (Map<String, Object> offer : offers) {
			names.add(offer.get("name"));
		}
		return names;
	}

	private static int modifierCount(List<Map<String, Object>> offers) {
		int count = 0;
		for (Map<String, Object> offer : offers) {
			if (offer.containsKey("modifier_text")) {
				count++;
			}
		}
		return count;
	}

	/** "Amount: 80 to 120", or a single "Amount: 48". */
	static final java.util.regex.Pattern AMOUNT_ROW = java.util.regex.Pattern.compile(
			"^Amount:\\s*(\\d+)(?:\\s*to\\s*(\\d+))?\\s*$", java.util.regex.Pattern.CASE_INSENSITIVE);
	/**
	 * "+15 Slayer points", "+30% Slayer XP", "+100 Assigned", "x2 clue
	 * scrolls", "-20 Assigned". Modifiers subtract as well as add, so the sign
	 * is part of the value: a "-20 Assigned" task asks for 20 fewer kills than
	 * the amount rolled, and reading it as +20 would have it ask for 40 more.
	 */
	static final java.util.regex.Pattern MODIFIER_ROW = java.util.regex.Pattern.compile(
			"^([+\\-x])?\\s*(\\d+)(%?)\\s+(.+?)\\s*$", java.util.regex.Pattern.CASE_INSENSITIVE);
	/** How many rows either side of an amount row still belong to that offer. */
	private static final int OFFER_BLOCK_SPAN = 6;

	private static String plain(List<String> texts, int index) {
		if (index < 0 || index >= texts.size() || texts.get(index) == null) {
			return "";
		}
		return net.runelite.client.util.Text.removeTags(texts.get(index)).trim();
	}

	/**
	 * Parses the offer dialog's rows, anchored on each "Amount: X to Y" row:
	 * the name is the row before it and the modifier the row after, with blank
	 * rows skipped on both sides. The rows are only consecutive once the
	 * dialog's untexted widgets are stepped over, which is why this searches
	 * rather than indexes.
	 *
	 * <p>The modifier row is kept verbatim as modifier_text even when it does
	 * not parse, so a wording this pattern misses still reaches the server.
	 * Package-private for tests.
	 */
	static List<Map<String, Object>> parseTaskOffers(List<String> texts) {
		List<Map<String, Object>> offers = new ArrayList<>();
		for (int i = 0; i < texts.size(); i++) {
			java.util.regex.Matcher amount = AMOUNT_ROW.matcher(plain(texts, i));
			if (!amount.matches()) {
				continue;
			}
			String name = nameBefore(texts, i);
			if (name == null) {
				continue;
			}
			Map<String, Object> offer = new LinkedHashMap<>();
			offer.put("name", name);
			int min = Integer.parseInt(amount.group(1));
			offer.put("amount_min", min);
			offer.put("amount_max",
					amount.group(2) == null ? min : Integer.parseInt(amount.group(2)));

			String modifier = modifierAfter(texts, i);
			if (modifier != null) {
				offer.put("modifier_text", modifier);
				java.util.regex.Matcher mod = MODIFIER_ROW.matcher(modifier);
				if (mod.matches()) {
					String sign = mod.group(1) == null ? "" : mod.group(1);
					int value = Integer.parseInt(mod.group(2));
					offer.put("modifier_value", "-".equals(sign) ? -value : value);
					offer.put("modifier_is_percent", !mod.group(3).isEmpty());
					offer.put("modifier_multiplies", "x".equalsIgnoreCase(sign));
					offer.put("modifier_label", mod.group(4));
				}
			}
			offers.add(offer);
		}
		return offers;
	}

	/** The task name an amount row belongs to: the row above it, blanks aside. */
	private static String nameBefore(List<String> texts, int amountIndex) {
		for (int i = amountIndex - 1;
				i >= 0 && i >= amountIndex - OFFER_BLOCK_SPAN; i--) {
			String row = plain(texts, i);
			if (row.isEmpty() || MODIFIER_ROW.matcher(row).matches()) {
				continue;
			}
			return AMOUNT_ROW.matcher(row).matches() ? null : row;
		}
		return null;
	}

	/**
	 * The modifier an amount row belongs to: the first row below it, blanks
	 * aside. The next offer's name sits in that same gap, so a row butting up
	 * against the next amount row is that name and not this offer's modifier.
	 */
	private static String modifierAfter(List<String> texts, int amountIndex) {
		List<String> block = new ArrayList<>();
		boolean nextOfferReached = false;
		for (int i = amountIndex + 1;
				i < texts.size() && i <= amountIndex + OFFER_BLOCK_SPAN; i++) {
			String row = plain(texts, i);
			if (row.isEmpty()) {
				continue;
			}
			if (AMOUNT_ROW.matcher(row).matches()) {
				nextOfferReached = true;
				break;
			}
			block.add(row);
		}
		if (nextOfferReached && !block.isEmpty()) {
			block.remove(block.size() - 1);
		}
		return block.isEmpty() ? null : block.get(0);
	}

	private SlayerSyncPayload buildPayload(String playerUsername, Snapshot s) {
		SlayerSyncPayload.State state = new SlayerSyncPayload.State(
				s.taskId,
				s.taskName,
				s.bossTaskId,
				s.areaId,
				s.masterId,
				s.amountRemaining,
				s.amountOriginal,
				s.points,
				s.streak,
				s.wildernessStreak,
				s.blockList,
				s.unlockBitfields,
				buildExtra(),
				s.slayerLevel,
				s.slayerXp,
				isoOrNull(firstKillMs),
				isoOrNull(lastKillMs),
				firstKillMs == 0 ? null : activeMs / 1000,
				Instant.now().toString());
		return new SlayerSyncPayload(playerUsername, state, new ArrayList<>(pendingEvents));
	}

	/** Assembles the state extra map: stored task + any recent task offers. */
	private Map<String, Object> buildExtra() {
		Map<String, Object> extra = storedTaskExtra(storedTask);
		if (!pendingOffers.isEmpty()
				&& System.currentTimeMillis() - offersCapturedMs <= OFFER_RETENTION_MS) {
			extra.put("task_offers", pendingOffers);
			extra.put("task_offers_captured_at", offersCapturedAt);
			if (pendingOfferRows != null) {
				extra.put("task_offer_rows", pendingOfferRows);
			}
		}
		return extra;
	}

	/**
	 * The extra-map entries for the captured Task Storage slot. Empty until a
	 * capture exists; zeros mean "capture confirmed the storage is empty",
	 * which the backend distinguishes from never-captured.
	 */
	static Map<String, Object> storedTaskExtra(StoredTaskCapture capture) {
		Map<String, Object> extra = new LinkedHashMap<>();
		if (capture == null) {
			return extra;
		}
		extra.put("stored_task_id", capture.taskId);
		extra.put("stored_task_amount", capture.amount);
		extra.put("stored_boss_task_id", capture.bossTaskId);
		if (capture.taskName != null) {
			extra.put("stored_task_name", capture.taskName);
		}
		extra.put("stored_task_captured_at", capture.capturedAt);
		return extra;
	}

	/** Reloads the last stored-task capture so restarts keep the known slot. */
	private void restoreStoredTask() {
		String stored = configManager.getRSProfileConfiguration("mystix", STORED_TASK_CONFIG_KEY);
		if (stored == null || stored.isEmpty()) {
			return;
		}
		try {
			StoredTaskCapture parsed = gson.fromJson(stored, StoredTaskCapture.class);
			if (parsed != null && parsed.capturedAt != null) {
				storedTask = parsed;
			}
		} catch (RuntimeException e) {
			log.debug("Discarding unparseable stored-task capture", e);
			configManager.unsetRSProfileConfiguration("mystix", STORED_TASK_CONFIG_KEY);
		}
	}

	/** Drops acknowledged events from the queue and re-persists the remainder. */
	private void onEventsAcknowledged(List<SlayerTaskEvent> sent) {
		clientThread.invokeLater(() -> {
			pendingEvents.removeIf(e -> sent.stream()
					.anyMatch(a -> a.getEventUuid().equals(e.getEventUuid())));
			persistPendingEvents();
		});
	}

	private void persistPendingEvents() {
		if (pendingEvents.isEmpty()) {
			configManager.unsetRSProfileConfiguration("mystix", PENDING_EVENTS_CONFIG_KEY);
		} else {
			configManager.setRSProfileConfiguration(
					"mystix", PENDING_EVENTS_CONFIG_KEY, gson.toJson(pendingEvents));
		}
	}

	/** Reloads events that were queued but never acknowledged (crash, failed POST). */
	private void restorePendingEvents() {
		String stored =
				configManager.getRSProfileConfiguration("mystix", PENDING_EVENTS_CONFIG_KEY);
		if (stored == null || stored.isEmpty()) {
			return;
		}
		try {
			List<SlayerTaskEvent> restored = gson.fromJson(
					stored, new TypeToken<List<SlayerTaskEvent>>() { }.getType());
			if (restored != null) {
				for (SlayerTaskEvent e : restored) {
					boolean alreadyQueued = pendingEvents.stream()
							.anyMatch(p -> p.getEventUuid().equals(e.getEventUuid()));
					if (!alreadyQueued) {
						pendingEvents.add(e);
					}
				}
			}
		} catch (RuntimeException e) {
			log.warn("Discarding unparseable pending slayer events", e);
			configManager.unsetRSProfileConfiguration("mystix", PENDING_EVENTS_CONFIG_KEY);
		}
	}
}
