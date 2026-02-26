package com.mystix;

import net.runelite.api.Skill;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Tests for PlayerSkillsMonitor config and basic functionality.
 */
public class PlayerSkillsMonitorTest
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
		assertEquals(true, config.syncPlayerSkills());
	}

	@Test
	public void testConfigSyncToggleCanBeDisabled()
	{
		config.setSyncPlayerSkills(false);
		assertEquals(false, config.syncPlayerSkills());
	}

	@Test
	public void testAllSkillsAvailable()
	{
		Skill[] skills = Skill.values();
		assertNotNull(skills);
		assertTrue("Should have at least 20 skills", skills.length > 20);
	}

	@Test
	public void testSkillHasName()
	{
		assertEquals("Attack", Skill.ATTACK.getName());
		assertEquals("Defence", Skill.DEFENCE.getName());
		assertEquals("Strength", Skill.STRENGTH.getName());
		assertEquals("Hitpoints", Skill.HITPOINTS.getName());
	}
}
