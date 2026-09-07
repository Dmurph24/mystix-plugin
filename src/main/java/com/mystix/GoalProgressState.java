package com.mystix;

import com.mystix.model.DiaryTierResult;
import com.mystix.model.GoalType;
import com.mystix.model.LootSyncPayload;
import com.mystix.model.Roadmap;
import com.mystix.model.RoadmapGoal;
import com.mystix.model.TimerSyncItem;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;

/**
 * Pure, client-independent bookkeeping for local roadmap goal progress.
 *
 * <p>The backend only re-evaluates goals when data is uploaded and the roadmap is
 * re-read, so on its own the overlay lags behind the game. This class layers what
 * the client has observed since the last server read on top of the server's
 * numbers:
 *
 * <ul>
 *   <li><b>Skill goals</b>: the server reports XP gained since the goal was created
 *       and the gain still needed. The baseline (start XP) is derived as
 *       {@code uploadedXp - serverCurrent}, where {@code uploadedXp} is the XP the
 *       plugin last uploaded for that skill (or the live XP before the first upload
 *       of a session, which is exact because XP cannot change while logged out).
 *       Live progress is then {@code liveXp - baseline}.</li>
 *   <li><b>Kill count / item quantity goals</b>: matching loot events add to a local
 *       delta on top of the server value. When a fresh server value arrives the delta
 *       shrinks by however much the server caught up, so counts never go backwards.</li>
 *   <li><b>Item goals</b> (drop / collection log): a matching drop or collection log
 *       entry completes the goal locally.</li>
 * </ul>
 *
 * <p>Every one of the player's roadmaps is tracked, so a completion in any of
 * them is noticed. A goal the plugin decides is complete is treated as complete
 * for the rest of the session, even if the server has not caught up yet. The server is still nudged to confirm through {@link SyncHooks}.
 *
 * <p>Thread-safety: every public method synchronizes on this instance. Callers
 * are the client thread (game events), OkHttp threads (roadmap arrivals) and the
 * overlay render thread (reads). Hooks are invoked while the lock is held and
 * must therefore only schedule work, never block.
 *
 * <p>Holds no RuneLite references so it is unit-testable without a live client.
 */
@Slf4j
final class GoalProgressState {
	/** Outbound actions, wired by the tracker so this class stays pure. */
	interface SyncHooks {
		/** Re-upload skills now (a skill goal just completed locally). */
		void forceSkillsSync();

		/** Flush queued loot drops now (a loot goal just completed locally). */
		void flushLootDrops();

		/** Re-upload bank / inventory / equipment now (an owned-item goal just completed). */
		void forceBankSync();

		/** Re-read the roadmap from the server after a short lag. */
		void requestReconcile(int delaySeconds);

		/** First time this session the goal is known to be complete; the roadmap it belongs to. */
		void goalCompleted(RoadmapGoal goal, Roadmap roadmap);
	}

	/** Lag before re-reading the roadmap, so uploads have time to be processed. */
	static final int RECONCILE_LAG_SECONDS = 10;
	/** RuneLite colour tags around NPC names, as the backend strips them. */
	private static final Pattern COL_TAG = Pattern.compile("</?col[^>]*>");
	private static final Pattern WHITESPACE = Pattern.compile("\\s+");
	/** WikiSync status code for a finished quest. */
	private static final int QUEST_FINISHED = 2;

	/**
	 * RuneLite quest names the backend maps explicitly before normalising (the
	 * Recipe for Disaster subquests are titled differently on the wiki). Mirrors
	 * WIKISYNC_QUEST_ALIASES in the backend's quests processor.
	 */
	private static final Map<String, String> QUEST_ALIASES;

