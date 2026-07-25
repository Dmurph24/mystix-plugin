package com.mystix.model;

/**
 * One slayer task transition (completed / skipped / blocked / reset / unknown)
 * detected client-side. Field names match the wire format of
 * POST /api/runelite/slayer/ events entries.
 *
 * <p>{@code outcome} is this client's claim; the backend re-derives the outcome
 * from the same signals and records disagreements, so the claim never needs to
 * be perfect, only honest. {@code event_uuid} makes replays idempotent.
 */
public class SlayerTaskEvent {
	private final String event_uuid;
	private final String outcome;
	private final Integer task_id;
	private final String task_name;
	private final Integer boss_task_id;
	private final Integer area_id;
	private final Integer master_id;
	private final int amount_original;
	private final int amount_remaining_at_transition;
	private final Integer streak_before;
	private final Integer streak_after;
	private final Integer points_before;
	private final Integer points_after;
	private final Boolean chat_matched;
	private final String chat_text;
	private final Boolean block_list_gained_task;
	private final String assigned_at;
	private final String ended_at;

	public SlayerTaskEvent(
			String eventUuid,
			String outcome,
			Integer taskId,
			String taskName,
			Integer bossTaskId,
			Integer areaId,
			Integer masterId,
			int amountOriginal,
			int amountRemainingAtTransition,
			Integer streakBefore,
			Integer streakAfter,
			Integer pointsBefore,
			Integer pointsAfter,
			Boolean chatMatched,
			String chatText,
			Boolean blockListGainedTask,
			String assignedAt,
			String endedAt) {
		this.event_uuid = eventUuid;
		this.outcome = outcome;
		this.task_id = taskId;
		this.task_name = taskName;
		this.boss_task_id = bossTaskId;
		this.area_id = areaId;
		this.master_id = masterId;
		this.amount_original = amountOriginal;
		this.amount_remaining_at_transition = amountRemainingAtTransition;
		this.streak_before = streakBefore;
		this.streak_after = streakAfter;
		this.points_before = pointsBefore;
		this.points_after = pointsAfter;
		this.chat_matched = chatMatched;
		this.chat_text = chatText;
		this.block_list_gained_task = blockListGainedTask;
		this.assigned_at = assignedAt;
		this.ended_at = endedAt;
	}

	public String getEventUuid() {
		return event_uuid;
	}

	public String getOutcome() {
		return outcome;
	}

	public String getTaskName() {
		return task_name;
	}
}
