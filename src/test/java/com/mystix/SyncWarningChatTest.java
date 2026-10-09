package com.mystix;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import com.mystix.SyncHealth.Status;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.Before;
import org.junit.Test;

public class SyncWarningChatTest {
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
	private SyncWarningChat chat;

	@Before
	public void setUp() {
		clock = new MutableClock();
		chat = new SyncWarningChat(null, null, null, clock);
	}

	@Test
	public void staysQuietWhileNothingChanges() {
		assertNull(chat.nextMessage(Status.OK));
		assertEquals(SyncWarningChat.KEY_REJECTED_MESSAGE, chat.nextMessage(Status.KEY_REJECTED));
		assertNull(chat.nextMessage(Status.KEY_REJECTED));
	}

	@Test
	public void announcesAnOutageAndItsEnd() {
		assertEquals(SyncWarningChat.UNREACHABLE_MESSAGE, chat.nextMessage(Status.UNREACHABLE));
		assertNull(chat.nextMessage(Status.UNREACHABLE));
		assertEquals(SyncWarningChat.RECOVERED_MESSAGE, chat.nextMessage(Status.OK));
	}

	@Test
	public void flakyConnectionIsAnnouncedOncePerCooldown() {
		assertEquals(SyncWarningChat.UNREACHABLE_MESSAGE, chat.nextMessage(Status.UNREACHABLE));
		assertEquals(SyncWarningChat.RECOVERED_MESSAGE, chat.nextMessage(Status.OK));

		// Drops out again 3 minutes later: held back, and so is its recovery.
		clock.advance(Duration.ofMinutes(3));
		assertNull(chat.nextMessage(Status.UNREACHABLE));
		assertNull(chat.nextMessage(Status.OK));

		// Once the cooldown is over the next outage is announced again.
		clock.advance(SyncWarningChat.OUTAGE_COOLDOWN);
		assertEquals(SyncWarningChat.UNREACHABLE_MESSAGE, chat.nextMessage(Status.UNREACHABLE));
	}

	@Test
	public void outageOutlastingTheCooldownIsAnnounced() {
		assertEquals(SyncWarningChat.UNREACHABLE_MESSAGE, chat.nextMessage(Status.UNREACHABLE));
		assertEquals(SyncWarningChat.RECOVERED_MESSAGE, chat.nextMessage(Status.OK));
		clock.advance(Duration.ofMinutes(2));
		assertNull(chat.nextMessage(Status.UNREACHABLE));

		clock.advance(SyncWarningChat.OUTAGE_COOLDOWN);
		assertEquals(SyncWarningChat.UNREACHABLE_MESSAGE, chat.nextMessage(Status.UNREACHABLE));
	}

	@Test
	public void cooldownSurvivesRelogButKeyWarningRepeats() {
		assertEquals(SyncWarningChat.UNREACHABLE_MESSAGE, chat.nextMessage(Status.UNREACHABLE));
		chat.reset();
		assertNull(chat.nextMessage(Status.UNREACHABLE));

		assertEquals(SyncWarningChat.KEY_REJECTED_MESSAGE, chat.nextMessage(Status.KEY_REJECTED));
		chat.reset();
		assertEquals(SyncWarningChat.KEY_REJECTED_MESSAGE, chat.nextMessage(Status.KEY_REJECTED));
	}

	@Test
	public void keyRejectionIsNotHeldByTheOutageCooldown() {
		assertEquals(SyncWarningChat.UNREACHABLE_MESSAGE, chat.nextMessage(Status.UNREACHABLE));
		assertEquals(SyncWarningChat.KEY_REJECTED_MESSAGE, chat.nextMessage(Status.KEY_REJECTED));
	}
}
