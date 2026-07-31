package com.mystix;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.mystix.DiarySyncTrigger.Decision;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests the read-timing state machine that fixes the "all diaries complete" false read: the
 * baseline read waits for the post-login settle period, the varbit replay flood cannot
 * pre-empt it, and account switches re-baseline without carrying a prior session forward.
 */
public class DiarySyncTriggerTest {
	private static final boolean IN = true;
	private static final boolean OUT = false;

	private DiarySyncTrigger trigger;

	@Before
	public void setUp() {
		trigger = new DiarySyncTrigger();
	}

	@Test
	public void testBaselineWaitsForSettleAndVarbitFloodDoesNotPreempt() {
		// Enter logged-in at tick 100; the entering tick never reads.
		assertEquals(Decision.NONE, trigger.tick(100, IN));

		// Server varp replay flood during the settle window must not trigger a read — this
		// is exactly the path that produced the bug.
		for (int t = 101; t < 100 + DiarySyncTrigger.LOGIN_SETTLE_TICKS; t++) {
			trigger.varbitChanged();
			assertEquals("no read during settle window at tick " + t, Decision.NONE, trigger.tick(t, IN));
		}

		// Once settled, exactly one baseline sync, and no immediate duplicate.
		int settleTick = 100 + DiarySyncTrigger.LOGIN_SETTLE_TICKS;
		assertEquals(Decision.SYNC, trigger.tick(settleTick, IN));
		assertEquals(Decision.NONE, trigger.tick(settleTick + 1, IN));
	}

	@Test
	public void testResyncAfterBaselineIsThrottled() {
		driveToBaseline(0);
		int base = DiarySyncTrigger.LOGIN_SETTLE_TICKS; // baseline synced at this tick

		trigger.varbitChanged();
		for (int t = base + 1; t < base + DiarySyncTrigger.RESYNC_THROTTLE_TICKS; t++) {
			assertEquals(Decision.NONE, trigger.tick(t, IN));
		}
		assertEquals(Decision.SYNC, trigger.tick(base + DiarySyncTrigger.RESYNC_THROTTLE_TICKS, IN));
	}

	@Test
	public void testAccountSwitchReBaselinesWithoutCarryOver() {
		driveToBaseline(0);

		// Log out: a baseline existed, so the final state is flushed and the session resets.
		assertTrue(trigger.leaveAndShouldFlush());

		// A different account logs in; its varp replay flood must not pre-empt the new
		// baseline (this is the account-switch carry-over the fix prevents).
		assertEquals(Decision.NONE, trigger.tick(1000, IN));
		for (int t = 1001; t < 1000 + DiarySyncTrigger.LOGIN_SETTLE_TICKS; t++) {
			trigger.varbitChanged();
			assertEquals(Decision.NONE, trigger.tick(t, IN));
		}
		assertEquals(Decision.SYNC, trigger.tick(1000 + DiarySyncTrigger.LOGIN_SETTLE_TICKS, IN));
	}

	@Test
	public void testLogoutBeforeBaselineDoesNotFlush() {
		assertEquals(Decision.NONE, trigger.tick(100, IN)); // enter; settle not reached
		assertFalse(trigger.leaveAndShouldFlush());
	}

	@Test
	public void testNotLoggedInNeverSyncs() {
		assertEquals(Decision.NONE, trigger.tick(1, OUT));
		trigger.varbitChanged();
		assertEquals(Decision.NONE, trigger.tick(2, OUT));
		assertFalse(trigger.isBaselineSynced());
	}

	@Test
	public void testResetClearsSession() {
		driveToBaseline(0);
		assertTrue(trigger.isBaselineSynced());
		trigger.reset();
		assertFalse(trigger.isBaselineSynced());
		// After reset a fresh login must settle again before reading.
		assertEquals(Decision.NONE, trigger.tick(500, IN));
		assertEquals(Decision.SYNC, trigger.tick(500 + DiarySyncTrigger.LOGIN_SETTLE_TICKS, IN));
	}

	private void driveToBaseline(int loginTick) {
		assertEquals(Decision.NONE, trigger.tick(loginTick, IN));
		assertEquals(Decision.SYNC, trigger.tick(loginTick + DiarySyncTrigger.LOGIN_SETTLE_TICKS, IN));
	}
}
