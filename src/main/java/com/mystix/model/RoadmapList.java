package com.mystix.model;

import com.google.gson.annotations.SerializedName;
import java.util.Collections;
import java.util.List;

/**
 * Response wrapper for {@code GET /api/runelite/roadmaps/}.
 */
public class RoadmapList {
	@SerializedName("player")
	private String player;

	@SerializedName("runelite_connected")
	private boolean runeliteConnected;

	@SerializedName("roadmaps")
	private List<RoadmapSummary> roadmaps;

	public String getPlayer() {
		return player;
	}

	public boolean isRuneliteConnected() {
		return runeliteConnected;
	}

	public List<RoadmapSummary> getRoadmaps() {
		return roadmaps == null ? Collections.emptyList() : roadmaps;
	}
}
