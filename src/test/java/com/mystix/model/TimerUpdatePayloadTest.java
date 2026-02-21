package com.mystix.model;

import net.runelite.client.plugins.timetracking.Tab;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class TimerUpdatePayloadTest
{
	@Test
	public void testFarmingPayloadToJson()
	{
		TimerUpdatePayload payload = TimerUpdatePayload.farming(Tab.HERB, 1700000000L);
		String json = payload.toJson();
		assertTrue(json.contains("\"type\":\"farming\""));
		assertTrue(json.contains("\"entity\":\"HERB\""));
		assertTrue(json.contains("\"expectedFinishTime\":1700000000"));
	}

	@Test
	public void testBirdHousePayloadToJson()
	{
		TimerUpdatePayload payload = TimerUpdatePayload.birdHouse(1700000100L);
		String json = payload.toJson();
		assertTrue(json.contains("\"type\":\"birdhouse\""));
		assertTrue(json.contains("\"entity\":\"birdhouse\""));
		assertTrue(json.contains("\"expectedFinishTime\":1700000100"));
	}

	@Test
	public void testTimerPayloadToJson()
	{
		TimerUpdatePayload payload = TimerUpdatePayload.timer("My Timer", 1700000200L);
		String json = payload.toJson();
		assertTrue(json.contains("\"type\":\"timer\""));
		assertTrue(json.contains("\"entity\":\"My Timer\""));
		assertTrue(json.contains("\"expectedFinishTime\":1700000200"));
	}

	@Test
	public void testJsonEscaping()
	{
		TimerUpdatePayload payload = TimerUpdatePayload.timer("Timer \"with\" quotes", 123L);
		String json = payload.toJson();
		assertTrue(json.contains("\\\""));
		assertTrue(json.contains("expectedFinishTime"));
	}
}
