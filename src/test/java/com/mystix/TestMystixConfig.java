package com.mystix;

/**
 * Test double for MystixConfig used in unit tests.
 */
public class TestMystixConfig implements MystixConfig {
	private String mystixAppKey = "";
	private boolean syncTimeTracking = true;

	@Override
	public String mystixAppKey() {
		return mystixAppKey;
	}

	@Override
	public boolean syncTimeTracking() {
		return syncTimeTracking;
	}

	public void setMystixAppKey(String key) {
		this.mystixAppKey = key;
	}

	public void setSyncTimeTracking(boolean value) {
		this.syncTimeTracking = value;
	}
}
