package com.mystix;

import static org.junit.Assert.assertEquals;

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
}
