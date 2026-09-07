package com.mystix;

import java.util.concurrent.TimeUnit;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ReconcileSchedulerTest {
	private final FakeScheduledExecutorService executor = new FakeScheduledExecutorService();
	private int refreshes;
	private final ReconcileScheduler scheduler = new ReconcileScheduler(executor, () -> refreshes++);

	@Test
	public void requestsDebounceToTheLastOne() {
		scheduler.request(10);
		scheduler.request(10);
		scheduler.request(10);
		assertEquals(1, executor.liveTasks().size());
		executor.runDue(TimeUnit.SECONDS.toMillis(10));
		assertEquals(1, refreshes);
		assertTrue(executor.liveTasks().isEmpty());
	}

	@Test
	public void periodicIsIdempotentAndRepeats() {
		scheduler.startPeriodic();
		scheduler.startPeriodic();
		assertEquals(1, executor.liveTasks().size());
		executor.runDue(TimeUnit.MINUTES.toMillis(ReconcileScheduler.PERIODIC_MINUTES));
		executor.runDue(TimeUnit.MINUTES.toMillis(ReconcileScheduler.PERIODIC_MINUTES * 2));
		assertEquals(2, refreshes);
	}

	@Test
	public void stopCancelsEverything() {
		scheduler.startPeriodic();
		scheduler.request(5);
		scheduler.stop();
		executor.runDue(TimeUnit.HOURS.toMillis(1));
		assertEquals(0, refreshes);
	}

	@Test
	public void busyDefersOnceThenRuns() {
		assertTrue(scheduler.markInFlight());
		scheduler.request(1);
		executor.runDue(TimeUnit.SECONDS.toMillis(1));
		assertEquals(0, refreshes);
		assertEquals(1, executor.liveTasks().size());
		scheduler.clearInFlight();
		executor.runDue(TimeUnit.SECONDS.toMillis(1 + ReconcileScheduler.RETRY_WHEN_BUSY_SECONDS));
		assertEquals(1, refreshes);
	}

	@Test
	public void busyWhilePendingOrInFlight() {
		assertFalse(scheduler.isBusy());
		scheduler.request(10);
		assertTrue(scheduler.isBusy());
		assertTrue(scheduler.markInFlight());
		executor.runDue(TimeUnit.SECONDS.toMillis(10));
		assertTrue(scheduler.isBusy());
		scheduler.clearInFlight();
		executor.runDue(TimeUnit.SECONDS.toMillis(10 + ReconcileScheduler.RETRY_WHEN_BUSY_SECONDS));
		assertFalse(scheduler.isBusy());
	}
}
