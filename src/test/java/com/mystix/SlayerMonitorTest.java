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
				SlayerMonitor.claimOutcome(39, 40, 3, 3, 470, 500, false, false, false));
	}

	@Test
	public void testWildernessStreakAdvanceIsCompleted() {
		assertEquals("completed",
				SlayerMonitor.claimOutcome(39, 39, 3, 4, 470, 500, false, false, false));
	}

	@Test
	public void testChatAloneIsCompleted() {
		assertEquals("completed",
				SlayerMonitor.claimOutcome(39, 39, 3, 3, 500, 500, true, false, false));
	}

	@Test
	public void testBlockListGainIsBlockedRegardlessOfPointCost() {
		// -120 (Mortimer block) and -100 (classic block) both classify by
		// membership, not amount.
		assertEquals("blocked",
				SlayerMonitor.claimOutcome(39, 39, 3, 3, 520, 400, false, true, false));
		assertEquals("blocked",
				SlayerMonitor.claimOutcome(39, 39, 3, 3, 500, 400, false, true, false));
	}

	@Test
	public void testMortimerHundredPointDropWithoutBlockIsSkip() {
		// The collision case: -100 is Mortimer's SKIP cost. Without block-list
		// membership it must never classify as blocked.
		assertEquals("skipped",
				SlayerMonitor.claimOutcome(39, 39, 3, 3, 500, 400, false, false, false));
	}

	@Test
	public void testClassicSkipIsSkipped() {
		assertEquals("skipped",
				SlayerMonitor.claimOutcome(39, 39, 3, 3, 500, 470, false, false, false));
	}

	@Test
	public void testStreakResetIsReset() {
		assertEquals("reset",
				SlayerMonitor.claimOutcome(39, 0, 3, 3, 500, 500, false, false, false));
	}

	@Test
	public void testNoSignalsIsUnknown() {
		assertEquals("unknown",
				SlayerMonitor.claimOutcome(39, 39, 3, 3, 500, 500, false, false, false));
	}

	@Test
	public void testMortimerPointsAwardWithFlatStreakIsCompleted() {
		// Mortimer keeps its own streak, which no varbit exposes, and its chat
		// line does not match the completion pattern. The +15 modifier award is
		// the only signal, and nothing but a completion pays.
		assertEquals("completed",
				SlayerMonitor.claimOutcome(121, 121, 0, 0, 291, 306, false, false, false));
	}

	@Test
	public void testMortimerUnpaidTaskCompletesOnCountdownToZero() {
		// A Mortimer task offered without a points modifier awards nothing, so
		// the observed countdown to 0 is all that separates it from a cancel.
		assertEquals("completed",
				SlayerMonitor.claimOutcome(121, 121, 0, 0, 306, 306, false, false, true));
	}

	@Test
	public void testCountdownToZeroNeverOutranksACharge() {
		// A cancel clears the same varp to 0: the charge has to win, or every
		// skip and block would read as a completion.
		assertEquals("skipped",
				SlayerMonitor.claimOutcome(121, 121, 0, 0, 406, 306, false, false, true));
		assertEquals("blocked",
				SlayerMonitor.claimOutcome(121, 121, 0, 0, 426, 306, false, true, true));
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
				SlayerMonitor.claimOutcome(39, 40, 3, 3, 500, 400, false, true, false));
	}

	@Test
	public void testHasStoredTaskRegularTask() {
		assertTrue(SlayerMonitor.hasStoredTask(51, 77));
		assertFalse(SlayerMonitor.hasStoredTask(0, 77));
		assertFalse(SlayerMonitor.hasStoredTask(51, 0));
		assertFalse(SlayerMonitor.hasStoredTask(0, 0));
		// Negative amounts only render for the boss sentinel in game.
		assertFalse(SlayerMonitor.hasStoredTask(51, -1));
	}

	@Test
	public void testHasStoredTaskBossSentinel() {
		assertTrue(SlayerMonitor.hasStoredTask(98, 5));
		// The rewards script draws a name-only slot for a boss with amount -1.
		assertTrue(SlayerMonitor.hasStoredTask(98, -1));
		assertFalse(SlayerMonitor.hasStoredTask(98, 0));
	}

	@Test
	public void testStoredTaskExtraEmptyUntilCaptured() {
		assertTrue(SlayerMonitor.storedTaskExtra(null).isEmpty());
	}

	@Test
	public void testStoredTaskExtraCarriesCapture() {
		SlayerMonitor.StoredTaskCapture capture = new SlayerMonitor.StoredTaskCapture();
		capture.taskId = 98;
		capture.amount = 35;
		capture.bossTaskId = 3;
		capture.taskName = "Kalphite Queen";
		capture.capturedAt = "2026-07-28T00:00:00Z";
		java.util.Map<String, Object> extra = SlayerMonitor.storedTaskExtra(capture);
		assertEquals(98, extra.get("stored_task_id"));
		assertEquals(35, extra.get("stored_task_amount"));
		assertEquals(3, extra.get("stored_boss_task_id"));
		assertEquals("Kalphite Queen", extra.get("stored_task_name"));
		assertEquals("2026-07-28T00:00:00Z", extra.get("stored_task_captured_at"));
	}

	@Test
	public void testStoredTaskExtraOmitsUnresolvedName() {
		// A capture that confirmed the storage is EMPTY still syncs zeros, so
		// the backend can distinguish "empty" from "never captured".
		SlayerMonitor.StoredTaskCapture capture = new SlayerMonitor.StoredTaskCapture();
		capture.capturedAt = "2026-07-28T00:00:00Z";
		java.util.Map<String, Object> extra = SlayerMonitor.storedTaskExtra(capture);
		assertEquals(0, extra.get("stored_task_id"));
		assertEquals(0, extra.get("stored_task_amount"));
		assertEquals(0, extra.get("stored_boss_task_id"));
		assertFalse(extra.containsKey("stored_task_name"));
	}

	@Test
	public void testParseTaskOffersAgainstCapturedDialogRows() {
		// Verbatim rows captured from the live Slayer Task Choice dialog
		// (interface 236.3), including its underline name tags.
		java.util.List<java.util.Map<String, Object>> offers =
				SlayerMonitor.parseTaskOffers(java.util.Arrays.asList(
						"Slayer Task Choice",
						"<u=ff981f>Turoth", "Amount: 80 to 120", "+15 Slayer points",
						"<u=ff981f>Gryphons", "Amount: 80 to 120", "+20 Slayer points",
						"<col=b2b2b2>Complete 50 tasks with Mortimer to unlock a third choice."));
		assertEquals(2, offers.size());
		assertEquals("Turoth", offers.get(0).get("name"));
		assertEquals(80, offers.get(0).get("amount_min"));
		assertEquals(120, offers.get(0).get("amount_max"));
		assertEquals("+15 Slayer points", offers.get(0).get("modifier_text"));
		assertEquals(15, offers.get(0).get("modifier_value"));
		assertEquals("Slayer points", offers.get(0).get("modifier_label"));
		assertEquals("Gryphons", offers.get(1).get("name"));
		assertEquals(20, offers.get(1).get("modifier_value"));
	}

	@Test
	public void testParseTaskOffersHandlesEveryObservedModifierForm() {
		// value, isPercent, label, and the row as displayed in game.
		Object[][] cases = {
				{30, true, "Slayer XP", "+30% Slayer XP"},
				{300, true, "Superior Chance", "+300% Superior Chance"},
				{100, false, "Assigned", "+100 Assigned"},
				{2, false, "clue scrolls", "x2 clue scrolls"},
		};
		for (Object[] c : cases) {
			java.util.List<java.util.Map<String, Object>> offers =
					SlayerMonitor.parseTaskOffers(java.util.Arrays.asList(
							"<u=ff981f>Turoth", "Amount: 80 to 120", (String) c[3]));
			assertEquals(1, offers.size());
			assertEquals(c[3], offers.get(0).get("modifier_text"));
			assertEquals(c[0], offers.get(0).get("modifier_value"));
			assertEquals(c[1], offers.get(0).get("modifier_is_percent"));
			assertEquals(c[2], offers.get(0).get("modifier_label"));
		}
	}

	@Test
	public void testUnparseableModifierWordingIsStillSent() {
		java.util.List<java.util.Map<String, Object>> offers =
				SlayerMonitor.parseTaskOffers(java.util.Arrays.asList(
						"Dust devils", "Amount: 145 to 237", "Guaranteed superior spawn"));
		assertEquals(1, offers.size());
		assertEquals("Guaranteed superior spawn", offers.get(0).get("modifier_text"));
		assertFalse(offers.get(0).containsKey("modifier_value"));
	}

	@Test
	public void testParseTaskOffersSingleAmountAndNoModifier() {
		java.util.List<java.util.Map<String, Object>> offers =
				SlayerMonitor.parseTaskOffers(java.util.Arrays.asList(
						"Basilisks", "Amount: 48", ""));
		assertEquals(1, offers.size());
		assertEquals(48, offers.get(0).get("amount_min"));
		assertEquals(48, offers.get(0).get("amount_max"));
		assertFalse(offers.get(0).containsKey("modifier_text"));
	}

	@Test
	public void testParseTaskOffersRejectsNonOfferDialogs() {
		java.util.List<String> texts = java.util.Arrays.asList(
				"Select an option", "Yes", "No", "Cancel");
		org.junit.Assert.assertTrue(SlayerMonitor.parseTaskOffers(texts).isEmpty());
	}
}
