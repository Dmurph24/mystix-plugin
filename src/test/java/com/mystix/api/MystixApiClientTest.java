package com.mystix.api;

import com.mystix.MystixConfig;
import com.mystix.model.TimerSyncItem;
import com.mystix.model.TimersSyncPayload;
import java.time.Instant;
import java.util.Collections;
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
		TimerSyncItem item = new TimerSyncItem("birdhouse", Instant.ofEpochSecond(1700000000L), true, "TestPlayer");
		client.sendTimersSync(Collections.singletonList(item));
		// Should not throw; with empty key it returns early
	}

	@Test
	public void testPayloadStructure()
	{
		TimerSyncItem item = new TimerSyncItem("farming:HERB", Instant.ofEpochSecond(1700000000L), true, "TestPlayer");
		String json = TimersSyncPayload.toJson(Collections.singletonList(item));
		assertNotNull(json);
		assertTrue(json.contains("\"timers\""));
		assertTrue(json.contains("\"name\":\"farming:HERB\""));
		assertTrue(json.contains("\"completed_at\""));
		assertTrue(json.contains("\"notifications_enabled\":true"));
		assertTrue(json.contains("\"player_username\":\"TestPlayer\""));
	}
}
