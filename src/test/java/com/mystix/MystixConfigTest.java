package com.mystix;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Tests for MystixConfig default values.
 */
public class MystixConfigTest
{
	@Test
	public void testDefaultAppKeyIsEmpty()
	{
		TestMystixConfig config = new TestMystixConfig();
		assertEquals("", config.mystixAppKey());
	}

	@Test
	public void testDefaultSyncTimeTrackingIsTrue()
	{
		TestMystixConfig config = new TestMystixConfig();
		assertTrue(config.syncTimeTracking());
	}
}
