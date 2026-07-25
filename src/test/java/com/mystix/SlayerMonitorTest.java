package com.mystix;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

/**
 * Tests for SlayerMonitor's outcome claim table and config toggle.
 *
 * <p>The claim mirrors the backend decision table: streak advance (either
 * counter) or completion chat = completed; block-list membership = blocked;
 * a points drop with neither = skipped. Point AMOUNTS never classify, because
 * Mortimer's skip costs 100, the classic block price.
 */
public class SlayerMonitorTest {
	private TestMystixConfig config;

	@Before
	public void setUp() {
		config = new TestMystixConfig();
	}

	@Test
	public void testConfigSyncSlayerDefaultIsTrue() {
		assertTrue(config.syncSlayer());
	}

	@Test
	public void testConfigSyncSlayerCanBeDisabled() {
		config.setSyncSlayer(false);
		assertFalse(config.syncSlayer());
	}

	@Test
	public void testStreakAdvanceIsCompleted() {
		assertEquals("completed",
				SlayerMonitor.claimOutcome(39, 40, 3, 3, 470, 500, false, false));
	}

	@Test
	public void testWildernessStreakAdvanceIsCompleted() {
		assertEquals("completed",
				SlayerMonitor.claimOutcome(39, 39, 3, 4, 470, 500, false, false));
	}

	@Test
	public void testChatAloneIsCompleted() {
		assertEquals("completed",
				SlayerMonitor.claimOutcome(39, 39, 3, 3, 500, 500, true, false));
	}

	@Test
	public void testBlockListGainIsBlockedRegardlessOfPointCost() {
		// -120 (Mortimer block) and -100 (classic block) both classify by
		// membership, not amount.
		assertEquals("blocked",
				SlayerMonitor.claimOutcome(39, 39, 3, 3, 520, 400, false, true));
		assertEquals("blocked",
				SlayerMonitor.claimOutcome(39, 39, 3, 3, 500, 400, false, true));
	}

	@Test
	public void testMortimerHundredPointDropWithoutBlockIsSkip() {
		// The collision case: -100 is Mortimer's SKIP cost. Without block-list
		// membership it must never classify as blocked.
		assertEquals("skipped",
				SlayerMonitor.claimOutcome(39, 39, 3, 3, 500, 400, false, false));
	}

	@Test
	public void testClassicSkipIsSkipped() {
		assertEquals("skipped",
				SlayerMonitor.claimOutcome(39, 39, 3, 3, 500, 470, false, false));
	}

	@Test
	public void testStreakResetIsReset() {
		assertEquals("reset",
				SlayerMonitor.claimOutcome(39, 0, 3, 3, 500, 500, false, false));
	}

	@Test
	public void testNoSignalsIsUnknown() {
		assertEquals("unknown",
				SlayerMonitor.claimOutcome(39, 39, 3, 3, 500, 500, false, false));
	}

	@Test
	public void testBlockListDecodesPackedBytes() {
		// Live-captured varp values: 424696191 packs Blue dragons (25), Cave
		// horrors (80), Spiritual creatures (89), Metal dragons (127), which
		// matched the four blocked rows in the rewards-interface scrape.
		SlayerMonitor.Snapshot s = new SlayerMonitor.Snapshot();
		s.blockList = java.util.List.of(0, 0, 4784128, 0, 424696191);
		java.util.Set<Integer> ids = s.decodedBlockIds();
		assertTrue(ids.containsAll(java.util.List.of(25, 80, 89, 127, 73)));
		assertFalse(ids.contains(0));
	}

	@Test
	public void testStreakAdvanceWinsOverBlockFlag() {
		// If both fire in one coalesced window, completion (authoritative
		// streak) outranks the block corroboration.
		assertEquals("completed",
				SlayerMonitor.claimOutcome(39, 40, 3, 3, 500, 400, false, true));
	}
}
