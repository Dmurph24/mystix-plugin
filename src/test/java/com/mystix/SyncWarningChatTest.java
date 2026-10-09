package com.mystix;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import com.mystix.SyncHealth.Status;
import org.junit.Test;

public class SyncWarningChatTest {
	@Test
	public void staysQuietWhileNothingChanges() {
		for (Status status : Status.values()) {
			assertNull(SyncWarningChat.messageFor(status, status));
		}
	}

	@Test
	public void announcesEachProblemOnce() {
		assertEquals(SyncWarningChat.KEY_REJECTED_MESSAGE, SyncWarningChat.messageFor(Status.OK, Status.KEY_REJECTED));
		assertEquals(SyncWarningChat.UNREACHABLE_MESSAGE, SyncWarningChat.messageFor(Status.OK, Status.UNREACHABLE));
		assertEquals(SyncWarningChat.KEY_REJECTED_MESSAGE,
				SyncWarningChat.messageFor(Status.UNREACHABLE, Status.KEY_REJECTED));
	}

	@Test
	public void saysWhenSyncingWorksAgain() {
		assertEquals(SyncWarningChat.RECOVERED_MESSAGE, SyncWarningChat.messageFor(Status.KEY_REJECTED, Status.OK));
		assertEquals(SyncWarningChat.RECOVERED_MESSAGE, SyncWarningChat.messageFor(Status.UNREACHABLE, Status.OK));
	}
}