	static {
		Map<String, String> m = new HashMap<>();
		m.put("Recipe for Disaster - Another Cook's Quest", "Recipe for Disaster/Another Cook's Quest");
		m.put("Recipe for Disaster - Culinaromancer", "Recipe for Disaster/Defeating the Culinaromancer");
		m.put("Recipe for Disaster - Evil Dave", "Recipe for Disaster/Freeing Evil Dave");
		m.put("Recipe for Disaster - King Awowogei", "Recipe for Disaster/Freeing King Awowogei");
		m.put("Recipe for Disaster - Lumbridge Guide", "Recipe for Disaster/Freeing the Lumbridge Guide");
		m.put("Recipe for Disaster - Mountain Dwarf", "Recipe for Disaster/Freeing the Mountain Dwarf");
		m.put("Recipe for Disaster - Pirate Pete", "Recipe for Disaster/Freeing Pirate Pete");
		m.put("Recipe for Disaster - Sir Amik Varze", "Recipe for Disaster/Freeing Sir Amik Varze");
		m.put("Recipe for Disaster - Skrach Uglogwee", "Recipe for Disaster/Freeing Skrach Uglogwee");
		m.put("Recipe for Disaster - Wartface & Bentnoze", "Recipe for Disaster/Freeing the Goblin generals");
		QUEST_ALIASES = Collections.unmodifiableMap(m);
	}
	/** Minimum gap between forced uploads triggered by local completions. */
	static final long FAST_PATH_MIN_INTERVAL_MS = 30_000L;

	private enum Source {
		SKILLS,
		LOOT,
		BANK
	}

	/** Per-goal local state layered over the last server values. */
	private static final class LocalGoal {
		RoadmapGoal goal;
		int collectionId;
		GoalType type = GoalType.UNKNOWN;
		String skillKey;
		Integer npcId;
		/** In-game name of the goal's NPC, resolved by the tracker; matches id variants. */
		String npcName;
		Integer itemId;
		/** Quest goals: the normalised quest name the backend matches uploads by. */
		String questKey;
		/** Diary task goals: region / tier (lower case) and position within the tier. */
		String diaryKey;
		String diaryTier;
		Integer sequence;
		/** Combat achievement goals: the in-game task id. */
		Integer taskId;
		/** Owned-item goals: holdings when the goal was created (gain baseline),
		 * and the banked parts as the server last saw them. */
		Integer startQty;
		Integer serverHeldBank;
		int serverHeldVaults;
		/** Farming goals: when the goal was created (crops planted after count), and its matchers. */
		Instant createdAt;
		Integer farmItemId;
		String farmTimerType;
		String farmEntity;
		/** Farming goals: patch completions already counted ("patchId:doneEpoch"). */
		final Set<String> farmDone = new HashSet<>();
		int serverCurrent;
		int serverTarget;
		boolean serverComplete;
		/** Absolute start XP for skill goals; -1 while unknown. */
		long baselineXp = -1;
		/** True once the baseline came from an acknowledged upload (kept stable). */
		boolean baselineFromUpload;
		/** Kills / items observed locally that the server has not counted yet. */
		int localDelta;
		boolean locallyComplete;

		boolean isComplete() {
			return serverComplete || locallyComplete;
		}
	}

	private final SyncHooks hooks;
	private final LongSupplier clockMs;

	/** Every tracked roadmap by collection id (all of the player's roadmaps). */
	private final Map<Integer, Roadmap> roadmaps = new HashMap<>();
	private final Map<String, Integer> liveXp = new HashMap<>();
	private final Map<String, Integer> uploadedXp = new HashMap<>();
	private final Map<Integer, LocalGoal> goals = new HashMap<>();
	/** Live inventory and equipment quantities per canonical item id (from
	 * container changes), and the bank proper from this session's last bank
	 * upload. Owned-item goals add the server's banked parts to these. */
	private final Map<Integer, Integer> liveInventory = new HashMap<>();
	private final Map<Integer, Integer> liveEquipment = new HashMap<>();
	private Map<Integer, Integer> localBank;
	/** True once the client has reported its inventory this session. */
	private boolean inventorySeen;

	/** Latest farming / bird house timers the plugin computed (any timer type). */
	private List<TimerSyncItem> farmingTimers = Collections.emptyList();
	private final Set<Integer> completionNotified = new HashSet<>();
	private long lastFastPathAtMs = Long.MIN_VALUE;

	GoalProgressState(SyncHooks hooks) {
		this(hooks, System::currentTimeMillis);
	}

	GoalProgressState(SyncHooks hooks, LongSupplier clockMs) {
		this.hooks = hooks;
		this.clockMs = clockMs;
	}

	// ------------------------------------------------------------------ inputs

