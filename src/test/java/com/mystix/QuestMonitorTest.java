package com.mystix;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

/**
 * Tests for QuestMonitor config toggle behavior.
 */
public class QuestMonitorTest {
	private TestMystixConfig config;

	@Before
	public void setUp() {
		config = new TestMystixConfig();
	}

	@Test
	public void testConfigSyncQuestsDefaultIsTrue() {
		assertTrue(config.syncQuests());
	}

	@Test
	public void testConfigSyncQuestsCanBeDisabled() {
		config.setSyncQuests(false);
		assertFalse(config.syncQuests());
	}

	@Test
	public void testConfigSyncQuestsCanBeReEnabled() {
		config.setSyncQuests(false);
		assertFalse(config.syncQuests());

		config.setSyncQuests(true);
		assertTrue(config.syncQuests());
	}
}
