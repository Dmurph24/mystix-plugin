package com.mystix;

/** How the plugin tells the player that nothing is syncing to Mystix. */
public enum SyncWarningStyle {
	CHAT("Red chat message"),
	BANNER("Banner"),
	OFF("Off");

	private final String label;

	SyncWarningStyle(String label) {
		this.label = label;
	}

	@Override
	public String toString() {
		return label;
	}
}
