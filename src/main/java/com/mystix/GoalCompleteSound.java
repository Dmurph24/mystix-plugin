package com.mystix;

/** Sound played alongside the goal completion popup. */
public enum GoalCompleteSound {
	IN_GAME("In-game sound"),
	CUSTOM("Custom file"),
	OFF("Off");

	private final String label;

	GoalCompleteSound(String label) {
		this.label = label;
	}

	@Override
	public String toString() {
		return label;
	}
}
