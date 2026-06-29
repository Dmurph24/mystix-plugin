package com.mystix.model;

import com.google.gson.annotations.SerializedName;

/**
 * Lightweight roadmap entry from {@code GET /api/runelite/roadmaps/}, used to
 * populate the panel's roadmap selector.
 */
public class RoadmapSummary {
	@SerializedName("collection_id")
	private int collectionId;

	@SerializedName("title")
	private String title;

	@SerializedName("goal_count")
	private int goalCount;

	public RoadmapSummary() {
	}

	public RoadmapSummary(int collectionId, String title, int goalCount) {
		this.collectionId = collectionId;
		this.title = title;
		this.goalCount = goalCount;
	}

	public int getCollectionId() {
		return collectionId;
	}

	public String getTitle() {
		return title;
	}

	public int getGoalCount() {
		return goalCount;
	}

	@Override
	public String toString() {
		return title == null ? "Roadmap " + collectionId : title;
	}
}
