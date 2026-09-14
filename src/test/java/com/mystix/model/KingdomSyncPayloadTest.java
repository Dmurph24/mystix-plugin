package com.mystix.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.util.Collections;
import org.junit.Test;

public class KingdomSyncPayloadTest {
	private static KingdomSyncPayload samplePayload() {
		return new KingdomSyncPayload(
				"TestPlayer", true, true, false,
				120, 2_500_000,
				10, 5, 0, 0, 0, 0,
				0, 1, 0,
				Collections.singletonMap("restotal_lowbits", 4321));
	}

	@Test
	public void testPayloadCreation() {
		KingdomSyncPayload payload = samplePayload();
		assertEquals("TestPlayer", payload.getPlayerUsername());
		assertTrue(payload.isInKingdom());
		assertTrue(payload.isThroneCompleted());
		assertFalse(payload.isRoyalTroubleCompleted());
		assertEquals(120, payload.getApprovalPoints());
		assertEquals(2_500_000, payload.getCoffer());
		assertEquals(10, payload.getWorkersWood());
		assertEquals(5, payload.getWorkersHerb());
		assertEquals(1, payload.getRarewoodType());
		assertEquals(Integer.valueOf(4321), payload.getExtra().get("restotal_lowbits"));
	}

	@Test
	public void testJsonUsesTheBackendFieldNames() {
		Gson gson = new Gson();
		JsonObject object = gson.fromJson(samplePayload().toJson(gson), JsonObject.class);
		assertEquals("TestPlayer", object.get("player_username").getAsString());
		assertTrue(object.get("in_kingdom").getAsBoolean());
		assertTrue(object.get("throne_completed").getAsBoolean());
		assertFalse(object.get("royal_trouble_completed").getAsBoolean());
		assertEquals(120, object.get("approval_points").getAsInt());
		assertEquals(2_500_000, object.get("coffer").getAsInt());
		assertEquals(10, object.get("workers_wood").getAsInt());
		assertEquals(5, object.get("workers_herb").getAsInt());
		assertEquals(0, object.get("workers_fish").getAsInt());
		assertEquals(0, object.get("workers_mine").getAsInt());
		assertEquals(0, object.get("workers_rarewood").getAsInt());
		assertEquals(0, object.get("workers_farm").getAsInt());
		assertEquals(0, object.get("cooked_fish").getAsInt());
		assertEquals(1, object.get("rarewood_type").getAsInt());
		assertEquals(0, object.get("herbs_or_flax").getAsInt());
		assertEquals(4321, object.getAsJsonObject("extra").get("restotal_lowbits").getAsInt());
		assertEquals(16, object.size());
	}

	@Test
	public void testNullExtraSerialisesAsEmptyObject() {
		Gson gson = new Gson();
		KingdomSyncPayload payload = new KingdomSyncPayload(
				"TestPlayer", false, true, true, 127, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, null);
		JsonObject object = gson.fromJson(payload.toJson(gson), JsonObject.class);
		assertEquals(0, object.getAsJsonObject("extra").size());
	}
}
