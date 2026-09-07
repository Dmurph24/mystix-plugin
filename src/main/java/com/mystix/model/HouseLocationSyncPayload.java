package com.mystix.model;

import com.google.gson.Gson;

/**
 * Payload for syncing the player-owned house location to the Mystix API.
 * Matches the format expected by POST /api/runelite/house-location/
 *
 * <p>Carries the raw {@code POH_HOUSE_LOCATION} varbit value; the backend owns
 * the value-to-town mapping so a new or corrected town never needs a plugin
 * release. Never sent for value 0 (no house), so a manual pick in the app is
 * never cleared by a fresh account or a pre-house login.
 */
public class HouseLocationSyncPayload {
	private final String player_username;
	private final int house_location_varbit;

	public HouseLocationSyncPayload(String playerUsername, int houseLocationVarbit) {
		this.player_username = playerUsername;
		this.house_location_varbit = houseLocationVarbit;
	}

	public String getPlayerUsername() {
		return player_username;
	}

	public int getHouseLocationVarbit() {
		return house_location_varbit;
	}

	public String toJson(Gson gson) {
		return gson.toJson(this);
	}
}
