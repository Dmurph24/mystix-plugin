package com.mystix;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.mystix.model.KillCountsSyncPayload;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;
import net.runelite.api.gameval.VarPlayerID;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests for KillCountMonitor config toggle behavior.
 */
public class KillCountMonitorTest {
	private TestMystixConfig config;

	@Before
	public void setUp() {
		config = new TestMystixConfig();
	}

	@Test
	public void testConfigSyncKillCountsDefaultIsTrue() {
		assertTrue(config.syncKillCounts());
	}

	@Test
	public void testConfigSyncKillCountsCanBeDisabled() {
		config.setSyncKillCounts(false);
		assertFalse(config.syncKillCounts());
	}

	@Test
	public void testConfigSyncKillCountsCanBeReEnabled() {
		config.setSyncKillCounts(false);
		assertFalse(config.syncKillCounts());

		config.setSyncKillCounts(true);
		assertTrue(config.syncKillCounts());
	}

	private static Map<Integer, Integer> doomScoreboard() {
		Map<Integer, Integer> varps = new HashMap<>();
		varps.put(VarPlayerID.DOM_LEVEL_1_COMPLETIONS, 179);
		varps.put(VarPlayerID.DOM_LEVEL_2_COMPLETIONS, 175);
		varps.put(VarPlayerID.DOM_LEVEL_3_COMPLETIONS, 170);
		varps.put(VarPlayerID.DOM_LEVEL_4_COMPLETIONS, 160);
		varps.put(VarPlayerID.DOM_LEVEL_5_COMPLETIONS, 140);
		varps.put(VarPlayerID.DOM_LEVEL_6_COMPLETIONS, 90);
		varps.put(VarPlayerID.DOM_LEVEL_7_COMPLETIONS, 31);
		varps.put(VarPlayerID.DOM_LEVEL_8_COMPLETIONS, 0);
		varps.put(VarPlayerID.DOM_LEVEL_8_PLUS_COMPLETIONS, 0);
		varps.put(VarPlayerID.DOM_DEEPEST_LEVEL, 7);
		return varps;
	}

	private static String payloadJson(Map<Integer, Integer> varps) {
		Map<String, Integer> killCounts = new TreeMap<>();
		killCounts.put("zulrah", 512);
		killCounts.putAll(KillCountMonitor.doomDelves(varp -> varps.getOrDefault(varp, 0)));
		return new KillCountsSyncPayload("Player", killCounts).toJson(new Gson());
	}

	@Test
	public void testPayloadCarriesDoomDelvesAndSkipsZeros() {
		JsonObject kc = new Gson().fromJson(payloadJson(doomScoreboard()), JsonObject.class)
				.getAsJsonObject("kill_counts");

		assertEquals(512, kc.get("zulrah").getAsInt());
		assertEquals(179, kc.get("doom delve 1").getAsInt());
		assertEquals(175, kc.get("doom delve 2").getAsInt());
		assertEquals(170, kc.get("doom delve 3").getAsInt());
		assertEquals(160, kc.get("doom delve 4").getAsInt());
		assertEquals(140, kc.get("doom delve 5").getAsInt());
		assertEquals(90, kc.get("doom delve 6").getAsInt());
		assertEquals(31, kc.get("doom delve 7").getAsInt());
		assertEquals(7, kc.get("doom deepest delve").getAsInt());
		assertFalse(kc.has("doom delve 8"));
		assertFalse(kc.has("doom delve 8+"));
		assertEquals(9, kc.size());
	}

	@Test
	public void testDeepDelvesUseTheEightPlusKey() {
		Map<Integer, Integer> varps = doomScoreboard();
		varps.put(VarPlayerID.DOM_LEVEL_8_COMPLETIONS, 12);
		varps.put(VarPlayerID.DOM_LEVEL_8_PLUS_COMPLETIONS, 20);
		varps.put(VarPlayerID.DOM_DEEPEST_LEVEL, 11);

		Map<String, Integer> delves = KillCountMonitor.doomDelves(varp -> varps.getOrDefault(varp, 0));

		assertEquals(Integer.valueOf(12), delves.get("doom delve 8"));
		assertEquals(Integer.valueOf(20), delves.get("doom delve 8+"));
		assertEquals(Integer.valueOf(11), delves.get("doom deepest delve"));
	}

	@Test
	public void testEmptyScoreboardAddsNothing() {
		assertTrue(KillCountMonitor.doomDelves(varp -> 0).isEmpty());
	}

	@Test
	public void testUnchangedScoreboardDedupesAndACompletedLevelResends() {
		String first = payloadJson(doomScoreboard());
		assertEquals(first, payloadJson(doomScoreboard()));

		Map<Integer, Integer> afterLevel = doomScoreboard();
		afterLevel.put(VarPlayerID.DOM_LEVEL_1_COMPLETIONS, 180);
		assertNotEquals(first, payloadJson(afterLevel));
	}
}
