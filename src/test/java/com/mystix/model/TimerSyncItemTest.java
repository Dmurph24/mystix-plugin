package com.mystix.model;

import java.time.Instant;
import org.junit.Test;

import static org.junit.Assert.assertTrue;

public class TimerSyncItemTest
{
	@Test
	public void testFarmingPayloadToJson()
	{
		TimerSyncItem item = new TimerSyncItem("tree", "farming guild", "Oak tree", Instant.ofEpochSecond(1700000000L), true, "TestPlayer");
		String json = TimersSyncPayload.toJson(java.util.Collections.singletonList(item));
		assertTrue(json.contains("\"timer_type\":\"tree\""));
		assertTrue(json.contains("\"region\":\"farming guild\""));
		assertTrue(json.contains("\"entity\":\"oak tree\""));
		assertTrue(json.contains("\"completed_at\""));
		assertTrue(json.contains("\"notifications_enabled\":true"));
		assertTrue(json.contains("\"player_username\":\"testplayer\""));
	}

	@Test
	public void testBirdHousePayloadToJson()
	{
		TimerSyncItem item = new TimerSyncItem("bird house", "fossil island", "bird house", Instant.ofEpochSecond(1700000100L), false, "Player1");
		String json = TimersSyncPayload.toJson(java.util.Collections.singletonList(item));
		assertTrue(json.contains("\"timer_type\":\"bird house\""));
		assertTrue(json.contains("\"region\":\"fossil island\""));
		assertTrue(json.contains("\"notifications_enabled\":false"));
	}

	@Test
	public void testApiFormatLowercaseAndSpaces()
	{
		TimerSyncItem item = new TimerSyncItem("Herb_Patches", "Farming_Guild", "Ranarr_weed", Instant.EPOCH, true, "Test_Player");
		String json = TimersSyncPayload.toJson(java.util.Collections.singletonList(item));
		assertTrue(json.contains("\"timer_type\":\"herb patches\""));
		assertTrue(json.contains("\"region\":\"farming guild\""));
		assertTrue(json.contains("\"entity\":\"ranarr weed\""));
	}

	@Test
	public void testJsonEscaping()
	{
		TimerSyncItem item = new TimerSyncItem("tree", "region \"quotes\"", "entity", Instant.EPOCH, true, "User");
		String json = TimersSyncPayload.toJson(java.util.Collections.singletonList(item));
		assertTrue(json.contains("\\\""));
	}

	@Test
	public void testEmptyTimersList()
	{
		String json = TimersSyncPayload.toJson(java.util.Collections.emptyList());
		assertTrue(json.contains("\"timers\":[]"));
	}
}
