package com.mystix;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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

	@Test
	public void testDefaultSyncQuestsIsTrue()
	{
		TestMystixConfig config = new TestMystixConfig();
		assertTrue(config.syncQuests());
	}

	@Test
	public void testDefaultShowNextGoalIsFalse()
	{
		// The overlay is opt-in; off by default.
		assertFalse(new TestMystixConfig().showNextGoal());
	}
}
