package com.mystix.model;

import com.google.gson.annotations.SerializedName;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
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

	@SerializedName("image_url")
	private String imageUrl;

	@SerializedName("current")
	private int current;

	@SerializedName("target")
	private int target;

	@SerializedName("progress_percent")
	private Integer progressPercent;

	@SerializedName("is_complete")
	private boolean complete;

	@SerializedName("completed_at")
	private String completedAt;

	@SerializedName("created_at")
	private String createdAt;

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

	/** OSRS item id for an icon, when this goal targets an item (else null).
	 *
	 * <p>Item/drop/clog goals expose it as {@code item_id}; farming goals carry it
	 * as {@code osrs_item_id} in their params, so fall back to that. */
	public Integer getItemId() {
		if (meta == null) {
			return null;
		}
		return meta.itemId != null ? meta.itemId : meta.osrsItemId;
	}

	/** Skill name (e.g. "Slayer") for a skill goal, used to pick its icon; null
	 * otherwise. Only skill-level / skill-xp goals carry it. */
	public String getSkillName() {
		return meta == null ? null : meta.skill;
	}

	/** OSRS NPC id for a kill-count goal; null for every other type. */
	public Integer getNpcId() {
		return meta == null ? null : meta.npcId;
	}

	/** The canonical NPC name the server matches kills against for a
	 * kill-count goal (older servers omit it); null otherwise. */
	public String getNpcName() {
		return meta == null ? null : meta.npcName;
	}

	/** Mystix quest id for a quest goal; null otherwise. */
	public Integer getQuestId() {
		return meta == null ? null : meta.questId;
	}

	/** Diary task id (Mystix pk) or combat achievement in-game task id; null otherwise. */
	public Integer getTaskId() {
		return meta == null ? null : meta.taskId;
	}

	/** Diary region name for a diary task goal (e.g. "Ardougne"); null otherwise. */
	public String getDiary() {
		return meta == null ? null : meta.diary;
	}

	/** Diary tier ("easy", "medium", "hard", "elite") for a diary task goal; null otherwise. */
	public String getDifficulty() {
		return meta == null ? null : meta.difficulty;
	}

	/** Position of a diary task within its region/tier (older servers omit it). */
	public Integer getSequence() {
		return meta == null ? null : meta.sequence;
	}

	/** Catalog metadata block; only the fields the plugin needs are mapped. */
	private static class Meta {
		@SerializedName("item_id")
		private Integer itemId;

		@SerializedName("osrs_item_id")
		private Integer osrsItemId;

		@SerializedName("skill")
		private String skill;

		@SerializedName("npc_id")
		private Integer npcId;

		@SerializedName("npc_name")
		private String npcName;

		@SerializedName("quest_id")
		private Integer questId;

		@SerializedName("task_id")
		private Integer taskId;

		@SerializedName("diary")
		private String diary;

		@SerializedName("difficulty")
		private String difficulty;

		@SerializedName("sequence")
		private Integer sequence;

		@SerializedName("entity")
		private String entity;

		@SerializedName("timer_type")
		private String timerType;

		@SerializedName("start_qty")
		private Integer startQty;

		@SerializedName("held_bank")
		private Integer heldBank;

		@SerializedName("held_vaults")
		private Integer heldVaults;
	}

	public String getGoalType() {
		return goalType;
	}

	/** The parsed goal type; {@link GoalType#UNKNOWN} for unrecognised values. */
	public GoalType getType() {
		return GoalType.fromWire(goalType);
	}

	public int getSortOrder() {
		return sortOrder;
	}

	public String getName() {
		return name;
	}

	/** Artwork the server links for this goal (a wiki image), or null. */
	public String getImageUrl() {
		return imageUrl;
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
	 * When the server recorded this goal complete (ISO-8601 on the wire), or null
	 * when the goal is incomplete, the field is absent, or the value cannot be
	 * parsed. Never throws.
	 */
	public Instant getCompletedAt() {
		return parseInstant(completedAt);
	}

	/** When the goal was created (older servers omit it); null when unknown. */
	public Instant getCreatedAt() {
		return parseInstant(createdAt);
	}

	private static Instant parseInstant(String iso) {
		if (iso == null || iso.isEmpty()) {
			return null;
		}
		try {
			return OffsetDateTime.parse(iso).toInstant();
		} catch (DateTimeParseException e) {
			return null;
		}
	}

	/** Farming goals: the crop / produce name the goal was created with; null otherwise. */
	public String getEntity() {
		return meta == null ? null : meta.entity;
	}

	/** Farming goals: the timer type ("herb", "tree", ...) when the goal names one. */
	public String getTimerType() {
		return meta == null ? null : meta.timerType;
	}

	/** Owned-item goals: how many the player held when the goal was created. */
	public Integer getStartQty() {
		return meta == null ? null : meta.startQty;
	}

	/** Owned-item goals: quantity in the bank proper at the server's last upload. */
	public Integer getHeldBank() {
		return meta == null ? null : meta.heldBank;
	}

	/** Owned-item goals: quantity in vaults (seed vault, looting bag, potion storage). */
	public Integer getHeldVaults() {
		return meta == null ? null : meta.heldVaults;
	}

	/** Farming goals: the produce item id the goal matches timers by; null otherwise. */
	public Integer getOsrsItemId() {
		return meta == null ? null : meta.osrsItemId;
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
