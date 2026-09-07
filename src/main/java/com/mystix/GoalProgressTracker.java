package com.mystix;

import com.mystix.model.BankSyncPayload;
import com.mystix.model.DiaryTierResult;
import com.mystix.model.LootSyncPayload;
import com.mystix.model.Roadmap;
import com.mystix.model.RoadmapGoal;
import com.mystix.model.TimerSyncItem;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.InventoryID;
import net.runelite.api.Item;
import net.runelite.api.ItemComposition;
import net.runelite.api.ItemContainer;
import net.runelite.api.NPCComposition;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.StatChanged;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.ItemManager;

/**
 * Bridges game events and the monitors to {@link GoalProgressState}, and turns
 * the state's outbound requests into monitor calls. Registered on the event bus
 * and wired to the monitors' listeners by {@link MystixPlugin}.
 */
@Slf4j
@Singleton
public class GoalProgressTracker implements GoalProgressState.SyncHooks {
	private final Client client;
	private final ClientThread clientThread;
	private final ItemManager itemManager;
	private final PlayerSkillsMonitor playerSkillsMonitor;
	private final LootMonitor lootMonitor;
	private final BankMemoryMonitor bankMemoryMonitor;
	private final RoadmapManager roadmapManager;
	private final GoalCompletionNotifier completionNotifier;
	private final GoalProgressState state;

	/** Farming completions are time-based, so re-check every few ticks. */
	private static final int FARMING_CHECK_TICKS = 8;

	private GameState previousGameState = GameState.UNKNOWN;

	@Inject
	public GoalProgressTracker(
			Client client,
			ClientThread clientThread,
			ItemManager itemManager,
			PlayerSkillsMonitor playerSkillsMonitor,
			LootMonitor lootMonitor,
			BankMemoryMonitor bankMemoryMonitor,
			RoadmapManager roadmapManager,
			GoalCompletionNotifier completionNotifier) {
		this.client = client;
		this.clientThread = clientThread;
		this.itemManager = itemManager;
		this.playerSkillsMonitor = playerSkillsMonitor;
		this.lootMonitor = lootMonitor;
		this.bankMemoryMonitor = bankMemoryMonitor;
		this.roadmapManager = roadmapManager;
		this.completionNotifier = completionNotifier;
		this.state = new GoalProgressState(this);
	}

	// ---------------------------------------------------------- game events

	@Subscribe
	public void onStatChanged(StatChanged event) {
		state.onSkillXp(event.getSkill().getName(), event.getXp());
	}

	/** Inventory and equipment changes keep owned-item goals exact between bank
	 * visits: cutting or picking up raises them, dropping lowers them. */
	@Subscribe
	public void onItemContainerChanged(ItemContainerChanged event) {
		boolean equipment;
		if (event.getContainerId() == InventoryID.INVENTORY.getId()) {
			equipment = false;
		} else if (event.getContainerId() == InventoryID.EQUIPMENT.getId()) {
			equipment = true;
		} else {
			return;
		}
		ItemContainer container = event.getItemContainer();
		Map<Integer, Integer> quantities = new HashMap<>();
		if (container != null) {
			for (Item item : container.getItems()) {
				if (item == null || item.getId() <= 0 || item.getQuantity() <= 0) {
					continue;
				}
				quantities.merge(canonicalItemId(item.getId()), item.getQuantity(), Integer::sum);
			}
		}
		state.onInventoryChanged(equipment, quantities);
	}

	/** A bank upload was built: its bank section becomes this session's bank
	 * snapshot (ids folded onto the unnoted item). Inventory is tracked live. */
	public void onBankPayload(BankSyncPayload payload) {
		if (payload == null) {
			return;
		}
		List<BankSyncPayload.BankItem> bank = payload.getItems().get(BankMemoryMonitor.SOURCE_BANK);
		if (bank == null) {
			return;
		}
		Map<Integer, Integer> quantities = new HashMap<>();
		for (BankSyncPayload.BankItem item : bank) {
			quantities.merge(canonicalItemId(item.getItemId()), item.getQuantity(), Integer::sum);
		}
		state.onBankSnapshot(quantities);
	}

	/** Noted items map to their unnoted item id (client thread). */
	private int canonicalItemId(int itemId) {
		ItemComposition composition = itemManager.getItemComposition(itemId);
		if (composition != null && composition.getNote() != -1) {
			return composition.getLinkedNoteId();
		}
		return itemId;
	}

	@Subscribe
	public void onGameTick(GameTick event) {
		if (client.getTickCount() % FARMING_CHECK_TICKS == 0) {
			state.checkFarming(System.currentTimeMillis());
		}
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event) {
		GameState newState = event.getGameState();
		if (previousGameState == GameState.LOGGED_IN && newState != GameState.LOGGED_IN) {
			state.resetSession();
		}
		previousGameState = newState;
	}

