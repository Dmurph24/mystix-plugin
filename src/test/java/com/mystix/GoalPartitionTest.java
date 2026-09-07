package com.mystix;

import com.google.gson.Gson;
import com.mystix.model.RoadmapGoal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class GoalPartitionTest {
	private static final Gson GSON = new Gson();

	private static RoadmapGoal goal(int id, boolean complete, String completedAt) {
		String at = completedAt == null ? "null" : "\"" + completedAt + "\"";
		return GSON.fromJson("{\"id\":" + id + ",\"sort_order\":" + id + ",\"name\":\"G" + id
				+ "\",\"is_complete\":" + complete + ",\"completed_at\":" + at + "}", RoadmapGoal.class);
	}

	private static List<Integer> ids(List<RoadmapGoal> goals) {
		List<Integer> out = new ArrayList<>();
		for (RoadmapGoal g : goals) {
			out.add(g.getId());
		}
		return out;
	}

	private static GoalPartition split(List<RoadmapGoal> goals, Integer... sessionIds) {
		Set<Integer> session = new HashSet<>(Arrays.asList(sessionIds));
		return GoalPartition.of(goals, RoadmapGoal::isComplete, g -> session.contains(g.getId()));
	}

	@Test
	public void allIncompleteStaysInTree() {
		List<RoadmapGoal> goals = Arrays.asList(goal(1, false, null), goal(2, false, null));
		GoalPartition p = split(goals);
		assertEquals(Arrays.asList(1, 2), ids(p.getTree()));
		assertTrue(p.getCompleted().isEmpty());
	}

	@Test
	public void completedGoalsLeaveTheTreeNewestFirst() {
		List<RoadmapGoal> goals = Arrays.asList(
				goal(1, true, "2026-09-01T10:00:00Z"),
				goal(2, false, null),
				goal(3, true, "2026-09-05T10:00:00+00:00"),
				goal(4, true, "2026-09-03T10:00:00Z"));
		GoalPartition p = split(goals);
		assertEquals(Collections.singletonList(2), ids(p.getTree()));
		assertEquals(Arrays.asList(3, 4, 1), ids(p.getCompleted()));
	}

	@Test
	public void sessionCompletedGoalsMoveToCompletedAsNewest() {
		List<RoadmapGoal> goals = Arrays.asList(
				goal(1, true, "2026-09-09T10:00:00Z"),
				goal(2, true, null),
				goal(3, false, null));
		GoalPartition p = split(goals, 2);
		assertEquals(Collections.singletonList(3), ids(p.getTree()));
		assertEquals(Arrays.asList(2, 1), ids(p.getCompleted()));
	}

	@Test
	public void unknownTimesSortLastAndTiesKeepOrder() {
		List<RoadmapGoal> goals = Arrays.asList(
				goal(1, true, null),
				goal(2, true, "2026-09-01T10:00:00Z"),
				goal(3, true, "garbage"),
				goal(4, true, "2026-09-01T10:00:00Z"));
		GoalPartition p = split(goals);
		assertEquals(Arrays.asList(2, 4, 1, 3), ids(p.getCompleted()));
	}

	@Test
	public void emptyInput() {
		GoalPartition p = split(Collections.emptyList());
		assertTrue(p.getTree().isEmpty());
		assertTrue(p.getCompleted().isEmpty());
	}
}