	/** A skill's XP as reported by the client (from {@code StatChanged}). */
	synchronized void onSkillXp(String skillName, int xp) {
		if (skillName == null) {
			return;
		}
		String key = key(skillName);
		liveXp.put(key, xp);

		List<RoadmapGoal> completed = new ArrayList<>();
		for (LocalGoal lg : goals.values()) {
			if (!lg.type.isSkill() || lg.isComplete() || !key.equals(lg.skillKey)) {
				continue;
			}
			if (skillReached(lg, xp)) {
				lg.locallyComplete = true;
				completed.add(lg.goal);
			}
		}
		if (!completed.isEmpty()) {
			notifyCompleted(completed);
			fastPath(Source.SKILLS);
		}
	}

	/** The XP snapshot the plugin just uploaded, keyed by skill name, once the
	 * server acknowledged it. Only affects baselines derived on later roadmap reads. */
	synchronized void onSkillsUploaded(Map<String, Integer> xpBySkillName) {
		if (xpBySkillName == null) {
			return;
		}
		for (Map.Entry<String, Integer> e : xpBySkillName.entrySet()) {
			if (e.getKey() != null && e.getValue() != null) {
				uploadedXp.put(key(e.getKey()), e.getValue());
			}
		}
	}

	/** The in-game name for a kill-count goal's NPC, so kills of any id variant
	 * of that NPC (guards, for example, have dozens) count locally. */
	synchronized void setNpcName(int goalId, String npcName) {
		LocalGoal lg = goals.get(goalId);
		if (lg != null && npcName != null && !npcName.isEmpty()) {
			lg.npcName = npcName;
		}
	}

	/** A loot drop the plugin has queued for upload. {@code kills} is the number of
	 * kills this event represents (normally 1); {@code items} carry canonical ids. */
	synchronized void onLootDrop(int npcId, String npcName, int kills, List<LootSyncPayload.LootItem> items) {
		List<RoadmapGoal> completed = new ArrayList<>();
		for (LocalGoal lg : goals.values()) {
			if (lg.isComplete()) {
				continue;
			}
			boolean nowComplete = false;
			if (lg.type == GoalType.KC && npcMatches(lg, npcId, npcName)) {
				lg.localDelta += Math.max(0, kills);
				log.debug("Goal {} kc +{} -> {}/{}", lg.goal.getId(), kills, lg.serverCurrent + lg.localDelta, lg.serverTarget);
				nowComplete = thresholdReached(lg);
			} else if (lg.type == GoalType.ITEM_QUANTITY && lg.itemId != null) {
				int qty = quantityOf(items, lg.itemId);
				if (qty > 0) {
					lg.localDelta += qty;
					nowComplete = thresholdReached(lg);
				}
			} else if (lg.type.isItemObtain() && lg.itemId != null) {
				nowComplete = quantityOf(items, lg.itemId) > 0;
			}
			if (nowComplete) {
				lg.locallyComplete = true;
				completed.add(lg.goal);
			}
		}
		if (!completed.isEmpty()) {
			notifyCompleted(completed);
			fastPath(Source.LOOT);
		}
	}

	/** The collection log reports an item as obtained (its monitor uploads on its own). */
	synchronized void onCollectionLogItemObtained(int itemId, int quantity) {
		if (quantity <= 0) {
			return;
		}
		List<RoadmapGoal> completed = new ArrayList<>();
		for (LocalGoal lg : goals.values()) {
			if (lg.isComplete() || !lg.type.isItemObtain() || lg.itemId == null || lg.itemId != itemId) {
				continue;
			}
			lg.locallyComplete = true;
			completed.add(lg.goal);
		}
		if (!completed.isEmpty()) {
			notifyCompleted(completed);
			hooks.requestReconcile(RECONCILE_LAG_SECONDS);
		}
	}

	/**
	 * The quest monitor read every quest's state (RuneLite quest name to WikiSync
	 * status 0/1/2). Quest goals whose quest is finished complete locally, using
	 * the backend's own name matching.
	 */
	synchronized void onQuestStates(Map<String, Integer> statusByQuestName) {
		if (statusByQuestName == null || statusByQuestName.isEmpty()) {
			return;
		}
		Set<String> finished = new HashSet<>();
		for (Map.Entry<String, Integer> e : statusByQuestName.entrySet()) {
			if (e.getValue() != null && e.getValue() == QUEST_FINISHED && e.getKey() != null) {
				finished.add(normaliseQuestName(QUEST_ALIASES.getOrDefault(e.getKey(), e.getKey())));
			}
		}
		List<RoadmapGoal> completed = new ArrayList<>();
		for (LocalGoal lg : goals.values()) {
			if (lg.type == GoalType.QUEST && !lg.isComplete() && lg.questKey != null && finished.contains(lg.questKey)) {
				lg.locallyComplete = true;
				completed.add(lg.goal);
			}
		}
		finishBinary(completed);
	}

