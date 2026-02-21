package com.mystix.model;

import java.time.Instant;
import org.junit.Test;

import static org.junit.Assert.assertTrue;

public class TimerSyncItemTest
{
	@Test
	public void testFarmingPayloadToJson()
	{
		TimerSyncItem item = new TimerSyncItem("farming:HERB", Instant.ofEpochSecond(1700000000L), true, "TestPlayer");
		String json = TimersSyncPayload.toJson(java.util.Collections.singletonList(item));
		assertTrue(json.contains("\"name\":\"farming:HERB\""));
		assertTrue(json.contains("\"completed_at\""));
		assertTrue(json.contains("\"notifications_enabled\":true"));
		assertTrue(json.contains("\"player_username\":\"TestPlayer\""));
	}

	@Test
	public void testBirdHousePayloadToJson()
	{
		TimerSyncItem item = new TimerSyncItem("birdhouse", Instant.ofEpochSecond(1700000100L), false, "Player1");
		String json = TimersSyncPayload.toJson(java.util.Collections.singletonList(item));
		assertTrue(json.contains("\"name\":\"birdhouse\""));
		assertTrue(json.contains("\"notifications_enabled\":false"));
	}

	@Test
	public void testJsonEscaping()
	{
		TimerSyncItem item = new TimerSyncItem("timer \"with\" quotes", Instant.EPOCH, true, "User");
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
