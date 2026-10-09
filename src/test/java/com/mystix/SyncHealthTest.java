package com.mystix;

import static org.junit.Assert.assertEquals;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.Before;
import org.junit.Test;

public class SyncHealthTest {
	/** A clock the test moves by hand. */
	private static final class MutableClock extends Clock {
		private Instant now = Instant.parse("2026-10-09T12:00:00Z");

		void advance(Duration by) {
			now = now.plus(by);
		}

		@Override
		public ZoneId getZone() {
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(ZoneId zone) {
			return this;
		}

		@Override
		public Instant instant() {
			return now;
		}
	}

	private MutableClock clock;
	private SyncHealth health;

	@Before
	public void setUp() {
		clock = new MutableClock();
		health = new SyncHealth(clock);
	}

	@Test
	public void startsOk() {
		assertEquals(SyncHealth.Status.OK, health.status());
	}

	@Test
	public void rejectedKeyWarnsAtOnce() {
		for (int code : new int[] {401, 403}) {
			health.reset();
			health.onResponseCode(code);
			assertEquals(SyncHealth.Status.KEY_REJECTED, health.status());
		}
	}

	@Test
	public void acceptedRequestClearsRejectedKey() {
		health.onResponseCode(403);
		health.onResponseCode(200);
		assertEquals(SyncHealth.Status.OK, health.status());
	}

	@Test
	public void payloadErrorsStillMeanTheKeyWorks() {
		health.onResponseCode(403);
		health.onResponseCode(400);
		assertEquals(SyncHealth.Status.OK, health.status());
	}

	@Test
	public void briefOutageDoesNotWarn() {
		for (int i = 0; i < 5; i++) {
			health.onUnreachable();
		}
		clock.advance(SyncHealth.UNREACHABLE_AFTER.minusSeconds(1));
		assertEquals(SyncHealth.Status.OK, health.status());
	}

	@Test
	public void lastingOutageWarns() {
		health.onUnreachable();
		health.onResponseCode(502);
		clock.advance(SyncHealth.UNREACHABLE_AFTER);
		health.onResponseCode(503);
		assertEquals(SyncHealth.Status.UNREACHABLE, health.status());
	}

	@Test
	public void singleOldFailureDoesNotWarn() {
		health.onUnreachable();
		clock.advance(Duration.ofMinutes(30));
		assertEquals(SyncHealth.Status.OK, health.status());
	}

	@Test
	public void anyAnswerEndsTheOutage() {
		for (int i = 0; i < SyncHealth.UNREACHABLE_MIN_FAILURES; i++) {
			health.onUnreachable();
		}
		clock.advance(SyncHealth.UNREACHABLE_AFTER);
		health.onResponseCode(200);
		assertEquals(SyncHealth.Status.OK, health.status());

		// The next outage starts its own timer.
		for (int i = 0; i < SyncHealth.UNREACHABLE_MIN_FAILURES; i++) {
			health.onUnreachable();
		}
		assertEquals(SyncHealth.Status.OK, health.status());
	}

	@Test
	public void rejectedKeyOutranksOutage() {
		for (int i = 0; i < SyncHealth.UNREACHABLE_MIN_FAILURES; i++) {
			health.onUnreachable();
		}
		clock.advance(SyncHealth.UNREACHABLE_AFTER);
		health.onResponseCode(403);
		assertEquals(SyncHealth.Status.KEY_REJECTED, health.status());
	}

	@Test
	public void resetClearsEverything() {
		health.onResponseCode(403);
		health.reset();
		assertEquals(SyncHealth.Status.OK, health.status());
	}
}