	/**
	 * The diary monitor read every diary tier: region name to tier name to the
	 * positional task results. Diary task goals complete when their position in
	 * their region/tier reads true, the same (region, tier, sequence) key the
	 * backend correlates uploads by.
	 */
	synchronized void onDiaryRead(Map<String, Map<String, DiaryTierResult>> diaries) {
		if (diaries == null || diaries.isEmpty()) {
			return;
		}
		Map<String, Map<String, DiaryTierResult>> byRegion = new HashMap<>();
		for (Map.Entry<String, Map<String, DiaryTierResult>> region : diaries.entrySet()) {
			Map<String, DiaryTierResult> tiers = new HashMap<>();
			if (region.getValue() != null) {
				for (Map.Entry<String, DiaryTierResult> tier : region.getValue().entrySet()) {
					tiers.put(key(tier.getKey()), tier.getValue());
				}
			}
			byRegion.put(key(region.getKey()), tiers);
		}
		List<RoadmapGoal> completed = new ArrayList<>();
		for (LocalGoal lg : goals.values()) {
			if (lg.type != GoalType.DIARY_TASK || lg.isComplete()
					|| lg.diaryKey == null || lg.diaryTier == null || lg.sequence == null) {
				continue;
			}
			Map<String, DiaryTierResult> tiers = byRegion.get(lg.diaryKey);
			DiaryTierResult tier = tiers == null ? null : tiers.get(lg.diaryTier);
			List<Boolean> tasks = tier == null ? null : tier.getTasks();
			if (tasks != null && lg.sequence >= 0 && lg.sequence < tasks.size()
					&& Boolean.TRUE.equals(tasks.get(lg.sequence))) {
				lg.locallyComplete = true;
				completed.add(lg.goal);
			}
		}
		finishBinary(completed);
	}

	/** The combat achievement monitor read the completed in-game task ids. */
	synchronized void onCombatTasksCompleted(Collection<Integer> taskIds) {
		if (taskIds == null || taskIds.isEmpty()) {
			return;
		}
		Set<Integer> done = new HashSet<>(taskIds);
		List<RoadmapGoal> completed = new ArrayList<>();
		for (LocalGoal lg : goals.values()) {
			if (lg.type == GoalType.COMBAT_ACHIEVEMENT && !lg.isComplete()
					&& lg.taskId != null && done.contains(lg.taskId)) {
				lg.locallyComplete = true;
				completed.add(lg.goal);
			}
		}
		finishBinary(completed);
	}

	/**
	 * The client's live inventory or equipment (canonical item id to quantity).
	 * Owned-item goals move on every change: picking up or cutting items raises
	 * them, dropping or alching lowers them.
	 */
	synchronized void onInventoryChanged(boolean equipment, Map<Integer, Integer> quantities) {
		Map<Integer, Integer> target = equipment ? liveEquipment : liveInventory;
		target.clear();
		if (quantities != null) {
			target.putAll(quantities);
		}
		inventorySeen = true;
		checkOwned();
	}

	/** This session's bank upload: the bank proper, which supersedes the
	 * server's banked figure until the next server read catches up. */
	synchronized void onBankSnapshot(Map<Integer, Integer> bankQuantities) {
		localBank = bankQuantities == null ? new HashMap<>() : new HashMap<>(bankQuantities);
		checkOwned();
	}

	private void checkOwned() {
		List<RoadmapGoal> completed = new ArrayList<>();
		for (LocalGoal lg : goals.values()) {
			if (lg.type != GoalType.ITEM_OWNED || lg.isComplete() || lg.itemId == null) {
				continue;
			}
			if (ownedReached(lg)) {
				lg.locallyComplete = true;
				completed.add(lg.goal);
			}
		}
		if (!completed.isEmpty()) {
			notifyCompleted(completed);
			fastPath(Source.BANK);
		}
	}

