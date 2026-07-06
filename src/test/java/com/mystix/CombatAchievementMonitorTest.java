package com.mystix;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

/**
 * Tests for CombatAchievementMonitor config toggle behavior.
 */
public class CombatAchievementMonitorTest {
	private TestMystixConfig config;

	@Before
	public void setUp() {
		config = new TestMystixConfig();
	}

	@Test
	public void testConfigSyncCombatAchievementsDefaultIsTrue() {
		assertTrue(config.syncCombatAchievements());
	}

	@Test
	public void testConfigSyncCombatAchievementsCanBeDisabled() {
		config.setSyncCombatAchievements(false);
		assertFalse(config.syncCombatAchievements());
	}

	@Test
	public void testConfigSyncCombatAchievementsCanBeReEnabled() {
		config.setSyncCombatAchievements(false);
		assertFalse(config.syncCombatAchievements());

		config.setSyncCombatAchievements(true);
		assertTrue(config.syncCombatAchievements());
	}
}
