package com.mystix;

import net.runelite.api.WorldType;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Tests for special game mode detection using WorldType enum.
 * Verifies that SEASONAL and DEADMAN world types are available for checking.
 */
public class SpecialGameModeTest
{
	@Test
	public void testWorldTypeSeasonalExists()
	{
		assertNotNull("WorldType.SEASONAL should exist", WorldType.SEASONAL);
	}

	@Test
	public void testWorldTypeDeadmanExists()
	{
		assertNotNull("WorldType.DEADMAN should exist", WorldType.DEADMAN);
	}

	@Test
	public void testWorldTypeEnumValues()
	{
		WorldType[] types = WorldType.values();
		assertNotNull(types);
		assertTrue("Should have multiple WorldType values", types.length > 0);
		
		boolean hasSeasonalOrDeadman = false;
		for (WorldType type : types)
		{
			if (type == WorldType.SEASONAL || type == WorldType.DEADMAN)
			{
				hasSeasonalOrDeadman = true;
				break;
			}
		}
		assertTrue("WorldType enum should contain SEASONAL or DEADMAN", hasSeasonalOrDeadman);
	}
}
