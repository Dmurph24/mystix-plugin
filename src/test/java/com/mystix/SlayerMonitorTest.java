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
	public void testParseTaskOffersReadsRangeAndPointModifier() {
		// Observed Slayer Task Choice dialog: two offers, ranged amounts,
		// "+N Slayer points" modifiers, and a locked-third-choice notice.
		java.util.List<String> texts = java.util.Arrays.asList(
				"Slayer Task Choice",
				"<col=ff9040>Turoth</col>",
				"Amount: 80 to 120",
				"",
				"+15 Slayer points",
				"<col=ff9040>Gryphons</col>",
				"Amount: 80 to 120",
				"",
				"+20 Slayer points",
				"Complete 50 tasks with Mortimer to unlock a third choice.");
		java.util.List<java.util.Map<String, Object>> offers =
				SlayerMonitor.parseTaskOffers(texts);
		org.junit.Assert.assertEquals(2, offers.size());
		org.junit.Assert.assertEquals("Turoth", offers.get(0).get("name"));
		org.junit.Assert.assertEquals(80, offers.get(0).get("amount_min"));
		org.junit.Assert.assertEquals(120, offers.get(0).get("amount_max"));
		org.junit.Assert.assertEquals("+15 Slayer points", offers.get(0).get("modifier_text"));
		org.junit.Assert.assertEquals(15, offers.get(0).get("modifier_value"));
		org.junit.Assert.assertEquals(false, offers.get(0).get("modifier_is_percent"));
		org.junit.Assert.assertEquals("Slayer points", offers.get(0).get("modifier_label"));
		org.junit.Assert.assertEquals("Gryphons", offers.get(1).get("name"));
		org.junit.Assert.assertEquals(20, offers.get(1).get("modifier_value"));
	}

	@Test
	public void testParseTaskOffersHandlesPercentAndMultiplierModifiers() {
		java.util.List<String> texts = java.util.Arrays.asList(
				"Abyssal demons", "Amount: 155 to 234", "+25% Slayer XP",
				"Gargoyles", "Amount: 120 to 180", "x2 clue scrolls");
		java.util.List<java.util.Map<String, Object>> offers =
				SlayerMonitor.parseTaskOffers(texts);
		org.junit.Assert.assertEquals(2, offers.size());
		org.junit.Assert.assertEquals(true, offers.get(0).get("modifier_is_percent"));
		org.junit.Assert.assertEquals("Slayer XP", offers.get(0).get("modifier_label"));
		org.junit.Assert.assertEquals(true, offers.get(1).get("modifier_multiplies"));
		org.junit.Assert.assertEquals(2, offers.get(1).get("modifier_value"));
	}

	@Test
	public void testParseTaskOffersSingleAmountAndNoModifier() {
		java.util.List<String> texts = java.util.Arrays.asList(
				"Basilisks", "Amount: 48", "");
		java.util.List<java.util.Map<String, Object>> offers =
				SlayerMonitor.parseTaskOffers(texts);
		org.junit.Assert.assertEquals(1, offers.size());
		org.junit.Assert.assertEquals(48, offers.get(0).get("amount_min"));
		org.junit.Assert.assertEquals(48, offers.get(0).get("amount_max"));
		org.junit.Assert.assertFalse(offers.get(0).containsKey("modifier_value"));
	}

	@Test
	public void testParseTaskOffersReadsSuperiorChanceModifier() {
		java.util.List<String> texts = java.util.Arrays.asList(
				"Kurask", "Amount: 40 to 60", "25% superior chance");
		java.util.List<java.util.Map<String, Object>> offers =
				SlayerMonitor.parseTaskOffers(texts);
		org.junit.Assert.assertEquals(1, offers.size());
		org.junit.Assert.assertEquals(25, offers.get(0).get("modifier_value"));
		org.junit.Assert.assertEquals(true, offers.get(0).get("modifier_is_percent"));
		org.junit.Assert.assertEquals("superior chance", offers.get(0).get("modifier_label"));
		org.junit.Assert.assertEquals(false, offers.get(0).get("modifier_multiplies"));
	}

	@Test
	public void testParseTaskOffersKeepsRawRowsForUnrecognisedModifiers() {
		// An unquantified wording still reaches the server via raw_rows.
		java.util.List<String> texts = java.util.Arrays.asList(
				"Dust devils", "Amount: 145 to 237", "Guaranteed superior spawn");
		java.util.List<java.util.Map<String, Object>> offers =
				SlayerMonitor.parseTaskOffers(texts);
		org.junit.Assert.assertEquals(1, offers.size());
		org.junit.Assert.assertFalse(offers.get(0).containsKey("modifier_value"));
		org.junit.Assert.assertEquals(
				java.util.Collections.singletonList("Guaranteed superior spawn"),
				offers.get(0).get("raw_rows"));
	}

	@Test
	public void testParseTaskOffersAgainstCapturedDialogRows() {
		// Verbatim rows captured from the live Slayer Task Choice dialog
		// (interface 236.3), including its underline name tags.
		java.util.List<String> texts = java.util.Arrays.asList(
				"Slayer Task Choice",
				"<u=ff981f>Turoth",
				"Amount: 80 to 120",
				"+15 Slayer points",
				"<u=ff981f>Gryphons",
				"Amount: 80 to 120",
				"+20 Slayer points",
				"<col=b2b2b2>Complete 50 tasks with Mortimer to unlock a third choice.");
		java.util.List<java.util.Map<String, Object>> offers =
				SlayerMonitor.parseTaskOffers(texts);
		org.junit.Assert.assertEquals(2, offers.size());
		org.junit.Assert.assertEquals("Turoth", offers.get(0).get("name"));
		org.junit.Assert.assertEquals(80, offers.get(0).get("amount_min"));
		org.junit.Assert.assertEquals(120, offers.get(0).get("amount_max"));
		org.junit.Assert.assertEquals(15, offers.get(0).get("modifier_value"));
		org.junit.Assert.assertEquals("Slayer points", offers.get(0).get("modifier_label"));
		org.junit.Assert.assertEquals("Gryphons", offers.get(1).get("name"));
		org.junit.Assert.assertEquals(20, offers.get(1).get("modifier_value"));
		// The locked-third-choice notice must not become an offer.
		org.junit.Assert.assertEquals(2, offers.size());
	}

	@Test
	public void testParseTaskOffersHandlesEveryObservedModifierForm() {
		// Observed in game: XP, superior chance, and quantity modifiers.
		java.util.List<String> texts = java.util.Arrays.asList(
				"<u=ff981f>Turoth", "Amount: 80 to 120", "+30% Slayer XP",
				"<u=ff981f>Gryphons", "Amount: 80 to 120", "+300% Superior Chance",
				"<u=ff981f>Kurask", "Amount: 40 to 60", "+100 Assigned");
		java.util.List<java.util.Map<String, Object>> offers =
				SlayerMonitor.parseTaskOffers(texts);
		org.junit.Assert.assertEquals(3, offers.size());

		org.junit.Assert.assertEquals(30, offers.get(0).get("modifier_value"));
		org.junit.Assert.assertEquals(true, offers.get(0).get("modifier_is_percent"));
		org.junit.Assert.assertEquals("Slayer XP", offers.get(0).get("modifier_label"));

		org.junit.Assert.assertEquals(300, offers.get(1).get("modifier_value"));
		org.junit.Assert.assertEquals(true, offers.get(1).get("modifier_is_percent"));
		org.junit.Assert.assertEquals("Superior Chance", offers.get(1).get("modifier_label"));

		// A quantity modifier: the assigned amount can exceed the shown range.
		org.junit.Assert.assertEquals(100, offers.get(2).get("modifier_value"));
		org.junit.Assert.assertEquals(false, offers.get(2).get("modifier_is_percent"));
		org.junit.Assert.assertEquals("Assigned", offers.get(2).get("modifier_label"));
	}

	@Test
	public void testParseTaskOffersRejectsNonOfferDialogs() {
		java.util.List<String> texts = java.util.Arrays.asList(
				"Select an option", "Yes", "No", "Cancel");
		org.junit.Assert.assertTrue(SlayerMonitor.parseTaskOffers(texts).isEmpty());
	}
}
