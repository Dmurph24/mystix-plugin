package com.mystix;

/**
 * Test double for MystixConfig used in unit tests.
 */
public class TestMystixConfig implements MystixConfig {
	private String mystixAppKey = "";
	private boolean syncTimeTracking = true;
	private boolean syncBankMemory = true;
	private boolean showNextGoal = false;

	@Override
	public String mystixAppKey() {
		return mystixAppKey;
	}

	@Override
	public boolean syncTimeTracking() {
		return syncTimeTracking;
	}

	@Override
	public boolean syncBankMemory() {
		return syncBankMemory;
	}

	@Override
	public boolean showNextGoal() {
		return showNextGoal;
	}

	public void setMystixAppKey(String key) {
		this.mystixAppKey = key;
	}

	public void setSyncTimeTracking(boolean value) {
		this.syncTimeTracking = value;
	}

	public void setSyncBankMemory(boolean value) {
		this.syncBankMemory = value;
	}

	public void setShowNextGoal(boolean value) {
		this.showNextGoal = value;
	}
}