	/** Held right now: banked parts (this session's bank snapshot when there is
	 * one, else the server's) plus live inventory and equipment. Null until
	 * the client has reported its inventory. */
	private Integer heldNow(LocalGoal lg) {
		if (!inventorySeen || lg.itemId == null || lg.serverHeldBank == null) {
			return null;
		}
		int bank = localBank != null ? localBank.getOrDefault(lg.itemId, 0) : lg.serverHeldBank;
		return bank + lg.serverHeldVaults
				+ liveInventory.getOrDefault(lg.itemId, 0)
				+ liveEquipment.getOrDefault(lg.itemId, 0);
	}

	private boolean ownedReached(LocalGoal lg) {
		Integer held = heldNow(lg);
		if (held == null || lg.serverTarget <= 0 || lg.startQty == null) {
			return false;
		}
		return held - lg.startQty >= lg.serverTarget;
	}

	/**
	 * The timer monitor recomputed every farming / bird house timer. Stored for
	 * grow-percent reads and checked for completions right away.
	 */
	synchronized void onFarmingTimers(List<TimerSyncItem> timers, long nowMs) {
		farmingTimers = timers == null ? Collections.emptyList() : new ArrayList<>(timers);
		checkFarming(nowMs);
	}

	/**
	 * Mirrors the backend's completion job: a growing patch whose estimated finish
	 * has elapsed counts as one completion, once, for goals whose crop matches and
	 * that were created before it was planted. "Grow N" goals count them; a single
	 * "Plant & grow" goal completes on the first.
	 */
	synchronized void checkFarming(long nowMs) {
		if (farmingTimers.isEmpty()) {
			return;
		}
		List<RoadmapGoal> completed = new ArrayList<>();
		for (LocalGoal lg : goals.values()) {
			if (lg.type != GoalType.FARMING_TIMER || lg.isComplete() || lg.createdAt == null) {
				continue;
			}
			boolean changed = false;
			for (TimerSyncItem timer : farmingTimers) {
				if (!farmingTimerMatches(lg, timer) || !plantedAfter(timer, lg.createdAt)) {
					continue;
				}
				if (!"growing".equals(timer.getCropState()) || timer.getCompletedAt() == null
						|| timer.getCompletedAt().toEpochMilli() > nowMs) {
					continue;
				}
				String key = timer.getPatchId() + ":" + timer.getCompletedAt().getEpochSecond();
				if (lg.farmDone.add(key)) {
					lg.localDelta++;
					changed = true;
				}
			}
			if (changed && (lg.serverTarget <= 1 || thresholdReached(lg))) {
				lg.locallyComplete = true;
				completed.add(lg.goal);
			}
		}
		finishBinary(completed);
	}

	/** Backend rule: match by produce item id when the goal has one, else by
	 * timer type and entity, both case-insensitively. */
	private static boolean farmingTimerMatches(LocalGoal lg, TimerSyncItem timer) {
		if (lg.farmItemId != null) {
			return timer.getOsrsItemId() != null && lg.farmItemId.equals(timer.getOsrsItemId());
		}
		if (lg.farmTimerType != null && !apiFormat(lg.farmTimerType).equals(apiFormat(timer.getTimerType()))) {
			return false;
		}
		return lg.farmEntity != null && apiFormat(lg.farmEntity).equals(apiFormat(timer.getEntity()));
	}

	private static boolean plantedAfter(TimerSyncItem timer, Instant createdAt) {
		return timer.getStartedAt() != null && timer.getStartedAt().isAfter(createdAt);
	}

	/** The wire form of timer strings: lower case, underscores to spaces, trimmed. */
	private static String apiFormat(String s) {
		return s == null ? "" : s.toLowerCase(Locale.ROOT).replace('_', ' ').trim();
	}

	/** Grow progress (0-100) of the matching crop closest to ready, planted after
	 * the goal was created; 0 when none. Mirrors farming_grow_percent. */
	private int farmingGrowPercent(LocalGoal lg, long nowMs) {
		int best = 0;
		for (TimerSyncItem timer : farmingTimers) {
			if (!farmingTimerMatches(lg, timer) || lg.createdAt == null || !plantedAfter(timer, lg.createdAt)
					|| timer.getCompletedAt() == null) {
				continue;
			}
			long total = timer.getCompletedAt().toEpochMilli() - timer.getStartedAt().toEpochMilli();
			int pct;
			if (total <= 0) {
				pct = 100;
			} else {
				long elapsed = nowMs - timer.getStartedAt().toEpochMilli();
				pct = (int) Math.max(0, Math.min(100, Math.round(elapsed * 100d / total)));
			}
			best = Math.max(best, pct);
		}
		return best;
	}

