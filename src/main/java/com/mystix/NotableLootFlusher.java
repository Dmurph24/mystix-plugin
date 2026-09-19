package com.mystix;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import net.runelite.api.gameval.ItemID;

/**
 * Decides whether a loot stack is worth uploading right away, and rate-limits the
 * early uploads it triggers so a burst of notable drops costs at most one extra
 * request per {@link #MIN_INTERVAL_MS}. The regular timed flush is untouched.
 */
class NotableLootFlusher
{
	static final long NOTABLE_STACK_VALUE = 100_000L;
	static final long MIN_INTERVAL_MS = 10_000L;

	private final ScheduledExecutorService executorService;
	private final Runnable flush;
	private final LongSupplier clockMs;

	private ScheduledFuture<?> pending;
	private long lastFlushMs;
	private boolean hasFlushed;

	NotableLootFlusher(ScheduledExecutorService executorService, Runnable flush)
	{
		this(executorService, flush, () -> TimeUnit.NANOSECONDS.toMillis(System.nanoTime()));
	}

	NotableLootFlusher(ScheduledExecutorService executorService, Runnable flush, LongSupplier clockMs)
	{
		this.executorService = executorService;
		this.flush = flush;
		this.clockMs = clockMs;
	}

	/**
	 * A stack is notable when it is worth at least {@link #NOTABLE_STACK_VALUE}, or is a
	 * single untradeable item (pets, collection log uniques). Untradeable stacks such as
	 * currencies and shards drop constantly and are not notable.
	 */
	static boolean isNotable(int itemId, int quantity, int unitPrice, boolean tradeable)
	{
		if (quantity <= 0 || itemId == ItemID.COINS)
		{
			return false;
		}
		if ((long) unitPrice * quantity >= NOTABLE_STACK_VALUE)
		{
			return true;
		}
		return !tradeable && quantity == 1;
	}

	/**
	 * Flushes now, or as soon as the minimum interval since the last early flush has
	 * passed. Requests made while a flush is already pending are absorbed by it.
	 */
	synchronized void request()
	{
		if (pending != null && !pending.isDone())
		{
			return;
		}
		long delayMs = hasFlushed ? Math.max(0L, lastFlushMs + MIN_INTERVAL_MS - clockMs.getAsLong()) : 0L;
		pending = executorService.schedule(this::run, delayMs, TimeUnit.MILLISECONDS);
	}

	synchronized void cancel()
	{
		if (pending != null)
		{
			pending.cancel(false);
			pending = null;
		}
	}

	private void run()
	{
		synchronized (this)
		{
			lastFlushMs = clockMs.getAsLong();
			hasFlushed = true;
		}
		flush.run();
	}
}
