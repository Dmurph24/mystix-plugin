package com.mystix;

import com.google.gson.Gson;
import com.mystix.model.RoadmapSummary;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class RoadmapListCacheTest {
	private static final List<RoadmapSummary> ONE = Collections.singletonList(
			new Gson().fromJson("{\"collection_id\":7,\"title\":\"Maxing\",\"goal_count\":3}", RoadmapSummary.class));

	private final RoadmapListCache cache = new RoadmapListCache();

	@Test
	public void emptyUntilRecorded() {
		assertNull(cache.reusable("Zezima", 0));
	}

	@Test
	public void reusedForTheSamePlayerWhileRecent() {
		cache.record("Zezima", ONE, 1_000);
		assertEquals(ONE, cache.reusable("Zezima", 1_000 + RoadmapListCache.REUSE_MS - 1));
		assertNull(cache.reusable("Zezima", 1_000 + RoadmapListCache.REUSE_MS));
	}

	@Test
	public void neverReusedForAnotherPlayer() {
		cache.record("Zezima", ONE, 0);
		assertNull(cache.reusable("Lynx Titan", 1));
	}

	@Test
	public void invalidateForcesAFetch() {
		cache.record("Zezima", ONE, 0);
		cache.invalidate();
		assertNull(cache.reusable("Zezima", 1));
	}

	@Test
	public void anEmptyListIsReusedToo() {
		cache.record("Zezima", Collections.emptyList(), 0);
		assertEquals(Collections.emptyList(), cache.reusable("Zezima", 1));
	}
}
