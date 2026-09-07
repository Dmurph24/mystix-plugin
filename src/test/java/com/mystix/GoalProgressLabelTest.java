package com.mystix;

import com.mystix.model.GoalType;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class GoalProgressLabelTest {
	@Test
	public void formatsPerType() {
		assertEquals("12.4K XP / 100K XP", GoalProgressLabel.format(GoalType.SKILL_XP, 12_400, 100_000, 12));
		assertEquals("1.3M XP / 2M XP", GoalProgressLabel.format(GoalType.SKILL_LEVEL, 1_300_000, 2_000_000, 65));
		assertEquals("123 / 500", GoalProgressLabel.format(GoalType.KC, 123, 500, 25));
		assertEquals("1,200 / 5,000", GoalProgressLabel.format(GoalType.ITEM_QUANTITY, 1_200, 5_000, 24));
		assertEquals("13.4M / 100M", GoalProgressLabel.format(GoalType.NET_WORTH, 13_400_000, 100_000_000, 13));
		assertEquals("2 / 5", GoalProgressLabel.format(GoalType.FARMING_TIMER, 2, 5, 40));
		assertEquals("1,200 / 3,760", GoalProgressLabel.format(GoalType.ITEM_OWNED, 1_200, 3_760, 31));
		assertEquals("47% grown", GoalProgressLabel.format(GoalType.FARMING_TIMER, 0, 1, 47));
		assertEquals("0% grown", GoalProgressLabel.format(GoalType.FARMING_TIMER, 0, 1, null));
	}

	@Test
	public void percentAndFraction() {
		assertNull(GoalProgressLabel.percent(null));
		assertEquals("63%", GoalProgressLabel.percent(63));
		assertEquals("100%", GoalProgressLabel.percent(150));
		assertEquals(0d, GoalProgressLabel.fraction(null), 0.0001);
		assertEquals(1d, GoalProgressLabel.fraction(150), 0.0001);
		assertEquals(0.25d, GoalProgressLabel.fraction(25), 0.0001);
	}
}
