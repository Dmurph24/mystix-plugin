package com.mystix.model;

import com.google.gson.Gson;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Tests parsing of the roadmap API responses and the next-goal / progress
 * helpers the panel and overlay rely on.
 */
public class RoadmapModelTest {
	private static final Gson GSON = new Gson();

	@Test
	public void testParseRoadmapList() {
		String json = "{\"player\":\"Zezima\",\"runelite_connected\":true,"
				+ "\"roadmaps\":[{\"collection_id\":1,\"title\":\"Maxing\",\"goal_count\":3},"
				+ "{\"collection_id\":2,\"title\":\"Iron grind\",\"goal_count\":0}]}";
		RoadmapList list = GSON.fromJson(json, RoadmapList.class);

		assertEquals("Zezima", list.getPlayer());
		assertTrue(list.isRuneliteConnected());
		assertEquals(2, list.getRoadmaps().size());
		assertEquals("Maxing", list.getRoadmaps().get(0).getTitle());
		assertEquals(3, list.getRoadmaps().get(0).getGoalCount());
	}

	@Test
	public void testRoadmapListEmptyRoadmapsIsNeverNull() {
		RoadmapList list = GSON.fromJson("{\"player\":\"Z\"}", RoadmapList.class);
		assertNotNull(list.getRoadmaps());
		assertTrue(list.getRoadmaps().isEmpty());
	}

	@Test
	public void testParseRoadmapDetailAndSorting() {
		String json = "{\"collection_id\":1,\"title\":\"Maxing\",\"goal_count\":2,"
				+ "\"goals\":["
				+ "{\"id\":11,\"goal_type\":\"skill_level\",\"sort_order\":2,\"name\":\"Level 99 Slayer\","
				+ "\"current\":50,\"target\":100,\"progress_percent\":63,\"is_complete\":false},"
				+ "{\"id\":10,\"goal_type\":\"quest\",\"sort_order\":1,\"name\":\"Cook's Assistant\","
				+ "\"current\":1,\"target\":1,\"progress_percent\":null,\"is_complete\":true}]}";
		Roadmap roadmap = GSON.fromJson(json, Roadmap.class);

		assertEquals(1, roadmap.getCollectionId());
		assertEquals(2, roadmap.getGoals().size());
		// Sorted by sort_order: the quest (1) before the skill (2).
		assertEquals(10, roadmap.getGoalsSorted().get(0).getId());
		assertEquals(11, roadmap.getGoalsSorted().get(1).getId());
	}

	@Test
	public void testFirstIncompleteGoalSkipsCompleted() {
		String json = "{\"collection_id\":1,\"goals\":["
				+ "{\"id\":1,\"sort_order\":1,\"name\":\"Done\",\"is_complete\":true},"
				+ "{\"id\":2,\"sort_order\":2,\"name\":\"Next up\",\"is_complete\":false},"
				+ "{\"id\":3,\"sort_order\":3,\"name\":\"Later\",\"is_complete\":false}]}";
		Roadmap roadmap = GSON.fromJson(json, Roadmap.class);

		RoadmapGoal next = roadmap.firstIncompleteGoal();
		assertNotNull(next);
		assertEquals("Next up", next.getName());
	}

	@Test
	public void testFirstIncompleteGoalNullWhenAllComplete() {
		String json = "{\"collection_id\":1,\"goals\":["
				+ "{\"id\":1,\"sort_order\":1,\"name\":\"Done\",\"is_complete\":true}]}";
		Roadmap roadmap = GSON.fromJson(json, Roadmap.class);
		assertNull(roadmap.firstIncompleteGoal());
	}

	@Test
	public void testProgressLabel() {
		RoadmapGoal measurable = GSON.fromJson(
				"{\"id\":1,\"progress_percent\":42,\"is_complete\":false}", RoadmapGoal.class);
		assertEquals("42%", measurable.progressLabel());

		RoadmapGoal binary = GSON.fromJson(
				"{\"id\":2,\"progress_percent\":null,\"is_complete\":false}", RoadmapGoal.class);
		assertEquals("", binary.progressLabel());
		assertFalse(binary.isComplete());
	}
}
