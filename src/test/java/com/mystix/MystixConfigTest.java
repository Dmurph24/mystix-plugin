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

	@Test
	public void testDefaultSyncQuestsIsTrue()
	{
		TestMystixConfig config = new TestMystixConfig();
		assertTrue(config.syncQuests());
	}

	@Test
	public void testDefaultSyncCombatAchievementsIsTrue()
	{
		TestMystixConfig config = new TestMystixConfig();
		assertTrue(config.syncCombatAchievements());
	}

	@Test
	public void testDefaultSyncKillCountsIsTrue()
	{
		TestMystixConfig config = new TestMystixConfig();
		assertTrue(config.syncKillCounts());
	}

	@Test
	public void testDefaultShowNextGoalIsTrue()
	{
		// The overlay is on by default, matching MystixConfig.showNextGoal().
		assertTrue(new TestMystixConfig().showNextGoal());
	}

	@Test
	public void testDefaultGoalFeaturesAreOn()
	{
		TestMystixConfig config = new TestMystixConfig();
		assertTrue(config.showGoalProgress());
		assertTrue(config.showGoalPopup());
		assertEquals(GoalCompleteSound.IN_GAME, config.goalCompleteSound());
	}
}
