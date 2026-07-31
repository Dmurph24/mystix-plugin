package com.mystix;

/**
 * Pure decision logic for <em>when</em> {@link AchievementDiaryMonitor} may read diary state.
 *
 * <p>The client's varplayer array is not reset atomically on login / world-hop /
 * account-switch: the server replays the varp block over several ticks after
 * {@code GameState.LOGGED_IN}. Reading during that window can mirror a prior session's
 * fully-complete diary bits onto the current player, which the append-only backend records
 * permanently. This state machine ensures:
 *
 * <ul>
 *   <li>the first read of a session (the <em>baseline</em>) only happens after a short
 *       settle period, by which time the server's varp block has landed;</li>
 *   <li>the varbit-driven near-real-time resync never fires <em>before</em> that baseline
 *       (the varp replay is itself a flood of {@code VarbitChanged}, which previously
 *       pre-empted the login delay and triggered a read mid-replay);</li>
 *   <li>state resets on leaving {@code LOGGED_IN}, so a prior session's values can never be
 *       adopted as the next account's baseline.</li>
 * </ul>
 *
 * <p>It holds no RuneLite references, so it is unit-testable without a live {@code Client}.
 */
final class DiarySyncTrigger {
	/**
	 * Ticks to wait after login before the first (baseline) read, so the server's varp
	 * replay has landed. ~5 ticks is roughly 3 seconds, matching the previous login delay
	 * while now being enforced against every read path rather than a single scheduled task.
	 */
	static final int LOGIN_SETTLE_TICKS = 5;
	/** Minimum ticks between varbit-driven resyncs once the baseline has been established. */
	static final int RESYNC_THROTTLE_TICKS = 3;

	enum Decision {
		NONE,
		SYNC
	}

	private boolean loggedIn;
	private boolean baselineSynced;
	private int loginTick = -1;
	private boolean resyncPending;
	private int lastReadTick = -1;

	/** A varbit changed; request a resync. Acted on only once the baseline has run. */
	void varbitChanged() {
		resyncPending = true;
	}

	/**
	 * Drive from every {@code GameTick}. Returns {@link Decision#SYNC} when the monitor
	 * should read and sync now, {@link Decision#NONE} otherwise.
	 *
	 * @param currentTick the client tick count
	 * @param loggedInNow whether the client is currently in the LOGGED_IN state
	 */
	Decision tick(int currentTick, boolean loggedInNow) {
		if (!loggedInNow) {
			reset();
			return Decision.NONE;
		}
		if (!loggedIn) {
			// Entered logged-in (also covers a missed GameStateChanged, e.g. the plugin
			// being enabled mid-session): start the settle clock, do not read yet.
			enterSession(currentTick);
			return Decision.NONE;
		}
		if (!baselineSynced) {
			if (currentTick - loginTick >= LOGIN_SETTLE_TICKS) {
				baselineSynced = true;
				resyncPending = false;
				lastReadTick = currentTick;
				return Decision.SYNC;
			}
			return Decision.NONE;
		}
		if (resyncPending && currentTick - lastReadTick >= RESYNC_THROTTLE_TICKS) {
			resyncPending = false;
			lastReadTick = currentTick;
			return Decision.SYNC;
		}
		return Decision.NONE;
	}

	/**
	 * Call on a {@code LOGGED_IN -> not-logged-in} transition. Returns whether to flush the
	 * final state (only when a trustworthy baseline was established this session, so a
	 * logout during the settle window never sends transitional data), then resets.
	 */
	boolean leaveAndShouldFlush() {
		// baselineSynced is only ever set while logged in, so it already implies loggedIn.
		boolean flush = baselineSynced;
		reset();
		return flush;
	}

	/** True once this session's settle period has elapsed and the baseline read has run. */
	boolean isBaselineSynced() {
		return baselineSynced;
	}

	/** Clears all session state; the next logged-in tick starts a fresh settle window. */
	void reset() {
		loggedIn = false;
		baselineSynced = false;
		loginTick = -1;
		resyncPending = false;
		lastReadTick = -1;
	}

	private void enterSession(int currentTick) {
		reset();
		loggedIn = true;
		loginTick = currentTick;
	}
}
