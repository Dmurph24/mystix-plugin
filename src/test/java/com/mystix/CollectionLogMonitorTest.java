package com.mystix;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

/**
 * Tests for CollectionLogMonitor config toggle behavior.
 */
public class CollectionLogMonitorTest {
	private TestMystixConfig config;

	@Before
	public void setUp() {
		config = new TestMystixConfig();
	}

	@Test
	public void testConfigSyncCollectionLogDefaultIsTrue() {
		assertTrue(config.syncCollectionLog());
	}

	@Test
	public void testConfigSyncCollectionLogCanBeDisabled() {
		config.setSyncCollectionLog(false);
		assertFalse(config.syncCollectionLog());
	}

	@Test
	public void testConfigSyncCollectionLogCanBeReEnabled() {
		config.setSyncCollectionLog(false);
		assertFalse(config.syncCollectionLog());

		config.setSyncCollectionLog(true);
		assertTrue(config.syncCollectionLog());
	}
}
