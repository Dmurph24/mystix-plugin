package com.mystix;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PlayerSkillsMonitorXpTest {
	@Test
	public void firstObservationIsNotAGain() {
		assertFalse(PlayerSkillsMonitor.xpIncreased(null, 1000));
	}

	@Test
	public void boostedLevelWithSameXpIsNotAGain() {
		assertFalse(PlayerSkillsMonitor.xpIncreased(1000, 1000));
	}

	@Test
	public void higherXpIsAGain() {
		assertTrue(PlayerSkillsMonitor.xpIncreased(1000, 1050));
	}
}
