package com.mystix;

import net.runelite.api.WorldType;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Tests for special game mode detection using WorldType enum.
 * Verifies that all special game mode world types are available for checking.
 * Special modes that should be excluded: SEASONAL, DEADMAN, FRESH_START_WORLD,
 * TOURNAMENT_WORLD, BETA_WORLD, NOSAVE_MODE, QUEST_SPEEDRUNNING.
 */
public class SpecialGameModeTest
{
	@Test
	public void testSpecialGameModeWorldTypesExist()
	{
		assertNotNull("WorldType.SEASONAL should exist", WorldType.SEASONAL);
		assertNotNull("WorldType.DEADMAN should exist", WorldType.DEADMAN);
		assertNotNull("WorldType.FRESH_START_WORLD should exist", WorldType.FRESH_START_WORLD);
		assertNotNull("WorldType.TOURNAMENT_WORLD should exist", WorldType.TOURNAMENT_WORLD);
		assertNotNull("WorldType.BETA_WORLD should exist", WorldType.BETA_WORLD);
		assertNotNull("WorldType.NOSAVE_MODE should exist", WorldType.NOSAVE_MODE);
		assertNotNull("WorldType.QUEST_SPEEDRUNNING should exist", WorldType.QUEST_SPEEDRUNNING);
	}

	@Test
	public void testNormalGameModeWorldTypesExist()
	{
		assertNotNull("WorldType.MEMBERS should exist", WorldType.MEMBERS);
		assertNotNull("WorldType.SKILL_TOTAL should exist", WorldType.SKILL_TOTAL);
		assertNotNull("WorldType.PVP should exist", WorldType.PVP);
	}

	@Test
	public void testWorldTypeEnumValues()
	{
		WorldType[] types = WorldType.values();
		assertNotNull(types);
		assertTrue("Should have multiple WorldType values", types.length >= 7);
		
		boolean hasAllSpecialModes = true;
		WorldType[] specialModes = {
			WorldType.SEASONAL,
			WorldType.DEADMAN,
			WorldType.FRESH_START_WORLD,
			WorldType.TOURNAMENT_WORLD,
			WorldType.BETA_WORLD,
			WorldType.NOSAVE_MODE,
			WorldType.QUEST_SPEEDRUNNING
		};
		
		for (WorldType specialMode : specialModes)
		{
			boolean found = false;
			for (WorldType type : types)
			{
				if (type == specialMode)
				{
					found = true;
					break;
				}
			}
			if (!found)
			{
				hasAllSpecialModes = false;
				break;
			}
		}
		assertTrue("WorldType enum should contain all special game mode types", hasAllSpecialModes);
	}
}
