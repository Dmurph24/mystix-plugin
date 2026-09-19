package com.mystix;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import net.runelite.api.gameval.ItemID;
import org.junit.Before;
import org.junit.Test;

public class NotableLootFlusherTest {
	private static final int SOME_ITEM = 4151;

	private FakeScheduledExecutorService executor;
	private NotableLootFlusher flusher;
	private int flushes;

	@Before
	public void setUp() {
		executor = new FakeScheduledExecutorService();
		flushes = 0;
		flusher = new NotableLootFlusher(executor, () -> flushes++, () -> executor.nowMs);
	}

	@Test
	public void testStackAtThresholdIsNotable() {
		assertTrue(NotableLootFlusher.isNotable(SOME_ITEM, 1, 100_000, true));
		assertTrue(NotableLootFlusher.isNotable(SOME_ITEM, 500, 200, true));
	}

	@Test
	public void testCheapTradeableStackIsNotNotable() {
		assertFalse(NotableLootFlusher.isNotable(SOME_ITEM, 1, 99_999, true));
		assertFalse(NotableLootFlusher.isNotable(SOME_ITEM, 499, 200, true));
	}

	@Test
	public void testStackValueDoesNotOverflow() {
		assertTrue(NotableLootFlusher.isNotable(SOME_ITEM, Integer.MAX_VALUE, Integer.MAX_VALUE, true));
	}

	@Test
	public void testSingleUntradeableIsNotable() {
		assertTrue(NotableLootFlusher.isNotable(SOME_ITEM, 1, 0, false));
	}

	@Test
	public void testUntradeableStackIsNotNotable() {
		assertFalse(NotableLootFlusher.isNotable(SOME_ITEM, 250, 0, false));
	}

	@Test
	public void testCoinsAreNeverNotable() {
		assertFalse(NotableLootFlusher.isNotable(ItemID.COINS, 1, 0, false));
		assertFalse(NotableLootFlusher.isNotable(ItemID.COINS, 5_000_000, 1, false));
	}

	@Test
	public void testFirstRequestFlushesImmediately() {
		flusher.request();
		executor.runDue(0);
		assertEquals(1, flushes);
	}

	@Test
	public void testBurstWithinIntervalCausesOneTrailingFlush() {
		flusher.request();
		executor.runDue(0);

		executor.runDue(2_000);
		flusher.request();
		flusher.request();
		executor.runDue(4_000);
		flusher.request();
		executor.runDue(NotableLootFlusher.MIN_INTERVAL_MS - 1);
		assertEquals(1, flushes);

		executor.runDue(NotableLootFlusher.MIN_INTERVAL_MS);
		assertEquals(2, flushes);
		assertTrue(executor.liveTasks().isEmpty());
	}

	@Test
	public void testRequestAfterIntervalFlushesImmediately() {
		flusher.request();
		executor.runDue(0);

		executor.runDue(NotableLootFlusher.MIN_INTERVAL_MS + 5_000);
		flusher.request();
		executor.runDue(executor.nowMs);
		assertEquals(2, flushes);
	}

	@Test
	public void testCancelDropsPendingFlush() {
		flusher.request();
		executor.runDue(0);
		executor.runDue(1_000);
		flusher.request();

		flusher.cancel();
		executor.runDue(60_000);
		assertEquals(1, flushes);
	}
}
