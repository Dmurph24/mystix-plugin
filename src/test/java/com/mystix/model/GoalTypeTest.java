package com.mystix.model;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class GoalTypeTest {
	@Test
	public void everyWireValueParses() {
		for (GoalType type : GoalType.values()) {
			if (type != GoalType.UNKNOWN) {
				assertEquals(type, GoalType.fromWire(type.getWire()));
			}
		}
	}

	@Test
	public void unknownAndNullMapToUnknown() {
		assertEquals(GoalType.UNKNOWN, GoalType.fromWire(null));
		assertEquals(GoalType.UNKNOWN, GoalType.fromWire("future_type"));
		assertEquals(GoalType.UNKNOWN, GoalType.fromWire(""));
	}

	@Test
	public void groupings() {
		assertTrue(GoalType.SKILL_LEVEL.isSkill());
		assertTrue(GoalType.SKILL_XP.isMeasurable());
		assertTrue(GoalType.KC.isLootCounted());
		assertTrue(GoalType.ITEM_QUANTITY.isLootCounted());
		assertTrue(GoalType.NPC_DROP.isItemObtain());
		assertTrue(GoalType.CLOG_ITEM.isItemObtain());
		assertTrue(GoalType.FARMING_TIMER.isMeasurable());
		assertFalse(GoalType.QUEST.isMeasurable());
		assertFalse(GoalType.CUSTOM.isMeasurable());
		assertTrue(GoalType.NET_WORTH.isServerDriven());
		assertTrue(GoalType.TEARS_OF_GUTHIX.isServerDriven());
		assertFalse(GoalType.KC.isServerDriven());
		assertFalse(GoalType.FARMING_TIMER.isServerDriven());
	}
}
