package com.mystix;

import com.mystix.model.RoadmapSummary;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * The player's last fetched roadmap list, reused by background refreshes so they
 * re-read only the roadmaps themselves. Roadmaps created or deleted elsewhere
 * show up once the list is older than {@link #REUSE_MS} (the periodic refresh
 * always is) or when the panel opens.
 */
final class RoadmapListCache {
	static final long REUSE_MS = TimeUnit.MINUTES.toMillis(5);

	private String player;
	private List<RoadmapSummary> summaries;
	private long fetchedAtMs;

	synchronized void record(String player, List<RoadmapSummary> summaries, long nowMs) {
		this.player = player;
		this.summaries = summaries == null
				? Collections.emptyList()
				: Collections.unmodifiableList(new ArrayList<>(summaries));
		this.fetchedAtMs = nowMs;
	}

	/** The list to reuse for {@code player}, or null when it has to be fetched. */
	synchronized List<RoadmapSummary> reusable(String player, long nowMs) {
		if (summaries == null || this.player == null || !this.player.equals(player)) {
			return null;
		}
		return nowMs - fetchedAtMs < REUSE_MS ? summaries : null;
	}

	/** Forget the list (a roadmap read failed, or the session ended). */
	synchronized void invalidate() {
		summaries = null;
		player = null;
	}
}
