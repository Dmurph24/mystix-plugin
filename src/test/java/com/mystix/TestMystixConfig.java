package com.mystix;

/**
 * Test double for MystixConfig used in unit tests.
 */
public class TestMystixConfig implements MystixConfig {
	private String mystixAppKey = "";
	private boolean syncTimeTracking = true;
	private boolean syncBankMemory = true;

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

	public void setMystixAppKey(String key) {
		this.mystixAppKey = key;
	}

	public void setSyncTimeTracking(boolean value) {
		this.syncTimeTracking = value;
	}

	public void setSyncBankMemory(boolean value) {
		this.syncBankMemory = value;
	}
}
