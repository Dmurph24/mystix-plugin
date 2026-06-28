package com.mystix.model;

import com.google.gson.annotations.SerializedName;
import java.util.Collections;
import java.util.List;

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

	@SerializedName("dependency_ids")
	private List<Integer> dependencyIds;

	@SerializedName("meta")
	private Meta meta;

	public int getId() {
		return id;
	}

	/** Ids of this goal's prerequisite goals (within the same roadmap). */
	public List<Integer> getDependencyIds() {
		return dependencyIds == null ? Collections.emptyList() : dependencyIds;
	}

	/** OSRS item id for an icon, when this goal targets an item (else null). */
	public Integer getItemId() {
		return meta == null ? null : meta.itemId;
	}

	/** Catalog metadata block; only the fields the plugin needs are mapped. */
	private static class Meta {
		@SerializedName("item_id")
		private Integer itemId;
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