	/** Binary completions: the monitor uploads on its own, so only ask for a re-read. */
	private void finishBinary(List<RoadmapGoal> completed) {
		if (!completed.isEmpty()) {
			notifyCompleted(completed);
			hooks.requestReconcile(RECONCILE_LAG_SECONDS);
		}
	}

	/** Some monitor just uploaded data the server evaluates goals against. */
	synchronized void onSourceSynced() {
		if (!roadmaps.isEmpty()) {
			hooks.requestReconcile(RECONCILE_LAG_SECONDS);
		}
	}

	/**
	 * A freshly rendered roadmap arrived from the server (any of the player's
	 * roadmaps). Rebases its goals' server values, drains local deltas the server
	 * has caught up on, derives skill baselines, and reports goals the server
	 * newly confirmed complete. Other roadmaps' goals are untouched.
	 */
	synchronized void onServerRoadmap(Roadmap roadmap) {
		if (roadmap == null) {
			return;
		}
		int collectionId = roadmap.getCollectionId();
		roadmaps.put(collectionId, roadmap);

		Set<Integer> ids = new HashSet<>();
		List<RoadmapGoal> completed = new ArrayList<>();
		for (RoadmapGoal g : roadmap.getGoals()) {
			ids.add(g.getId());
			LocalGoal lg = goals.get(g.getId());
			boolean isNew = lg == null;
			if (isNew) {
				lg = new LocalGoal();
				goals.put(g.getId(), lg);
			}
			boolean wasServerComplete = lg.serverComplete;
			int oldDisplayed = lg.serverCurrent + lg.localDelta;

			lg.goal = g;
			lg.collectionId = collectionId;
			lg.type = g.getType();
			lg.skillKey = g.getSkillName() == null ? null : key(g.getSkillName());
			lg.npcId = g.getNpcId();
			if (g.getNpcName() != null && !g.getNpcName().isEmpty()) {
				lg.npcName = g.getNpcName(); // the server's canonical name wins
			}
			lg.itemId = g.getItemId();
			lg.questKey = lg.type == GoalType.QUEST && g.getName() != null ? normaliseQuestName(g.getName()) : null;
			lg.diaryKey = g.getDiary() == null ? null : key(g.getDiary());
			lg.diaryTier = g.getDifficulty() == null ? null : key(g.getDifficulty());
			lg.sequence = g.getSequence();
			lg.taskId = lg.type == GoalType.COMBAT_ACHIEVEMENT ? g.getTaskId() : null;
			lg.createdAt = g.getCreatedAt();
			lg.startQty = g.getStartQty();
			lg.serverHeldBank = g.getHeldBank();
			lg.serverHeldVaults = g.getHeldVaults() == null ? 0 : g.getHeldVaults();
			// A fresh server read includes any bank upload made before it.
			localBank = null;
			lg.farmItemId = lg.type == GoalType.FARMING_TIMER ? g.getOsrsItemId() : null;
			lg.farmTimerType = g.getTimerType();
			lg.farmEntity = g.getEntity();
			lg.serverTarget = g.getTarget();
			lg.serverComplete = g.isComplete();
			if (usesLocalDelta(lg)) {
				lg.localDelta = lg.serverComplete ? 0 : Math.max(0, oldDisplayed - g.getCurrent());
			} else {
				lg.localDelta = 0;
			}
			lg.serverCurrent = g.getCurrent();
			if (lg.type.isSkill()) {
				deriveBaseline(lg);
			}
			if (lg.serverComplete) {
				lg.locallyComplete = false;
				if (!isNew && !wasServerComplete) {
					completed.add(g);
				}
			}
		}
		// Goals removed from this roadmap drop out; other roadmaps keep theirs.
		goals.values().removeIf(lg -> lg.collectionId == collectionId && !ids.contains(lg.goal.getId()));
		completionNotified.retainAll(goals.keySet());
		if (!completed.isEmpty()) {
			notifyCompleted(completed);
		}
	}

	/** Forget roadmaps the player no longer has (the list came back without them). */
	synchronized void retainRoadmaps(Set<Integer> collectionIds) {
		if (collectionIds == null) {
			return;
		}
		roadmaps.keySet().retainAll(collectionIds);
		goals.values().removeIf(lg -> !collectionIds.contains(lg.collectionId));
		completionNotified.retainAll(goals.keySet());
	}

