package com.mystix;

import com.mystix.model.Roadmap;
import com.mystix.model.RoadmapGoal;
import java.util.ArrayList;
import java.util.List;

/** Records every outbound action {@link GoalProgressState} requests. */
class RecordingSyncHooks implements GoalProgressState.SyncHooks {
	int skillsSyncs;
	int lootFlushes;
	int bankSyncs;
	final List<Integer> reconcileDelays = new ArrayList<>();
	final List<Integer> completedGoalIds = new ArrayList<>();
	final List<Integer> completedCollectionIds = new ArrayList<>();

	@Override
	public void forceSkillsSync() {
		skillsSyncs++;
	}

	@Override
	public void flushLootDrops() {
		lootFlushes++;
	}

	@Override
	public void forceBankSync() {
		bankSyncs++;
	}

	@Override
	public void requestReconcile(int delaySeconds) {
		reconcileDelays.add(delaySeconds);
	}

	@Override
	public void goalCompleted(RoadmapGoal goal, Roadmap roadmap) {
		completedGoalIds.add(goal.getId());
		completedCollectionIds.add(roadmap == null ? -1 : roadmap.getCollectionId());
	}
}
