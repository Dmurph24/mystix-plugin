package com.mystix.model;

import static org.junit.Assert.assertEquals;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.junit.Test;

public class HouseLocationSyncPayloadTest {
	@Test
	public void testPayloadCreation() {
		HouseLocationSyncPayload payload = new HouseLocationSyncPayload("TestPlayer", 7);
		assertEquals("TestPlayer", payload.getPlayerUsername());
		assertEquals(7, payload.getHouseLocationVarbit());
	}

	@Test
	public void testJsonUsesTheBackendFieldNames() {
		Gson gson = new Gson();
		String json = new HouseLocationSyncPayload("TestPlayer", 7).toJson(gson);
		JsonObject object = gson.fromJson(json, JsonObject.class);
		assertEquals("TestPlayer", object.get("player_username").getAsString());
		assertEquals(7, object.get("house_location_varbit").getAsInt());
		assertEquals(2, object.size());
	}
}
