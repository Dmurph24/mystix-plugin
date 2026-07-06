package com.mystix;

import net.runelite.api.Client;
import net.runelite.api.Player;

/**
 * Shared pre-sync validation checks used by all monitors.
 */
public final class SyncGuard {

	/**
	 * Minimum length a value must reach to be treated as a complete app key.
	 * Keys are currently generated server-side as a 16-character string (see the
	 * backend's UserProfile.generate_runelite_key); this floor stays valid if that
	 * length ever changes, while still rejecting obviously-partial input.
	 */
	public static final int APP_KEY_MIN_LENGTH = 8;

	private SyncGuard() {
	}

	public static boolean hasAppKey(MystixConfig config) {
		String key = config.mystixAppKey();
		return key != null && !key.isBlank();
	}

	/**
	 * Returns true when a ConfigChanged event represents a complete Mystix app key
	 * being entered: the Mystix config group, the app-key item, and a value at least
	 * APP_KEY_MIN_LENGTH characters long. The length floor rejects obviously-partial
	 * input and key clears while tolerating a future change to the key length.
	 */
	public static boolean isCompleteAppKeyEntry(String group, String key, String newValue) {
		if (!MystixConfig.CONFIG_GROUP.equals(group) || !MystixConfig.APP_KEY.equals(key)) {
			return false;
		}
		return newValue != null && newValue.trim().length() >= APP_KEY_MIN_LENGTH;
	}

	/**
	 * Returns the local player's username, or null if unavailable.
	 */
	public static String getPlayerUsername(Client client) {
		Player localPlayer = client.getLocalPlayer();
		if (localPlayer == null) {
			return null;
		}
		String name = localPlayer.getName();
		if (name == null || name.isBlank()) {
			return null;
		}
		return name;
	}
}
