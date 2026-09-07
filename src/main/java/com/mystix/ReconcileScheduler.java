package com.mystix;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Coalesces roadmap re-reads: a periodic refresh plus debounced on-demand
 * requests. Several requests inside the delay window collapse into one read that
 * fires after the <em>last</em> request, so a burst of uploads produces a single
 * fetch once they have all landed. A read already in flight defers the next one
 * briefly instead of stacking requests.
 */
final class ReconcileScheduler {
	static final int PERIODIC_MINUTES = 10;
	static final int RETRY_WHEN_BUSY_SECONDS = 5;

	private final ScheduledExecutorService executor;
	private final Runnable refresh;
	private final AtomicBoolean inFlight = new AtomicBoolean();

	private ScheduledFuture<?> periodic;
	private ScheduledFuture<?> pending;
	private boolean busyRetryUsed;

	ReconcileScheduler(ScheduledExecutorService executor, Runnable refresh) {
		this.executor = executor;
		this.refresh = refresh;
	}

	/** Re-read after {@code delaySeconds}; replaces any earlier pending request. */
	synchronized void request(int delaySeconds) {
		if (pending != null) {
			pending.cancel(false);
		}
		pending = executor.schedule(this::fire, Math.max(0, delaySeconds), TimeUnit.SECONDS);
	}

	/** Starts the periodic refresh; safe to call more than once. */
	synchronized void startPeriodic() {
		if (periodic == null) {
			periodic = executor.scheduleAtFixedRate(
					this::fire, PERIODIC_MINUTES, PERIODIC_MINUTES, TimeUnit.MINUTES);
		}
	}

	synchronized void stop() {
		if (periodic != null) {
			periodic.cancel(false);
			periodic = null;
		}
		if (pending != null) {
			pending.cancel(false);
			pending = null;
		}
		inFlight.set(false);
		busyRetryUsed = false;
	}

	/** True while a re-read is scheduled or running. */
	synchronized boolean isBusy() {
		return pending != null || inFlight.get();
	}

	/** Claims the in-flight slot; false when a read is already running. */
	boolean markInFlight() {
		return inFlight.compareAndSet(false, true);
	}

	void clearInFlight() {
		inFlight.set(false);
	}

	private void fire() {
		synchronized (this) {
			pending = null;
			if (inFlight.get()) {
				// Let the running read finish; try once more shortly after.
				if (!busyRetryUsed) {
					busyRetryUsed = true;
					request(RETRY_WHEN_BUSY_SECONDS);
				}
				return;
			}
			busyRetryUsed = false;
		}
		refresh.run();
	}
}
