package com.mystix;

import com.mystix.model.RoadmapGoal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Splits a roadmap's goals into the dependency tree the panel draws and the
 * flat "Completed" list beneath it.
 *
 * <p>The tree keeps only incomplete goals; a goal moves to the completed list
 * the moment the plugin considers it done. The completed list is sorted newest
 * first: goals completed this session (which have no server timestamp yet)
 * come first, then by the server's completion time, unknown times last. Pure,
 * so it is testable without Swing.
 */
final class GoalPartition {
	private final List<RoadmapGoal> tree;
	private final List<RoadmapGoal> completed;

	private GoalPartition(List<RoadmapGoal> tree, List<RoadmapGoal> completed) {
		this.tree = Collections.unmodifiableList(tree);
		this.completed = Collections.unmodifiableList(completed);
	}

	/**
	 * @param goals    goals in sort order
	 * @param complete whether the plugin considers a goal complete (server or local)
	 * @param session  whether the goal's completion was first seen this session
	 *                 (sorted newest in the completed list)
	 */
	static GoalPartition of(List<RoadmapGoal> goals,
			Predicate<RoadmapGoal> complete, Predicate<RoadmapGoal> session) {
		List<RoadmapGoal> tree = new ArrayList<>();
		List<RoadmapGoal> done = new ArrayList<>();
		Map<Integer, Long> recency = new HashMap<>();
		for (RoadmapGoal g : goals) {
			if (!complete.test(g)) {
				tree.add(g);
				continue;
			}
			done.add(g);
			recency.put(g.getId(), recencyKey(g, session.test(g)));
		}
		// Newest first; List.sort is stable so ties keep the roadmap's order.
		done.sort((a, b) -> Long.compare(recency.get(b.getId()), recency.get(a.getId())));
		return new GoalPartition(tree, done);
	}

	/** Session completions have no server timestamp yet: treat them as newest;
	 * a missing or unparseable timestamp sorts oldest. */
	private static long recencyKey(RoadmapGoal g, boolean thisSession) {
		if (thisSession) {
			return Long.MAX_VALUE;
		}
		Instant at = g.getCompletedAt();
		return at == null ? Long.MIN_VALUE : at.toEpochMilli();
	}

	/** Goals to draw as the dependency tree, in the given order. */
	List<RoadmapGoal> getTree() {
		return tree;
	}

	/** Finished goals for the collapsible list, newest completion first. */
	List<RoadmapGoal> getCompleted() {
		return completed;
	}
}
