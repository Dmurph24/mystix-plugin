package com.mystix;

import net.runelite.api.Client;
import net.runelite.api.Player;

/**
 * Shared pre-sync validation checks used by all monitors.
 */
public final class SyncGuard {

	private SyncGuard() {
	}

	public static boolean hasAppKey(MystixConfig config) {
		String key = config.mystixAppKey();
		return key != null && !key.isBlank();
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
