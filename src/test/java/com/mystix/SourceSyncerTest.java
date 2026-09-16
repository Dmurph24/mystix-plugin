package com.mystix;

import com.google.gson.Gson;
import com.mystix.model.BankSyncPayload;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class SourceSyncerTest {
	private FakeScheduledExecutorService executor;
	private List<BankSyncPayload> sent;
	private boolean enabled;
	private SourceSyncer syncer;

	@Before
	public void setUp() {
		executor = new FakeScheduledExecutorService();
		sent = new ArrayList<>();
		enabled = true;
		syncer = new SourceSyncer("fish_barrel", new Gson(), executor, () -> enabled, () -> "Zezima", sent::add);
	}

	private static Map<Integer, Integer> qty(int itemId, int q) {
		Map<Integer, Integer> m = new HashMap<>();
		m.put(itemId, q);
		return m;
	}

	@Test
	public void firstSubmitSendsImmediatelyThenDebouncesBursts() {
		syncer.submit(qty(13439, 1), false);
		assertEquals(1, sent.size());
		// Ten more catches in a burst: one scheduled upload with the last contents.
		for (int i = 2; i <= 11; i++) {
			syncer.submit(qty(13439, i), false);
		}
		assertEquals(1, sent.size());
		assertEquals(1, executor.liveTasks().size());
		executor.runDue(TimeUnit.SECONDS.toMillis(SourceSyncer.DEBOUNCE_SECONDS));
		assertEquals(2, sent.size());
		assertEquals(11, sent.get(1).getItems().get("fish_barrel").get(0).getQuantity());
	}

	@Test
	public void unchangedContentsAreNotResent() {
		syncer.submit(qty(13439, 5), false);
		syncer.submit(qty(13439, 5), false);
		syncer.submit(qty(13439, 5), true);
		assertEquals(1, sent.size());
		assertTrue(executor.liveTasks().isEmpty());
	}

	@Test
	public void immediateSubmitAndFlushSkipTheDebounce() {
		syncer.submit(qty(13439, 5), false);
		syncer.submit(qty(13439, 6), false);
		assertEquals(1, sent.size());
		syncer.submit(new HashMap<>(), true); // emptied at the bank
		assertEquals(2, sent.size());
		assertTrue(sent.get(1).getItems().get("fish_barrel").isEmpty());
		assertTrue(executor.liveTasks().isEmpty());

		syncer.submit(qty(13439, 2), false);
		assertEquals(2, sent.size());
		syncer.flushPending(); // logout
		assertEquals(3, sent.size());
	}

	@Test
	public void gatedOffSendsNothing() {
		enabled = false;
		syncer.submit(qty(13439, 5), true);
		assertTrue(sent.isEmpty());
	}
}
