package com.mystix;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;

/**
 * Coalesces roadmap re-reads: a periodic refresh plus debounced on-demand
 * requests. Several requests inside the delay window collapse into one read that
 * fires after the <em>last</em> request, so a burst of uploads produces a single
 * fetch once they have all landed. A read already in flight defers the next one
 * briefly instead of stacking requests, and reads are at least
 * {@link #MIN_INTERVAL_SECONDS} apart: a player uploading every few seconds
 * gets one re-read a minute, not one per upload.
 */
final class ReconcileScheduler {
	static final int PERIODIC_MINUTES = 10;
	static final int RETRY_WHEN_BUSY_SECONDS = 5;
	static final int MIN_INTERVAL_SECONDS = 60;
	/** A pending read closer than this counts as "syncing" for the UI hint. */
	static final int SYNCING_HINT_SECONDS = 15;

	private final ScheduledExecutorService executor;
	private final Runnable refresh;
	private final LongSupplier clockMs;
	private final AtomicBoolean inFlight = new AtomicBoolean();

	private ScheduledFuture<?> periodic;
	private ScheduledFuture<?> pending;
	private boolean busyRetryUsed;
	private long lastFireMs = Long.MIN_VALUE;
	private long pendingDueMs;

	ReconcileScheduler(ScheduledExecutorService executor, Runnable refresh) {
		this(executor, refresh, System::currentTimeMillis);
	}

	ReconcileScheduler(ScheduledExecutorService executor, Runnable refresh, LongSupplier clockMs) {
		this.executor = executor;
		this.refresh = refresh;
		this.clockMs = clockMs;
	}

	/** Re-read after {@code delaySeconds}, or once the minimum interval since
	 * the last read has passed if that is later; replaces any pending request. */
	synchronized void request(int delaySeconds) {
		if (pending != null) {
			pending.cancel(false);
		}
		long delayMs = TimeUnit.SECONDS.toMillis(Math.max(0, delaySeconds));
		if (lastFireMs != Long.MIN_VALUE) {
			long earliestMs = lastFireMs + TimeUnit.SECONDS.toMillis(MIN_INTERVAL_SECONDS);
			delayMs = Math.max(delayMs, earliestMs - clockMs.getAsLong());
		}
		pendingDueMs = clockMs.getAsLong() + delayMs;
		pending = executor.schedule(this::firePending, delayMs, TimeUnit.MILLISECONDS);
	}

	/** Starts the periodic refresh; safe to call more than once. */
	synchronized void startPeriodic() {
		if (periodic == null) {
			periodic = executor.scheduleAtFixedRate(
					this::firePeriodic, PERIODIC_MINUTES, PERIODIC_MINUTES, TimeUnit.MINUTES);
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
		lastFireMs = Long.MIN_VALUE;
	}

	/** True while a re-read is scheduled or running. */
	synchronized boolean isBusy() {
		return pending != null || inFlight.get();
	}

	/** True while a re-read is running or due within {@link #SYNCING_HINT_SECONDS}.
	 * Drives the "Syncing" hint: a read held back by the minimum interval
	 * would otherwise show it for up to a minute. */
	synchronized boolean isSyncingSoon() {
		if (inFlight.get()) {
			return true;
		}
		return pending != null
				&& pendingDueMs - clockMs.getAsLong() <= TimeUnit.SECONDS.toMillis(SYNCING_HINT_SECONDS);
	}

	/** Claims the in-flight slot; false when a read is already running. */
	boolean markInFlight() {
		return inFlight.compareAndSet(false, true);
	}

	void clearInFlight() {
		inFlight.set(false);
	}

	private void firePending() {
		synchronized (this) {
			pending = null;
		}
		fire();
	}

	/** The periodic read also answers any request still waiting, so drop it
	 * (left scheduled, a later request could no longer cancel it). */
	private void firePeriodic() {
		synchronized (this) {
			if (pending != null) {
				pending.cancel(false);
				pending = null;
			}
		}
		fire();
	}

	private void fire() {
		synchronized (this) {
			if (inFlight.get()) {
				// Let the running read finish; try once more shortly after.
				if (!busyRetryUsed) {
					busyRetryUsed = true;
					request(RETRY_WHEN_BUSY_SECONDS);
				}
				return;
			}
			busyRetryUsed = false;
			lastFireMs = clockMs.getAsLong();
		}
		refresh.run();
	}
}