	// ------------------------------------------------ monitor / manager feeds

	public void onServerRoadmap(Roadmap roadmap) {
		state.onServerRoadmap(roadmap);
		if (roadmap != null) {
			resolveNpcNames(roadmap);
		}
	}

	/** The player's current set of roadmaps; any others are forgotten. */
	public void retainRoadmaps(Set<Integer> collectionIds) {
		state.retainRoadmaps(collectionIds);
	}

	/**
	 * Kill-count goals carry one canonical NPC id, but many NPCs (guards, for
	 * example) have several in-game ids sharing a name. The server sends the
	 * canonical name; for older responses without it, resolve the id to a name
	 * on the client thread as a fallback (multi-form NPCs resolve to "null").
	 */
	private void resolveNpcNames(Roadmap roadmap) {
		clientThread.invokeLater(() -> {
			for (RoadmapGoal goal : roadmap.getGoals()) {
				Integer npcId = goal.getNpcId();
				if (npcId == null || goal.getNpcName() != null) {
					continue; // the server already told us the canonical name
				}
				NPCComposition composition = client.getNpcDefinition(npcId);
				String name = composition == null ? null : composition.getName();
				log.debug("Goal {} npc {} resolves to name '{}'", goal.getId(), npcId, name);
				if (name != null && !"null".equals(name)) {
					state.setNpcName(goal.getId(), name);
				}
			}
		});
	}

	public void onSkillsUploaded(Map<String, Integer> xpBySkillName) {
		state.onSkillsUploaded(xpBySkillName);
	}

	public void onLootDrop(int npcId, String npcName, int kills, List<LootSyncPayload.LootItem> items) {
		log.debug("Goal progress: loot drop npc {} '{}' x{}", npcId, npcName, kills);
		state.onLootDrop(npcId, npcName, kills, items);
	}

	public void onCollectionLogItemObtained(int itemId, int quantity) {
		state.onCollectionLogItemObtained(itemId, quantity);
	}

	public void onSourceSynced() {
		state.onSourceSynced();
	}

	public void onQuestStates(Map<String, Integer> statusByQuestName) {
		state.onQuestStates(statusByQuestName);
	}

	public void onDiaryRead(Map<String, Map<String, DiaryTierResult>> diaries) {
		state.onDiaryRead(diaries);
	}

	public void onCombatTasksCompleted(List<Integer> taskIds) {
		state.onCombatTasksCompleted(taskIds);
	}

	public void onFarmingTimers(List<TimerSyncItem> timers) {
		state.onFarmingTimers(timers, System.currentTimeMillis());
	}

	public void clear() {
		previousGameState = GameState.UNKNOWN;
		state.clearAll();
	}

	// ----------------------------------------------------------------- reads

	public GoalProgressView progressFor(RoadmapGoal goal) {
		return state.progressFor(goal);
	}

	public boolean isComplete(RoadmapGoal goal) {
		return state.isComplete(goal);
	}

	/** True when the goal's completion was first seen this session (it lingers in the tree). */
	public boolean completedThisSession(RoadmapGoal goal) {
		return goal != null && state.completedThisSession(goal.getId());
	}

	/** The first goal in sort order the plugin does not consider complete. */
	public RoadmapGoal firstIncompleteGoal(Roadmap roadmap) {
		if (roadmap == null) {
			return null;
		}
		for (RoadmapGoal goal : roadmap.getGoalsSorted()) {
			if (!isComplete(goal)) {
				return goal;
			}
		}
		return null;
	}

	// ------------------------------------------------------------- SyncHooks

	@Override
	public void forceSkillsSync() {
		playerSkillsMonitor.forceSync();
	}

	@Override
	public void flushLootDrops() {
		lootMonitor.flushDropsNow();
	}

	@Override
	public void forceBankSync() {
		// Inventory and gear (plus the bank when it was opened this session) so
		// the server can confirm without waiting for a bank visit.
		bankMemoryMonitor.syncInventoryNow();
	}

	@Override
	public void requestReconcile(int delaySeconds) {
		roadmapManager.requestReconcile(delaySeconds);
	}

	@Override
	public void goalCompleted(RoadmapGoal goal, Roadmap roadmap) {
		log.debug("Roadmap goal completed: {}", goal.getName());
		int total = 0;
		int done = 0;
		if (roadmap != null) {
			for (RoadmapGoal g : roadmap.getGoals()) {
				total++;
				// The just-completed goal is already marked complete in the state.
				if (isComplete(g)) {
					done++;
				}
			}
			// A completion in another roadmap makes that roadmap the active one.
			roadmapManager.selectRoadmap(roadmap);
		}
		completionNotifier.show(goal, roadmap == null ? null : roadmap.getTitle(), done, total);
		// Move the goal into the panel's Completed section right away.
		roadmapManager.notifyPanel();
	}
}
