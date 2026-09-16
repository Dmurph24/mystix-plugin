package com.mystix;

import com.google.gson.Gson;
import com.mystix.model.BankSyncPayload;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SourceSyncerTest {
	private static final long GOAL_MS = TimeUnit.SECONDS.toMillis(SourceSyncer.GOAL_DEBOUNCE_SECONDS);
	private static final long DEFAULT_MS = TimeUnit.SECONDS.toMillis(SourceSyncer.DEFAULT_DEBOUNCE_SECONDS);

	private FakeScheduledExecutorService executor;
	private List<BankSyncPayload> sent;
	private boolean enabled;
	private long now;
	private Set<Integer> goalItems;
	private SourceSyncer syncer;

	@Before
	public void setUp() {
		executor = new FakeScheduledExecutorService();
		sent = new ArrayList<>();
		enabled = true;
		now = 0;
		goalItems = new HashSet<>();
		syncer = new SourceSyncer("fish_barrel", new Gson(), executor, () -> now, () -> enabled, () -> "Zezima", sent::add);
		syncer.setGoalItems(() -> goalItems);
	}

	private static Map<Integer, Integer> qty(int itemId, int q) {
		Map<Integer, Integer> m = new HashMap<>();
		m.put(itemId, q);
		return m;
	}

	private void advance(long ms) {
		now += ms;
		executor.runDue(now);
	}

	private int quantity(BankSyncPayload p, String source, int itemId) {
		for (BankSyncPayload.BankItem i : p.getItems().get(source)) {
			if (i.getItemId() == itemId) {
				return i.getQuantity();
			}
		}
		return 0;
	}

	@Test
	public void firstContentsSendAtOnceThenRoutineChangesWaitThreeMinutes() {
		syncer.submit(qty(13439, 1), false);
		assertEquals(1, sent.size());
		for (int i = 2; i <= 11; i++) {
			syncer.submit(qty(13439, i), false);
		}
		assertEquals(1, sent.size());
		advance(GOAL_MS);
		assertEquals("not a goal item: still waiting", 1, sent.size());
		advance(DEFAULT_MS - GOAL_MS);
		assertEquals(2, sent.size());
		assertEquals(11, quantity(sent.get(1), "fish_barrel", 13439));
	}

	@Test
	public void changesToAGoalItemUseTheShortDebounce() {
		goalItems.add(13439);
		syncer.submit(qty(13439, 1), false);
		syncer.submit(qty(13439, 2), false);
		advance(GOAL_MS);
		assertEquals(2, sent.size());
		// A later non-goal change never pushes a running goal wait out.
		Map<Integer, Integer> both = qty(13439, 3);
		syncer.submit(both, false);
		advance(GOAL_MS / 2);
		Map<Integer, Integer> plusShark = new HashMap<>(both);
		plusShark.put(383, 1);
		syncer.submit(plusShark, false);
		advance(GOAL_MS / 2);
		assertEquals(3, sent.size());
		assertEquals(1, quantity(sent.get(2), "fish_barrel", 383));
	}

	@Test
	public void unchangedContentsAreNotResent() {
		syncer.submit(qty(13439, 5), false);
		syncer.submit(qty(13439, 5), false);
		syncer.submit(qty(13439, 5), true);
		assertEquals(1, sent.size());
		assertTrue(executor.liveTasks().isEmpty());
		// A change that reverts before the debounce fires sends nothing.
		syncer.submit(qty(13439, 6), false);
		syncer.submit(qty(13439, 5), false);
		advance(DEFAULT_MS);
		assertEquals(1, sent.size());
	}

	@Test
	public void immediateSubmitAndFlushSkipTheWait() {
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
		assertTrue(executor.liveTasks().isEmpty());
	}

	@Test
	public void multipleSourcesMergeIntoOnePendingUpload() {
		SourceSyncer bank = new SourceSyncer("bank memory", new Gson(), executor, () -> now, () -> enabled, () -> "Zezima", sent::add);
		bank.setGoalItems(() -> goalItems);
		Map<String, Map<Integer, Integer>> first = new HashMap<>();
		first.put("inventory", qty(1511, 5));
		bank.submitSources(first, false);
		assertEquals(1, sent.size());
		// The bank opens later: a source never sent goes up at once, with the inventory as it is.
		Map<String, Map<Integer, Integer>> withBank = new HashMap<>();
		withBank.put("bank", qty(1511, 100));
		withBank.put("inventory", qty(1511, 5));
		bank.submitSources(withBank, false);
		assertEquals(2, sent.size());
		assertEquals(100, quantity(sent.get(1), "bank", 1511));
		// Routine changes to both sources collapse into one request.
		Map<String, Map<Integer, Integer>> later = new HashMap<>();
		later.put("bank", qty(1511, 90));
		later.put("inventory", qty(1511, 15));
		bank.submitSources(later, false);
		Map<String, Map<Integer, Integer>> invOnly = new HashMap<>();
		invOnly.put("inventory", qty(1511, 16));
		bank.submitSources(invOnly, false);
		advance(DEFAULT_MS);
		assertEquals(3, sent.size());
		assertEquals(90, quantity(sent.get(2), "bank", 1511));
		assertEquals(16, quantity(sent.get(2), "inventory", 1511));
	}

	@Test
	public void invalidateForcesTheNextSubmitAndGatedOffSendsNothing() {
		syncer.submit(qty(13439, 5), false);
		syncer.invalidate();
		syncer.submit(qty(13439, 5), true);
		assertEquals(2, sent.size());
		enabled = false;
		syncer.submit(qty(13439, 9), true);
		assertEquals(2, sent.size());
		assertFalse(enabled);
	}
}
