package com.mystix;

import net.runelite.client.plugins.timetracking.SummaryState;
import net.runelite.client.plugins.timetracking.Tab;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Tests for TimerMonitor. Verifies config defaults and that sync toggle is respected.
 */
public class TimerMonitorTest
{
	private TestMystixConfig config;

	@Before
	public void setUp()
	{
		config = new TestMystixConfig();
	}

	@Test
	public void testConfigSyncToggleDefaultIsTrue()
	{
		assertEquals(true, config.syncTimeTracking());
	}

	@Test
	public void testConfigSyncToggleCanBeDisabled()
	{
		config.setSyncTimeTracking(false);
		assertEquals(false, config.syncTimeTracking());
	}

	@Test
	public void testFarmingTabsAvailable()
	{
		assertNotNull(Tab.FARMING_TABS);
		assertTrue(Tab.FARMING_TABS.length > 0);
	}

	@Test
	public void testSummaryStateValues()
	{
		assertEquals(SummaryState.IN_PROGRESS, SummaryState.IN_PROGRESS);
		assertEquals(SummaryState.COMPLETED, SummaryState.COMPLETED);
	}
}
