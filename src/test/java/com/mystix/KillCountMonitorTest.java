package com.mystix;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

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
}
