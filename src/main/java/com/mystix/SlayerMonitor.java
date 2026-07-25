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
import net.runelite.api.Skill;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.VarbitChanged;
import net.runelite.api.gameval.DBTableID;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;

/**
 * Monitors slayer state (assignment, points, streaks, block list, unlocks) and
 * task transitions, and syncs them to the Mystix API.
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
	/** SLAYER_TARGET value meaning "a boss task"; resolve via the sublist table. */
	private static final int BOSS_TASK_SENTINEL = 98;
	private static final String PENDING_EVENTS_CONFIG_KEY = "pendingSlayerEvents";

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

		/** Task identity, ignoring progress. Boss tasks differ by sublist id. */
		boolean sameTask(Snapshot other) {
			return java.util.Objects.equals(taskId, other.taskId)
					&& java.util.Objects.equals(bossTaskId, other.bossTaskId)
					&& java.util.Objects.equals(areaId, other.areaId);
		}
	}

	private final Client client;
	private final ClientThread clientThread;
	private final MystixConfig config;
	private final MystixApiClient apiClient;
	private final ScheduledExecutorService executorService;
	private final Gson gson;
	private final ConfigManager configManager;

	private GameState previousGameState = GameState.UNKNOWN;
	private boolean checkPending;
	private int lastReadTick = -1;
	private String lastSyncJson;
	private long lastAmountSyncMs;
	private Snapshot previous;
	private String currentTaskAssignedAt;
	private String lastCompletionChatText;
	private int lastCompletionChatTick = -1;
	private final List<SlayerTaskEvent> pendingEvents = new ArrayList<>();

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
	public void onVarbitChanged(VarbitChanged event) {
		if (WATCHED_VARPS.contains(event.getVarpId())
				|| WATCHED_VARBITS.contains(event.getVarbitId())) {
			checkPending = true;
		}
	}

	@Subscribe
	public void onGameTick(GameTick event) {
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

		Snapshot now = readSnapshot();
		detectTransition(previous, now);
		boolean taskChanged = previous == null
				|| previous.hasTask() != now.hasTask()
				|| (now.hasTask() && !now.sameTask(previous));
		if (taskChanged && now.hasTask()) {
			currentTaskAssignedAt = Instant.now().toString();
		}
		previous = now;

		boolean amountWindowOpen =
				System.currentTimeMillis() - lastAmountSyncMs >= AMOUNT_SYNC_MIN_INTERVAL_MS;
		if (!force && !taskChanged && pendingEvents.isEmpty() && !amountWindowOpen) {
			return;
		}

		SlayerSyncPayload payload = buildPayload(playerUsername, now);
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

	/** Detects a task ending/changing between snapshots and queues an event. */
	private void detectTransition(Snapshot before, Snapshot now) {
		if (before == null || !before.hasTask()) {
			return;
		}
		boolean ended = !now.hasTask() || !now.sameTask(before);
		if (!ended) {
			return;
		}

		boolean chatRecent = lastCompletionChatTick != -1
				&& client.getTickCount() - lastCompletionChatTick <= CHAT_ATTACH_WINDOW_TICKS;
		boolean blockListGained = before.taskId != null
				&& !before.blockList.contains(before.taskId)
				&& now.blockList.contains(before.taskId);

		String outcome = claimOutcome(
				before.streak, now.streak,
				before.wildernessStreak, now.wildernessStreak,
				before.points, now.points,
				chatRecent, blockListGained);

		SlayerTaskEvent taskEvent = new SlayerTaskEvent(
				UUID.randomUUID().toString(),
				outcome,
				before.taskId,
				before.taskName,
				before.bossTaskId,
				before.areaId,
				before.masterId,
				before.amountOriginal,
				before.amountRemaining,
				before.streak,
				now.streak,
				before.points,
				now.points,
				chatRecent,
				chatRecent ? lastCompletionChatText : null,
				blockListGained,
				currentTaskAssignedAt,
				Instant.now().toString());
		pendingEvents.add(taskEvent);
		persistPendingEvents();
		lastCompletionChatTick = -1;
		lastCompletionChatText = null;
		currentTaskAssignedAt = null;
		log.debug("Slayer task transition: {} -> {}", taskEvent.getTaskName(), outcome);
	}

	/**
	 * This client's outcome claim, mirroring the backend decision table: streak
	 * advance (either counter) or a completion chat line means completed; the
	 * block list gaining the task means blocked; a points drop with neither is
	 * a skip. Point AMOUNTS never classify (Mortimer's skip costs the classic
	 * block price), the backend checks them against per-master costs.
	 */
	static String claimOutcome(
			int streakBefore, int streakAfter,
			int wildStreakBefore, int wildStreakAfter,
			int pointsBefore, int pointsAfter,
			boolean chatMatched, boolean blockListGained) {
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
				new LinkedHashMap<>(),
				s.slayerLevel,
				s.slayerXp,
				Instant.now().toString());
		return new SlayerSyncPayload(playerUsername, state, new ArrayList<>(pendingEvents));
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
