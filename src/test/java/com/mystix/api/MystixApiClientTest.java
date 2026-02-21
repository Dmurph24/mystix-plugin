package com.mystix.api;

import com.mystix.MystixConfig;
import com.mystix.model.TimerUpdatePayload;
import net.runelite.client.plugins.timetracking.Tab;
import org.junit.Test;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Tests that MystixApiClient handles config correctly and payload structure.
 */
public class MystixApiClientTest
{
	private static MystixConfig emptyKeyConfig()
	{
		com.mystix.TestMystixConfig c = new com.mystix.TestMystixConfig();
		c.setMystixAppKey("");
		return c;
	}

	@Test
	public void testSendWithEmptyAppKeyDoesNotThrow()
	{
		MystixApiClient client = new MystixApiClient(emptyKeyConfig());
		TimerUpdatePayload payload = TimerUpdatePayload.birdHouse(1700000000L);
		client.sendTimerUpdate(payload);
		// Should not throw; with empty key it returns early
	}

	@Test
	public void testPayloadStructure()
	{
		TimerUpdatePayload payload = TimerUpdatePayload.farming(Tab.HERB, 1700000000L);
		String json = payload.toJson();
		assertNotNull(json);
		assertTrue(json.contains("type"));
		assertTrue(json.contains("entity"));
		assertTrue(json.contains("expectedFinishTime"));
	}
}
