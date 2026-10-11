package com.mystix;

import static org.junit.Assert.assertEquals;

import com.google.gson.Gson;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.Test;

public class LootMonitorContextTest
{
	@Test
	public void varbitClearedOnTheRewardTickKeepsItsValue()
	{
		assertEquals(1, LootMonitor.heldVarbitValue(0, new int[]{1, 1570}, 1570));
		assertEquals(1, LootMonitor.heldVarbitValue(0, new int[]{1, 1566}, 1570));
	}

	@Test
	public void varbitClearedLongAgoReadsZero()
	{
		assertEquals(0, LootMonitor.heldVarbitValue(0, new int[]{1, 1500}, 1570));
		assertEquals(0, LootMonitor.heldVarbitValue(0, null, 1570));
	}

	@Test
	public void setVarbitIsReadAsIs()
	{
		assertEquals(1, LootMonitor.heldVarbitValue(1, null, 1570));
		assertEquals(1, LootMonitor.heldVarbitValue(1, new int[]{1, 1200}, 1570));
	}

	@Test
	public void tombsOfAmascutContextCarriesSarcophagusVarbitAndPointsVarp()
	{
		Map<String, Object> context = new LinkedHashMap<>();
		context.put("metadata", new int[]{300, 3, 12345});
		LootMonitor.putContextValues(context, "varbits", new int[]{14373}, id -> 1);
		LootMonitor.putContextValues(context, "varps", new int[]{3606}, id -> 18234);
		LootMonitor.putContextValues(context, "skipped", null, id -> 5);

		assertEquals("{\"metadata\":[300,3,12345],\"varbits\":{\"14373\":1},\"varps\":{\"3606\":18234}}",
			new Gson().toJson(context));
	}
}
