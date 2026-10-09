package com.mystix;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Whether the plugin's requests to Mystix are getting through, for the in-game
 * sync warning.
 *
 * <p>A rejected App Key is reported at once: one rejection means nothing will
 * sync until the key is replaced. Network errors and server errors only count
 * once they have gone on for {@link #UNREACHABLE_AFTER} with no request getting
 * through, so a brief blip never flashes the warning. Any accepted request
 * clears both.
 */
@Singleton
public class SyncHealth {
	public enum Status {
		OK,
		KEY_REJECTED,
		UNREACHABLE
	}

	static final Duration UNREACHABLE_AFTER = Duration.ofMinutes(2);
	static final int UNREACHABLE_MIN_FAILURES = 3;

	private final Clock clock;
	private boolean keyRejected;
	private Instant failingSince;
	private int failures;

	@Inject
	public SyncHealth() {
		this(Clock.systemUTC());
	}

	SyncHealth(Clock clock) {
		this.clock = clock;
	}

	/** The server answered and accepted the App Key. */
	public synchronized void onAccepted() {
		keyRejected = false;
		failingSince = null;
		failures = 0;
	}

	/** The server answered 401 / 403: the App Key is wrong or was regenerated. */
	public synchronized void onKeyRejected() {
		keyRejected = true;
		failingSince = null;
		failures = 0;
	}

	/** The request never got a usable answer: a network error or a 5xx. */
	public synchronized void onUnreachable() {
		if (failingSince == null) {
			failingSince = clock.instant();
		}
		failures++;
	}

	/** Forget everything, e.g. when the App Key is edited. */
	public synchronized void reset() {
		keyRejected = false;
		failingSince = null;
		failures = 0;
	}

	public synchronized Status status() {
		if (keyRejected) {
			return Status.KEY_REJECTED;
		}
		if (failingSince != null
				&& failures >= UNREACHABLE_MIN_FAILURES
				&& !clock.instant().isBefore(failingSince.plus(UNREACHABLE_AFTER))) {
			return Status.UNREACHABLE;
		}
		return Status.OK;
	}

	/** Maps an HTTP status code from Mystix onto this tracker. */
	public void onResponseCode(int code) {
		if (code == 401 || code == 403) {
			onKeyRejected();
		} else if (code >= 500) {
			onUnreachable();
		} else {
			// Any other answer (including a 4xx about the payload) means the
			// server is up and authenticated the key before handling it.
			onAccepted();
		}
	}
}
