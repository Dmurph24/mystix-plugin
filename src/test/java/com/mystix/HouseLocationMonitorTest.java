package com.mystix;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

/**
 * Tests for HouseLocationMonitor config toggle behavior.
 */
public class HouseLocationMonitorTest {
	private TestMystixConfig config;

	@Before
	public void setUp() {
		config = new TestMystixConfig();
	}

	@Test
	public void testConfigSyncHouseLocationDefaultIsTrue() {
		assertTrue(config.syncHouseLocation());
	}

	@Test
	public void testConfigSyncHouseLocationCanBeDisabled() {
		config.setSyncHouseLocation(false);
		assertFalse(config.syncHouseLocation());
	}

	@Test
	public void testConfigSyncHouseLocationCanBeReEnabled() {
		config.setSyncHouseLocation(false);
		assertFalse(config.syncHouseLocation());

		config.setSyncHouseLocation(true);
		assertTrue(config.syncHouseLocation());
	}
}
