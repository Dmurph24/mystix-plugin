package com.mystix;

import com.mystix.model.GoalType;
import com.mystix.model.RoadmapGoal;

/**
 * A goal's progress as the plugin currently believes it: the server's numbers
 * plus whatever the client has observed locally since. Immutable snapshot built
 * per read by {@link GoalProgressState}.
 */
public final class GoalProgressView {
	private final GoalType type;
	/** Longs: net worth and owned-coins goals pass int range (OSRS lifted max cash). */
	private final long current;
	private final long target;
	/** 0-100, or null for binary goals that have no progress bar. */
	private final Integer percent;
	private final boolean complete;

	GoalProgressView(GoalType type, long current, long target, Integer percent, boolean complete) {
		this.type = type;
		this.current = current;
		this.target = target;
		this.percent = percent;
		this.complete = complete;
	}

	/** The server's view of a goal, untouched by local tracking. */
	public static GoalProgressView fromServer(RoadmapGoal goal) {
		Integer percent = goal.getProgressPercent();
		if (goal.isComplete() && percent != null) {
			percent = 100;
		}
		return new GoalProgressView(goal.getType(), goal.getCurrent(), goal.getTarget(), percent, goal.isComplete());
	}

	public GoalType getType() {
		return type;
	}

	public long getCurrent() {
		return current;
	}

	public long getTarget() {
		return target;
	}

	public Integer getPercent() {
		return percent;
	}

	public boolean isComplete() {
		return complete;
	}

	/** True when this goal is drawn with a progress bar. */
	public boolean isMeasurable() {
		return percent != null;
	}
}
