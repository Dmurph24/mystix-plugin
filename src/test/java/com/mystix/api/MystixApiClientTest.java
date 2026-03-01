package com.mystix.api;

import com.mystix.MystixConfig;
import com.mystix.model.PlayerSkillsSyncPayload;
import com.mystix.model.TimerSyncItem;
import com.mystix.model.TimersSyncPayload;
import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.junit.Test;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Tests that MystixApiClient handles config correctly and payload structure.
 */
public class MystixApiClientTest {
	private static MystixConfig emptyKeyConfig() {
		com.mystix.TestMystixConfig c = new com.mystix.TestMystixConfig();
		c.setMystixAppKey("");
		return c;
	}

	@Test
	public void testSendWithEmptyAppKeyDoesNotThrow() {
		MystixApiClient client = new MystixApiClient(emptyKeyConfig());
		TimerSyncItem item = new TimerSyncItem("bird house", "fossil island", "bird house",
				Instant.ofEpochSecond(1700000000L), true, "TestPlayer", null, null, null);
		client.sendTimersSync(Collections.singletonList(item));
		// Should not throw; with empty key it returns early
	}

	@Test
	public void testPayloadStructure() {
		TimerSyncItem item = new TimerSyncItem("tree", "farming guild", "Oak tree", Instant.ofEpochSecond(1700000000L),
				true, "TestPlayer", null, null, null);
		String json = TimersSyncPayload.toJson(Collections.singletonList(item));
		assertNotNull(json);
		assertTrue(json.contains("\"timers\""));
		assertTrue(json.contains("\"timer_type\":\"tree\""));
		assertTrue(json.contains("\"region\":\"farming guild\""));
		assertTrue(json.contains("\"entity\":\"oak tree\""));
		assertTrue(json.contains("\"completed_at\""));
		assertTrue(json.contains("\"notifications_enabled\":true"));
		assertTrue(json.contains("\"player_username\":\"testplayer\""));
	}

	@Test
	public void testSendPlayerSkillsWithEmptyAppKeyDoesNotThrow() {
		MystixApiClient client = new MystixApiClient(emptyKeyConfig());
		Map<String, PlayerSkillsSyncPayload.SkillData> skills = new HashMap<>();
		skills.put("Attack", new PlayerSkillsSyncPayload.SkillData(75, 1200000));
		skills.put("Defence", new PlayerSkillsSyncPayload.SkillData(70, 800000));
		PlayerSkillsSyncPayload payload = new PlayerSkillsSyncPayload("TestPlayer", skills, 145, 85);
		client.sendPlayerSkillsSync(payload);
		// Should not throw; with empty key it returns early
	}

	@Test
	public void testPlayerSkillsPayloadStructure() {
		Map<String, PlayerSkillsSyncPayload.SkillData> skills = new HashMap<>();
		skills.put("Attack", new PlayerSkillsSyncPayload.SkillData(75, 1200000));
		skills.put("Defence", new PlayerSkillsSyncPayload.SkillData(70, 800000));
		skills.put("Strength", new PlayerSkillsSyncPayload.SkillData(80, 2000000));
		PlayerSkillsSyncPayload payload = new PlayerSkillsSyncPayload("TestPlayer", skills, 225, 95);
		String json = payload.toJson();

		assertNotNull(json);
		assertTrue(json.contains("\"player\":\"TestPlayer\""));
		assertTrue(json.contains("\"skills\""));
		/* API expects skills as {"SkillName": {"level": N, "current_xp": N}} */
		assertTrue(json.contains("Attack"));
		assertTrue(json.contains("\"level\":75"));
		assertTrue(json.contains("\"level\":70"));
		assertTrue(json.contains("\"level\":80"));
		assertTrue(json.contains("\"current_xp\":1200000"));
		assertTrue(json.contains("\"current_xp\":800000"));
		assertTrue(json.contains("\"current_xp\":2000000"));
	}
}
