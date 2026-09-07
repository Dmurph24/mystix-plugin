package com.mystix;

import com.google.gson.Gson;
import com.mystix.model.DiaryTierResult;
import com.mystix.model.GoalType;
import com.mystix.model.LootSyncPayload;
import com.mystix.model.Roadmap;
import com.mystix.model.RoadmapGoal;
import com.mystix.model.TimerSyncItem;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class GoalProgressStateTest {
	private static final Gson GSON = new Gson();

	private RecordingSyncHooks hooks;
	private long now;
	private GoalProgressState state;

	@Before
	public void setUp() {
		hooks = new RecordingSyncHooks();
		now = 1_000_000L;
		state = new GoalProgressState(hooks, () -> now);
	}

	// ------------------------------------------------------------- helpers

	private static String goalJson(int id, String type, int current, int target, boolean complete, String meta) {
		Integer percent = null;
		if (Arrays.asList("skill_level", "skill_xp", "kc", "item_quantity", "item_owned", "net_worth", "farming_timer").contains(type)) {
			percent = target > 0 ? Math.min(100, current * 100 / target) : 0;
		}
		return "{\"id\":" + id + ",\"goal_type\":\"" + type + "\",\"sort_order\":" + id
				+ ",\"name\":\"Goal " + id + "\",\"current\":" + current + ",\"target\":" + target
				+ ",\"progress_percent\":" + percent + ",\"is_complete\":" + complete
				+ ",\"meta\":" + (meta == null ? "{}" : meta) + "}";
	}

	private static Roadmap roadmap(int collectionId, String... goalJsons) {
		return GSON.fromJson("{\"collection_id\":" + collectionId + ",\"title\":\"T\",\"goals\":["
				+ String.join(",", goalJsons) + "]}", Roadmap.class);
	}

	private static RoadmapGoal goal(Roadmap roadmap, int id) {
		for (RoadmapGoal g : roadmap.getGoals()) {
			if (g.getId() == id) {
				return g;
			}
		}
		throw new IllegalArgumentException("no goal " + id);
	}

	private static List<LootSyncPayload.LootItem> items(int... idQtyPairs) {
		List<LootSyncPayload.LootItem> list = new java.util.ArrayList<>();
		for (int i = 0; i < idQtyPairs.length; i += 2) {
			list.add(new LootSyncPayload.LootItem(idQtyPairs[i], idQtyPairs[i + 1]));
		}
		return list;
	}

	private static Map<String, Integer> xp(String skill, int xp) {
		Map<String, Integer> m = new HashMap<>();
		m.put(skill, xp);
		return m;
	}

	// ------------------------------------------------------------- skills

	@Test
	public void skillBaselineFromAcknowledgedUpload() {
		// Uploaded 1,000,000 xp; server says 200,000 gained toward 800,000 -> start was 800,000.
		state.onSkillsUploaded(xp("Slayer", 1_000_000));
		Roadmap r = roadmap(1, goalJson(1, "skill_xp", 200_000, 800_000, false, "{\"skill\":\"Slayer\"}"));
		state.onServerRoadmap(r);

		state.onSkillXp("Slayer", 1_050_000);
		GoalProgressView v = state.progressFor(goal(r, 1));
		assertEquals(250_000, v.getCurrent());
		assertEquals(800_000, v.getTarget());
		assertEquals(Integer.valueOf(31), v.getPercent());
		assertFalse(v.isComplete());
		assertEquals(GoalType.SKILL_XP, v.getType());
	}

	@Test
	public void skillCompletionFiresFastPathOnce() {
		state.onSkillsUploaded(xp("Slayer", 1_000_000));
		Roadmap r = roadmap(1, goalJson(1, "skill_xp", 200_000, 800_000, false, "{\"skill\":\"Slayer\"}"));
		state.onServerRoadmap(r);

		state.onSkillXp("Slayer", 1_600_000);
		GoalProgressView v = state.progressFor(goal(r, 1));
		assertTrue(v.isComplete());
		assertEquals(Integer.valueOf(100), v.getPercent());
		assertEquals(1, hooks.skillsSyncs);
		assertEquals(Collections.singletonList(GoalProgressState.RECONCILE_LAG_SECONDS), hooks.reconcileDelays);
		assertEquals(Collections.singletonList(1), hooks.completedGoalIds);

		// Further xp does nothing more.
		state.onSkillXp("Slayer", 1_700_000);
		assertEquals(1, hooks.skillsSyncs);
		assertEquals(1, hooks.reconcileDelays.size());
		assertEquals(1, hooks.completedGoalIds.size());
	}

	@Test
	public void skillBaselineFromLiveXpBeforeFirstUpload() {
		state.onSkillXp("Slayer", 1_000_000);
		Roadmap r = roadmap(1, goalJson(1, "skill_level", 200_000, 800_000, false, "{\"skill\":\"Slayer\"}"));
		state.onServerRoadmap(r);

		state.onSkillXp("Slayer", 1_010_000);
		assertEquals(210_000, state.progressFor(goal(r, 1)).getCurrent());

		// A later acknowledged upload re-derives the baseline exactly (here: same value).
		state.onSkillsUploaded(xp("Slayer", 1_010_000));
		state.onServerRoadmap(roadmap(1, goalJson(1, "skill_level", 210_000, 800_000, false, "{\"skill\":\"Slayer\"}")));
		state.onSkillXp("Slayer", 1_020_000);
		assertEquals(220_000, state.progressFor(goal(r, 1)).getCurrent());
	}

	@Test
	public void skillFallsBackToServerWhenNothingKnown() {
		Roadmap r = roadmap(1, goalJson(1, "skill_xp", 200_000, 800_000, false, "{\"skill\":\"Sailing\"}"));
		state.onServerRoadmap(r);
		GoalProgressView v = state.progressFor(goal(r, 1));
		assertEquals(200_000, v.getCurrent());
		assertEquals(Integer.valueOf(25), v.getPercent());
		assertFalse(v.isComplete());
	}

	@Test
	public void olderAbsoluteSkillGoalUsesZeroBaseline() {
		// No start_xp on the server: current == absolute xp == what we uploaded.
		state.onSkillsUploaded(xp("Slayer", 1_000_000));
		Roadmap r = roadmap(1, goalJson(1, "skill_level", 1_000_000, 1_986_068, false, "{\"skill\":\"Slayer\"}"));
		state.onServerRoadmap(r);
		state.onSkillXp("Slayer", 1_500_000);
		assertEquals(1_500_000, state.progressFor(goal(r, 1)).getCurrent());
	}

	@Test
	public void skillNeverShowsLessThanServer() {
		// Server ahead of our upload (synced from another computer): clamp to server.
		state.onSkillsUploaded(xp("Slayer", 900_000));
		Roadmap r = roadmap(1, goalJson(1, "skill_xp", 300_000, 800_000, false, "{\"skill\":\"Slayer\"}"));
		state.onServerRoadmap(r);
		state.onSkillXp("Slayer", 900_000);
		assertEquals(300_000, state.progressFor(goal(r, 1)).getCurrent());
	}

	// ------------------------------------------------------------- loot

	@Test
	public void killCountAddsMatchingKillsOnly() {
		Roadmap r = roadmap(1, goalJson(1, "kc", 5, 10, false, "{\"npc_id\":104}"));
		state.onServerRoadmap(r);
		state.onLootDrop(104, null, 1, items(526, 1));
		state.onLootDrop(999, null, 1, items(526, 1));
		assertEquals(6, state.progressFor(goal(r, 1)).getCurrent());
		assertEquals(Integer.valueOf(60), state.progressFor(goal(r, 1)).getPercent());
		assertEquals(0, hooks.lootFlushes);
	}

	@Test
	public void itemQuantitySumsMatchingItems() {
		Roadmap r = roadmap(1, goalJson(1, "item_quantity", 10, 50, false, "{\"item_id\":536}"));
		state.onServerRoadmap(r);
		state.onLootDrop(1, null, 1, items(536, 3, 526, 2, 536, 1));
		assertEquals(14, state.progressFor(goal(r, 1)).getCurrent());
	}

	@Test
	public void lootThresholdCompletesAndFlushes() {
		Roadmap r = roadmap(1, goalJson(1, "kc", 9, 10, false, "{\"npc_id\":104}"));
		state.onServerRoadmap(r);
		state.onLootDrop(104, null, 1, items(526, 1));
		GoalProgressView v = state.progressFor(goal(r, 1));
		assertTrue(v.isComplete());
		assertEquals(Integer.valueOf(100), v.getPercent());
		assertEquals(1, hooks.lootFlushes);
		assertEquals(Collections.singletonList(1), hooks.completedGoalIds);
		assertEquals(1, hooks.reconcileDelays.size());
	}

	@Test
	public void reconcileNeverGoesBackwardsAndDrains() {
		Roadmap r = roadmap(1, goalJson(1, "kc", 5, 20, false, "{\"npc_id\":104}"));
		state.onServerRoadmap(r);
		state.onLootDrop(104, null, 1, null);
		state.onLootDrop(104, null, 1, null);
		assertEquals(7, state.progressFor(goal(r, 1)).getCurrent());

		// Server caught up on one kill: still shows 7.
		state.onServerRoadmap(roadmap(1, goalJson(1, "kc", 6, 20, false, "{\"npc_id\":104}")));
		assertEquals(7, state.progressFor(goal(r, 1)).getCurrent());

		// Server ahead of us: shows the server value.
		state.onServerRoadmap(roadmap(1, goalJson(1, "kc", 9, 20, false, "{\"npc_id\":104}")));
		assertEquals(9, state.progressFor(goal(r, 1)).getCurrent());
	}

	@Test
	public void dropCompletesItemGoalAndClogCompletesToo() {
		Roadmap r = roadmap(1,
				goalJson(1, "npc_drop", 0, 0, false, "{\"item_id\":13576}"),
				goalJson(2, "clog_item", 0, 0, false, "{\"item_id\":6570}"));
		state.onServerRoadmap(r);

		state.onLootDrop(7, null, 1, items(13576, 1));
		assertTrue(state.progressFor(goal(r, 1)).isComplete());
		assertNull(state.progressFor(goal(r, 1)).getPercent());
		assertEquals(1, hooks.lootFlushes);

		state.onCollectionLogItemObtained(6570, 1);
		assertTrue(state.progressFor(goal(r, 2)).isComplete());
		assertEquals(Arrays.asList(1, 2), hooks.completedGoalIds);
		assertEquals(2, hooks.reconcileDelays.size());
	}

	@Test
	public void localCompletionIsStickyWhenServerLags() {
		Roadmap r = roadmap(1, goalJson(1, "kc", 9, 10, false, "{\"npc_id\":104}"));
		state.onServerRoadmap(r);
		state.onLootDrop(104, null, 1, null);
		assertTrue(state.progressFor(goal(r, 1)).isComplete());

		state.onServerRoadmap(roadmap(1, goalJson(1, "kc", 9, 10, false, "{\"npc_id\":104}")));
		assertTrue(state.progressFor(goal(r, 1)).isComplete());
		// Server later confirms: no second completion event.
		state.onServerRoadmap(roadmap(1, goalJson(1, "kc", 10, 10, true, "{\"npc_id\":104}")));
		assertTrue(state.progressFor(goal(r, 1)).isComplete());
		assertEquals(Collections.singletonList(1), hooks.completedGoalIds);
	}

	@Test
	public void fastPathIsRateLimited() {
		Roadmap r = roadmap(1,
				goalJson(1, "kc", 9, 10, false, "{\"npc_id\":104}"),
				goalJson(2, "kc", 9, 10, false, "{\"npc_id\":105}"));
		state.onServerRoadmap(r);
		state.onLootDrop(104, null, 1, null);
		now += 5_000;
		state.onLootDrop(105, null, 1, null);
		assertEquals(1, hooks.lootFlushes);
		assertEquals(2, hooks.reconcileDelays.size());
		now += GoalProgressState.FAST_PATH_MIN_INTERVAL_MS;
		state.onServerRoadmap(roadmap(1, goalJson(3, "kc", 9, 10, false, "{\"npc_id\":106}")));
		state.onLootDrop(106, null, 1, null);
		assertEquals(2, hooks.lootFlushes);
	}

	// ------------------------------------------------------------- lifecycle

	@Test
	public void serverConfirmedCompletionFiresOnceAndNotOnFirstLoad() {
		Roadmap first = roadmap(1,
				goalJson(1, "quest", 0, 0, true, null),
				goalJson(2, "quest", 0, 0, false, null));
		state.onServerRoadmap(first);
		assertTrue(hooks.completedGoalIds.isEmpty());

		state.onServerRoadmap(roadmap(1, goalJson(1, "quest", 0, 0, true, null), goalJson(2, "quest", 0, 0, true, null)));
		assertEquals(Collections.singletonList(2), hooks.completedGoalIds);
		state.onServerRoadmap(roadmap(1, goalJson(1, "quest", 0, 0, true, null), goalJson(2, "quest", 0, 0, true, null)));
		assertEquals(1, hooks.completedGoalIds.size());
	}

	@Test
	public void everyRoadmapIsTrackedAndCompletionsNameTheirRoadmap() {
		Roadmap first = roadmap(1, goalJson(1, "kc", 5, 20, false, "{\"npc_id\":104}"));
		Roadmap second = roadmap(2, goalJson(2, "kc", 9, 10, false, "{\"npc_id\":105}"));
		state.onServerRoadmap(first);
		state.onServerRoadmap(second);
		state.onLootDrop(104, null, 1, null);
		state.onLootDrop(105, null, 1, null);
		assertEquals(6, state.progressFor(goal(first, 1)).getCurrent());
		assertTrue(state.progressFor(goal(second, 2)).isComplete());
		assertEquals(Collections.singletonList(2), hooks.completedGoalIds);
		assertEquals(Collections.singletonList(2), hooks.completedCollectionIds);

		// A refresh of one roadmap leaves the other's local progress alone.
		state.onServerRoadmap(roadmap(2, goalJson(2, "kc", 10, 10, true, "{\"npc_id\":105}")));
		assertEquals(6, state.progressFor(goal(first, 1)).getCurrent());

		// Roadmaps the player no longer has are forgotten.
		state.retainRoadmaps(new java.util.HashSet<>(Collections.singletonList(2)));
		assertNull(state.getRoadmap(1));
		assertEquals(5, state.progressFor(goal(first, 1)).getCurrent());
	}

	@Test
	public void removedGoalsAreDroppedAndAddedGoalsStartClean() {
		Roadmap r = roadmap(1, goalJson(1, "kc", 5, 20, false, "{\"npc_id\":104}"));
		state.onServerRoadmap(r);
		state.onLootDrop(104, null, 1, null);
		Roadmap next = roadmap(1, goalJson(2, "kc", 0, 20, false, "{\"npc_id\":104}"));
		state.onServerRoadmap(next);
		assertEquals(0, state.progressFor(goal(next, 2)).getCurrent());
		// The removed goal is now unknown: server passthrough.
		assertEquals(5, state.progressFor(goal(r, 1)).getCurrent());
	}

	@Test
	public void logoutResetsLocalObservationsButKeepsServerValues() {
		state.onSkillsUploaded(xp("Slayer", 1_000_000));
		Roadmap r = roadmap(1,
				goalJson(1, "skill_xp", 200_000, 800_000, false, "{\"skill\":\"Slayer\"}"),
				goalJson(2, "kc", 5, 20, false, "{\"npc_id\":104}"));
		state.onServerRoadmap(r);
		state.onSkillXp("Slayer", 1_100_000);
		state.onLootDrop(104, null, 1, null);
		state.resetSession();
		assertEquals(200_000, state.progressFor(goal(r, 1)).getCurrent());
		assertEquals(5, state.progressFor(goal(r, 2)).getCurrent());
	}

	@Test
	public void sourceSyncedRequestsOneReconcileOnlyWithRoadmap() {
		state.onSourceSynced();
		assertTrue(hooks.reconcileDelays.isEmpty());
		state.onServerRoadmap(roadmap(1, goalJson(1, "quest", 0, 0, false, null)));
		state.onSourceSynced();
		assertEquals(Collections.singletonList(GoalProgressState.RECONCILE_LAG_SECONDS), hooks.reconcileDelays);
	}

	@Test
	public void unknownGoalPassesThroughServerValues() {
		RoadmapGoal g = GSON.fromJson(goalJson(9, "net_worth", 13, 100, false, null), RoadmapGoal.class);
		GoalProgressView v = state.progressFor(g);
		assertEquals(13, v.getCurrent());
		assertEquals(Integer.valueOf(13), v.getPercent());
		assertEquals(GoalType.NET_WORTH, v.getType());
	}

	@Test
	public void completedThisSessionTracksFirstObservedCompletion() {
		Roadmap r = roadmap(1,
				goalJson(1, "quest", 0, 0, true, null),
				goalJson(2, "kc", 9, 10, false, "{\"npc_id\":104}"),
				goalJson(3, "quest", 0, 0, false, null));
		state.onServerRoadmap(r);
		assertFalse(state.completedThisSession(1));

		state.onLootDrop(104, null, 1, null);
		assertTrue(state.completedThisSession(2));

		state.onServerRoadmap(roadmap(1,
				goalJson(1, "quest", 0, 0, true, null),
				goalJson(2, "kc", 10, 10, true, "{\"npc_id\":104}"),
				goalJson(3, "quest", 0, 0, true, null)));
		assertTrue(state.completedThisSession(3));
		assertTrue(state.completedThisSession(2));
		assertFalse(state.completedThisSession(1));

		state.resetSession();
		assertTrue(state.completedThisSession(2));

		state.retainRoadmaps(Collections.emptySet());
		assertFalse(state.completedThisSession(2));
		state.clearAll();
		assertFalse(state.completedThisSession(3));
	}

	@Test
	public void killCountMatchesNpcNameForIdVariants() {
		Roadmap r = roadmap(1, goalJson(1, "kc", 5, 10, false, "{\"npc_id\":3010}"));
		state.onServerRoadmap(r);
		state.onLootDrop(11917, "Guard", 1, null);
		assertEquals(5, state.progressFor(goal(r, 1)).getCurrent());

		state.setNpcName(1, "Guard");
		state.onLootDrop(11917, "guard", 1, null); // case differs: not the same NPC on the backend
		state.onLootDrop(11918, "<col=00ffff>Guard</col> ", 1, null); // tags stripped, trimmed
		state.onLootDrop(11919, "Guard", 1, null);
		state.onLootDrop(11920, "Knight", 1, null);
		assertEquals(7, state.progressFor(goal(r, 1)).getCurrent());

		// The name survives a server refresh of the same roadmap.
		state.onServerRoadmap(roadmap(1, goalJson(1, "kc", 5, 10, false, "{\"npc_id\":3010}")));
		state.onLootDrop(11921, "Guard", 1, null);
		assertEquals(8, state.progressFor(goal(r, 1)).getCurrent());
	}

	@Test
	public void killCountUsesServerCanonicalName() {
		Roadmap r = roadmap(1, goalJson(1, "kc", 3, 10, false, "{\"npc_id\":384,\"npc_name\":\"Guard\"}"));
		state.onServerRoadmap(r);
		state.onLootDrop(11916, "Guard", 1, null);
		state.onLootDrop(11917, "Guard", 1, null);
		assertEquals(5, state.progressFor(goal(r, 1)).getCurrent());
	}

	@Test
	public void questCompletesLocallyUsingBackendNameRules() {
		Roadmap r = roadmap(1,
				goalJson(1, "quest", 0, 0, false, "{\"quest_id\":5}"),
				goalJson(2, "quest", 0, 0, false, "{\"quest_id\":6}"));
		// Names come from the server: the wiki titles.
		r = GSON.fromJson(GSON.toJson(r).replace("Goal 1", "Dragon  Slayer II")
				.replace("Goal 2", "Recipe for Disaster/Freeing Evil Dave"), Roadmap.class);
		state.onServerRoadmap(r);

		Map<String, Integer> states = new HashMap<>();
		states.put("Dragon Slayer II", 1);
		states.put("Recipe for Disaster - Evil Dave", 2);
		state.onQuestStates(states);
		assertFalse(state.progressFor(goal(r, 1)).isComplete());
		assertTrue(state.progressFor(goal(r, 2)).isComplete());

		states.put("Dragon Slayer II", 2);
		state.onQuestStates(states);
		assertTrue(state.progressFor(goal(r, 1)).isComplete());
		assertEquals(Arrays.asList(2, 1), hooks.completedGoalIds);
		assertEquals(2, hooks.reconcileDelays.size());
	}

	@Test
	public void diaryTaskCompletesByRegionTierAndPosition() {
		Roadmap r = roadmap(1, goalJson(1, "diary_task", 0, 0, false,
				"{\"task_id\":77,\"diary\":\"Ardougne\",\"difficulty\":\"easy\",\"sequence\":2}"));
		state.onServerRoadmap(r);

		Map<String, Map<String, DiaryTierResult>> diaries = new HashMap<>();
		Map<String, DiaryTierResult> tiers = new HashMap<>();
		tiers.put("Easy", new DiaryTierResult(false, Arrays.asList(true, false, false)));
		diaries.put("Ardougne", tiers);
		state.onDiaryRead(diaries);
		assertFalse(state.progressFor(goal(r, 1)).isComplete());

		tiers.put("Easy", new DiaryTierResult(false, Arrays.asList(true, false, true)));
		state.onDiaryRead(diaries);
		assertTrue(state.progressFor(goal(r, 1)).isComplete());
		assertEquals(Collections.singletonList(1), hooks.completedGoalIds);
	}

	@Test
	public void combatTaskCompletesByInGameTaskId() {
		Roadmap r = roadmap(1, goalJson(1, "combat_achievement", 0, 0, false, "{\"task_id\":413,\"tier\":\"hard\"}"));
		state.onServerRoadmap(r);
		state.onCombatTasksCompleted(Arrays.asList(1, 2, 412));
		assertFalse(state.progressFor(goal(r, 1)).isComplete());
		state.onCombatTasksCompleted(Arrays.asList(1, 2, 412, 413));
		assertTrue(state.progressFor(goal(r, 1)).isComplete());
		assertEquals(Collections.singletonList(1), hooks.completedGoalIds);
	}

	@Test
	public void questNameNormalisationMatchesBackend() {
		assertEquals("dragon slayer ii", GoalProgressState.normaliseQuestName("Dragon_Slayer  II"));
		assertEquals("a & b", GoalProgressState.normaliseQuestName("A &amp; B#section"));
		assertEquals("cook's assistant", GoalProgressState.normaliseQuestName("Cook's Assistant|label"));
	}

	private static TimerSyncItem timer(int patchId, Integer itemId, String entity, String cropState,
			Instant started, Instant done) {
		return new TimerSyncItem("herb", "catherby", entity, done, true, "Tester", started, cropState, itemId, patchId);
	}

	private static String farmGoal(int id, int target, boolean complete) {
		return goalJson(id, "farming_timer", 0, target, complete,
				"{\"entity\":\"ranarr\",\"osrs_item_id\":207,\"timer_type\":\"herb\"}")
				.replace("\"meta\":", "\"created_at\":\"2026-09-01T00:00:00Z\",\"meta\":");
	}

	@Test
	public void singleFarmingGoalShowsLiveGrowThenCompletes() {
		now = Instant.parse("2026-09-02T00:00:00Z").toEpochMilli();
		Roadmap r = roadmap(1, farmGoal(1, 1, false));
		state.onServerRoadmap(r);

		Instant planted = Instant.parse("2026-09-01T12:00:00Z");
		Instant done = Instant.parse("2026-09-02T12:00:00Z");
		// Planted before the goal existed: ignored entirely.
		TimerSyncItem old = timer(9, 207, "ranarr", "growing", Instant.parse("2026-08-31T00:00:00Z"), planted);
		state.onFarmingTimers(Arrays.asList(old, timer(1, 207, "ranarr", "growing", planted, done)), now);
		GoalProgressView v = state.progressFor(goal(r, 1));
		assertFalse(v.isComplete());
		assertEquals(Integer.valueOf(50), v.getPercent());

		now = done.toEpochMilli() + 1000;
		state.checkFarming(now);
		assertTrue(state.progressFor(goal(r, 1)).isComplete());
		assertEquals(Collections.singletonList(1), hooks.completedGoalIds);
	}

	@Test
	public void countedFarmingGoalCountsEachPatchOnceAndMatchesByEntityWithoutItemId() {
		now = Instant.parse("2026-09-03T00:00:00Z").toEpochMilli();
		String json = goalJson(1, "farming_timer", 1, 3, false, "{\"entity\":\"Ranarr\",\"timer_type\":\"Herb\"}")
				.replace("\"meta\":", "\"created_at\":\"2026-09-01T00:00:00Z\",\"meta\":");
		Roadmap r = roadmap(1, json);
		state.onServerRoadmap(r);

		Instant planted = Instant.parse("2026-09-02T00:00:00Z");
		Instant done = Instant.parse("2026-09-02T02:00:00Z");
		List<TimerSyncItem> timers = Arrays.asList(
				timer(1, 207, "ranarr", "growing", planted, done),
				timer(2, 207, "ranarr", "growing", planted, done),
				timer(3, 208, "toadflax", "growing", planted, done),
				timer(4, 207, "ranarr", "dead", planted, done));
		state.onFarmingTimers(timers, now);
		state.onFarmingTimers(timers, now); // same completions again: not double counted
		GoalProgressView v = state.progressFor(goal(r, 1));
		assertEquals(3, v.getCurrent());
		assertTrue(v.isComplete());
	}

	private static Map<String, Map<Integer, Integer>> held(String source, int itemId, int qty) {
		Map<Integer, Integer> m = new HashMap<>();
		m.put(itemId, qty);
		Map<String, Map<Integer, Integer>> by = new HashMap<>();
		by.put(source, m);
		return by;
	}

	@Test
	public void ownedItemGoalTracksHoldingsAcrossSources() {
		// Started with 40 held; wants 100 more. Server has counted 0 so far.
		Roadmap r = roadmap(1, goalJson(1, "item_owned", 0, 100, false, "{\"item_id\":1511,\"start_qty\":40}"));
		state.onServerRoadmap(r);
		assertEquals(0, state.progressFor(goal(r, 1)).getCurrent());

		// Only the inventory is known so far: never show less than the server.
		state.onHeldQuantities(held("inventory", 1511, 20), false);
		assertEquals(0, state.progressFor(goal(r, 1)).getCurrent());

		// Bank upload: 30 banked + 20 in inventory = 50 held -> 10 gained.
		Map<String, Map<Integer, Integer>> upload = held("bank", 1511, 30);
		upload.putAll(held("inventory", 1511, 20));
		state.onHeldQuantities(upload, true);
		GoalProgressView v = state.progressFor(goal(r, 1));
		assertEquals(10, v.getCurrent());
		assertEquals(Integer.valueOf(10), v.getPercent());

		// Cut logs: inventory grows to 110 -> 140 held -> 100 gained -> complete.
		state.onHeldQuantities(held("inventory", 1511, 110), false);
		assertTrue(state.progressFor(goal(r, 1)).isComplete());
		assertEquals(1, hooks.bankSyncs);
		assertEquals(Collections.singletonList(1), hooks.completedGoalIds);
	}

	@Test
	public void ownedItemGoalFallsBackToServerWithoutHoldings() {
		Roadmap r = roadmap(1, goalJson(1, "item_owned", 25, 100, false, "{\"item_id\":1511,\"start_qty\":40}"));
		state.onServerRoadmap(r);
		GoalProgressView v = state.progressFor(goal(r, 1));
		assertEquals(25, v.getCurrent());
		assertEquals(Integer.valueOf(25), v.getPercent());
		assertEquals(GoalType.ITEM_OWNED, v.getType());
	}
}