	/** The tracked roadmap with this collection id, or null. */
	synchronized Roadmap getRoadmap(int collectionId) {
		return roadmaps.get(collectionId);
	}

	/** Leaving the logged-in state: forget everything observed live this session
	 * but keep the server values so the overlay still has something to show. */
	synchronized void resetSession() {
		liveXp.clear();
		uploadedXp.clear();
		lastFastPathAtMs = Long.MIN_VALUE;
		farmingTimers = Collections.emptyList();
		liveInventory.clear();
		liveEquipment.clear();
		localBank = null;
		inventorySeen = false;
		for (LocalGoal lg : goals.values()) {
			lg.localDelta = 0;
			lg.locallyComplete = false;
			lg.baselineXp = -1;
			lg.baselineFromUpload = false;
			lg.farmDone.clear();
		}
	}

	synchronized void clearAll() {
		roadmaps.clear();
		goals.clear();
		completionNotified.clear();
		liveXp.clear();
		uploadedXp.clear();
		lastFastPathAtMs = Long.MIN_VALUE;
	}

	// ------------------------------------------------------------------- reads

	/** The plugin's current belief about a goal's progress. Never null. */
	synchronized GoalProgressView progressFor(RoadmapGoal goal) {
		LocalGoal lg = goal == null ? null : goals.get(goal.getId());
		if (lg == null) {
			return GoalProgressView.fromServer(goal);
		}
		GoalType type = lg.type;
		int target = lg.serverTarget;

		if (type.isSkill()) {
			Integer xp = lg.skillKey == null ? null : liveXp.get(lg.skillKey);
			if (xp == null || lg.baselineXp < 0) {
				return new GoalProgressView(type, lg.serverCurrent, target,
						lg.isComplete() ? Integer.valueOf(100) : goal.getProgressPercent(), lg.isComplete());
			}
			long gained = Math.max(0, xp - lg.baselineXp);
			int current = (int) Math.max(lg.serverCurrent, Math.min(Integer.MAX_VALUE, gained));
			boolean complete = lg.isComplete() || skillReached(lg, xp);
			return new GoalProgressView(type, current, target, complete ? 100 : percent(current, target), complete);
		}
		if (usesLocalDelta(lg)) {
			int current = lg.serverCurrent + lg.localDelta;
			boolean complete = lg.isComplete() || thresholdReached(lg);
			return new GoalProgressView(type, current, target, complete ? 100 : percent(current, target), complete);
		}
		if (type == GoalType.ITEM_OWNED) {
			Integer held = heldNow(lg);
			if (held == null || lg.startQty == null) {
				return new GoalProgressView(type, lg.serverCurrent, target,
						lg.isComplete() ? Integer.valueOf(100) : goal.getProgressPercent(), lg.isComplete());
			}
			// Exact and live: goes down again if the items are dropped or used.
			int current = Math.max(0, held - lg.startQty);
			boolean complete = lg.isComplete() || ownedReached(lg);
			return new GoalProgressView(type, current, target, complete ? 100 : percent(current, target), complete);
		}
		if (type == GoalType.FARMING_TIMER) {
			// Single "Plant & grow": the bar is the live grow percent of the crop.
			boolean complete = lg.isComplete();
			Integer serverPercent = goal.getProgressPercent();
			int live = farmingGrowPercent(lg, clockMs.getAsLong());
			int pct = complete ? 100 : Math.max(serverPercent == null ? 0 : serverPercent, live);
			return new GoalProgressView(type, complete ? 1 : 0, Math.max(1, target), pct, complete);
		}
		boolean complete = lg.isComplete();
		Integer percent = goal.getProgressPercent();
		if (complete && percent != null) {
			percent = 100;
		}
		return new GoalProgressView(type, lg.serverCurrent, target, percent, complete);
	}

	/** True when the plugin considers the goal complete (server or local). */
	synchronized boolean isComplete(RoadmapGoal goal) {
		return progressFor(goal).isComplete();
	}

	/** True when this goal's completion was first observed during this session
	 * (locally or by a server read), so the panel can keep it in the tree. */
	synchronized boolean completedThisSession(int goalId) {
		return completionNotified.contains(goalId);
	}

	// --------------------------------------------------------------- internals

