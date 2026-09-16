package com.mystix;

import java.util.HashMap;
import java.util.Map;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class ChargesPluginBridgeTest {
	@Test
	public void parsesTheirStorageJson() {
		Map<Integer, Integer> m = ChargesPluginBridge.parse("[{\"itemId\":13439,\"quantity\":5},{\"itemId\":383,\"quantity\":2}]");
		assertEquals(Integer.valueOf(5), m.get(13439));
		assertEquals(Integer.valueOf(2), m.get(383));
		assertTrue(ChargesPluginBridge.parse("[]").isEmpty());
		assertNull(ChargesPluginBridge.parse(null));
		assertNull(ChargesPluginBridge.parse("not json"));
		assertNull(ChargesPluginBridge.parse("{\"itemId\":1}"));
	}

	@Test
	public void keysFoldIntoOurSources() {
		assertEquals("herb_sack", ChargesPluginBridge.sourceForConfigKey("silklined_herb_sack_storage"));
		assertEquals("gem_bag", ChargesPluginBridge.sourceForConfigKey("gem_sack_storage"));
		assertEquals("log_basket", ChargesPluginBridge.sourceForConfigKey("forestry_basket_storage"));
		assertNull(ChargesPluginBridge.sourceForConfigKey("fish_barrel"));
		assertNull(ChargesPluginBridge.sourceForConfigKey("ring_of_dueling_storage"));
		assertEquals(2, ChargesPluginBridge.keysFor("herb_sack").size());
	}

	@Test
	public void readAllSumsFoldedKeysAndSkipsUnknownSources() {
		Map<String, String> cfg = new HashMap<>();
		cfg.put("herb_sack_storage", "[{\"itemId\":207,\"quantity\":3}]");
		cfg.put("silklined_herb_sack_storage", "[{\"itemId\":207,\"quantity\":4}]");
		cfg.put("fish_barrel_storage", "[]");
		Map<String, Map<Integer, Integer>> all = ChargesPluginBridge.readAll((group, key) ->
				ChargesPluginBridge.CONFIG_GROUP.equals(group) ? cfg.get(key) : null);
		assertEquals(Integer.valueOf(7), all.get("herb_sack").get(207));
		assertTrue(all.get("fish_barrel").isEmpty());
		assertNull(all.get("coal_bag"));
	}
}
