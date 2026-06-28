package com.mystix.model;

import com.google.gson.annotations.SerializedName;

/**
 * A single goal within a roadmap, as returned by the roadmap detail/recompute
 * endpoints. Only the fields the plugin renders are mapped.
 */
public class RoadmapGoal {
	@SerializedName("id")
	private int id;

	@SerializedName("goal_type")
	private String goalType;

	@SerializedName("sort_order")
	private int sortOrder;

	@SerializedName("name")
	private String name;

	@SerializedName("current")
	private int current;

	@SerializedName("target")
	private int target;

	@SerializedName("progress_percent")
	private Integer progressPercent;

	@SerializedName("is_complete")
	private boolean complete;

	public int getId() {
		return id;
	}

	public String getGoalType() {
		return goalType;
	}

	public int getSortOrder() {
		return sortOrder;
	}

	public String getName() {
		return name;
	}

	public int getCurrent() {
		return current;
	}

	public int getTarget() {
		return target;
	}

	public Integer getProgressPercent() {
		return progressPercent;
	}

	public boolean isComplete() {
		return complete;
	}

	/**
	 * Human-readable progress suffix for measurable goals (e.g. "63%"), or empty
	 * when the goal is binary (no percent).
	 */
	public String progressLabel() {
		if (progressPercent == null) {
			return "";
		}
		return progressPercent + "%";
	}
}