	private void deriveBaseline(LocalGoal lg) {
		if (lg.baselineFromUpload || lg.skillKey == null) {
			return;
		}
		Integer uploaded = uploadedXp.get(lg.skillKey);
		if (uploaded != null) {
			lg.baselineXp = Math.max(0, (long) uploaded - lg.serverCurrent);
			lg.baselineFromUpload = true;
			return;
		}
		Integer live = liveXp.get(lg.skillKey);
		if (live != null) {
			lg.baselineXp = Math.max(0, (long) live - lg.serverCurrent);
		}
	}

	/**
	 * Mirrors the backend's NPC canonicalisation: a dropped NPC counts toward a
	 * goal when its id is the goal's canonical id, or its name (colour tags
	 * stripped, trimmed) exactly equals the canonical NPC's name.
	 */
	private static boolean npcMatches(LocalGoal lg, int npcId, String npcName) {
		if (lg.npcId != null && lg.npcId == npcId) {
			return true;
		}
		if (lg.npcName == null || npcName == null) {
			return false;
		}
		String dropped = sanitizeNpcName(npcName);
		return !dropped.isEmpty() && dropped.equals(sanitizeNpcName(lg.npcName));
	}

	/**
	 * Same as the backend's normalise_name for quests: unescape HTML, drop any
	 * link target / anchor suffix, underscores to spaces, collapse whitespace,
	 * trim, lower case.
	 */
	static String normaliseQuestName(String name) {
		if (name == null) {
			return "";
		}
		String target = name.replace("&amp;", "&").replace("&#39;", "'").replace("&quot;", "\"");
		int pipe = target.indexOf('|');
		if (pipe >= 0) {
			target = target.substring(0, pipe);
		}
		int hash = target.indexOf('#');
		if (hash >= 0) {
			target = target.substring(0, hash);
		}
		return WHITESPACE.matcher(target.replace('_', ' ')).replaceAll(" ").trim().toLowerCase(Locale.ROOT);
	}

	/** Same as the backend's sanitize_npc_name: strip RuneLite colour tags, trim. */
	static String sanitizeNpcName(String name) {
		return name == null ? "" : COL_TAG.matcher(name).replaceAll("").trim();
	}

	/** Goals whose progress is a count of local events layered on the server's count. */
	private static boolean usesLocalDelta(LocalGoal lg) {
		return lg.type.isLootCounted() || (lg.type == GoalType.FARMING_TIMER && lg.serverTarget > 1);
	}

	private static boolean skillReached(LocalGoal lg, int xp) {
		return lg.baselineXp >= 0 && lg.serverTarget > 0 && xp >= lg.baselineXp + lg.serverTarget;
	}

	private static boolean thresholdReached(LocalGoal lg) {
		return lg.serverTarget > 0 && lg.serverCurrent + lg.localDelta >= lg.serverTarget;
	}

	private static int quantityOf(List<LootSyncPayload.LootItem> items, int itemId) {
		if (items == null) {
			return 0;
		}
		int total = 0;
		for (LootSyncPayload.LootItem item : items) {
			if (item != null && item.getItemId() == itemId) {
				total += Math.max(0, item.getQuantity());
			}
		}
		return total;
	}

	private static Integer percent(int current, int target) {
		if (target <= 0) {
			return 0;
		}
		long pct = (long) current * 100L / target;
		return (int) Math.max(0, Math.min(100, pct));
	}

	private void notifyCompleted(List<RoadmapGoal> completed) {
		for (RoadmapGoal g : completed) {
			if (completionNotified.add(g.getId())) {
				LocalGoal lg = goals.get(g.getId());
				hooks.goalCompleted(g, lg == null ? null : roadmaps.get(lg.collectionId));
			}
		}
	}

	private void fastPath(Source source) {
		long now = clockMs.getAsLong();
		if (lastFastPathAtMs == Long.MIN_VALUE || now - lastFastPathAtMs >= FAST_PATH_MIN_INTERVAL_MS) {
			lastFastPathAtMs = now;
			if (source == Source.SKILLS) {
				hooks.forceSkillsSync();
			} else if (source == Source.BANK) {
				hooks.forceBankSync();
			} else {
				hooks.flushLootDrops();
			}
		}
		hooks.requestReconcile(RECONCILE_LAG_SECONDS);
	}

	private static String key(String skillName) {
		return skillName.trim().toLowerCase(Locale.ROOT);
	}
}
