package com.mystix;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.Callable;
import java.util.concurrent.Delayed;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Deterministic {@link ScheduledExecutorService} for tests: {@code execute} runs
 * inline, scheduled tasks are recorded with a due time and only run when
 * {@link #runDue(long)} advances the fake clock past them.
 */
public class FakeScheduledExecutorService extends AbstractExecutorService implements ScheduledExecutorService {
	public final class FakeTask implements ScheduledFuture<Object> {
		public final Runnable runnable;
		public long dueMs;
		public final long periodMs;
		public boolean cancelled;
		public int runs;

		FakeTask(Runnable runnable, long dueMs, long periodMs) {
			this.runnable = runnable;
			this.dueMs = dueMs;
			this.periodMs = periodMs;
		}

		@Override
		public long getDelay(TimeUnit unit) {
			return unit.convert(dueMs - nowMs, TimeUnit.MILLISECONDS);
		}

		@Override
		public int compareTo(Delayed o) {
			return Long.compare(getDelay(TimeUnit.MILLISECONDS), o.getDelay(TimeUnit.MILLISECONDS));
		}

		@Override
		public boolean cancel(boolean mayInterruptIfRunning) {
			cancelled = true;
			return true;
		}

		@Override
		public boolean isCancelled() {
			return cancelled;
		}

		@Override
		public boolean isDone() {
			return cancelled || (periodMs <= 0 && runs > 0);
		}

		@Override
		public Object get() {
			return null;
		}

		@Override
		public Object get(long timeout, TimeUnit unit) {
			return null;
		}
	}

	public final List<FakeTask> tasks = new ArrayList<>();
	public long nowMs;
	private boolean shutdown;

	/** Live (not cancelled, not finished) scheduled tasks. */
	public List<FakeTask> liveTasks() {
		List<FakeTask> live = new ArrayList<>();
		for (FakeTask t : tasks) {
			if (!t.isDone()) {
				live.add(t);
			}
		}
		return live;
	}

	/** Advances the clock to {@code toMs} and runs every task due by then, in order. */
	public void runDue(long toMs) {
		nowMs = toMs;
		boolean ran = true;
		while (ran) {
			ran = false;
			for (FakeTask t : new ArrayList<>(tasks)) {
				if (t.isDone() || t.dueMs > nowMs) {
					continue;
				}
				t.runs++;
				if (t.periodMs > 0) {
					t.dueMs += t.periodMs;
				}
				t.runnable.run();
				ran = true;
			}
		}
	}

	@Override
	public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
		FakeTask task = new FakeTask(command, nowMs + unit.toMillis(delay), 0);
		tasks.add(task);
		return task;
	}

	@Override
	public <V> ScheduledFuture<V> schedule(Callable<V> callable, long delay, TimeUnit unit) {
		throw new UnsupportedOperationException();
	}

	@Override
	public ScheduledFuture<?> scheduleAtFixedRate(Runnable command, long initialDelay, long period, TimeUnit unit) {
		FakeTask task = new FakeTask(command, nowMs + unit.toMillis(initialDelay), unit.toMillis(period));
		tasks.add(task);
		return task;
	}

	@Override
	public ScheduledFuture<?> scheduleWithFixedDelay(Runnable command, long initialDelay, long delay, TimeUnit unit) {
		return scheduleAtFixedRate(command, initialDelay, delay, unit);
	}

	@Override
	public void execute(Runnable command) {
		command.run();
	}

	@Override
	public void shutdown() {
		shutdown = true;
	}

	@Override
	public List<Runnable> shutdownNow() {
		shutdown = true;
		return new ArrayList<>();
	}

	@Override
	public boolean isShutdown() {
		return shutdown;
	}

	@Override
	public boolean isTerminated() {
		return shutdown;
	}

	@Override
	public boolean awaitTermination(long timeout, TimeUnit unit) {
		return true;
	}
}
