package com.mystix;

import java.util.HashMap;
import java.util.Map;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class BankDepositLedgerTest {
	private static Map<Integer, Long> qty(int id, long q) {
		Map<Integer, Long> m = new HashMap<>();
		m.put(id, q);
		return m;
	}

	@Test
	public void itemsRemovedInADepositContextAreBankedUntilTheBankIsRead() {
		BankDepositLedger l = new BankDepositLedger(Map::of);
		// Dropping a fish is not a deposit.
		assertFalse(l.onItemsRemoved(qty(13439, 1), 10));
		// With the deposit box interface open every removal counts.
		l.onDepositInterface(true);
		assertTrue(l.onItemsRemoved(qty(13439, 12), 21));
		assertTrue(l.onItemsRemoved(qty(13439, 3), 40));
		l.onDepositInterface(false);
		assertEquals(Long.valueOf(15), l.contents().get(13439));
		assertFalse(l.onItemsRemoved(qty(13439, 1), 44));
		// Reading the bank proper folds them in.
		assertTrue(l.onBankRead());
		assertTrue(l.contents().isEmpty());
		assertFalse(l.onBankRead());
	}

	@Test
	public void firstDepositOfASessionBuildsOnTheServersFigure() {
		Map<Integer, Long> server = qty(13439, 5);
		BankDepositLedger l = new BankDepositLedger(() -> server);
		l.onDepositInterface(true);
		l.onItemsRemoved(qty(13439, 2), 1);
		assertEquals(Long.valueOf(7), l.contents().get(13439));
		// A bank read supersedes the server's figure; later deposits start from zero.
		l.onBankRead();
		l.onItemsRemoved(qty(13439, 1), 50);
		assertEquals(Long.valueOf(1), l.contents().get(13439));
		// Next session seeds again.
		l.resetSession();
		server.put(13439, 9L);
		l.onDepositInterface(true);
		l.onItemsRemoved(qty(13439, 1), 100);
		assertEquals(Long.valueOf(10), l.contents().get(13439));
	}

	@Test
	public void aContainerEmptiedIntoADepositBoxCountsAsDeposited() {
		BankDepositLedger l = new BankDepositLedger(Map::of);
		l.onContainerEmptied(qty(13439, 28));
		assertEquals(Long.valueOf(28), l.contents().get(13439));
		l.onBankRead();
		assertTrue(l.contents().isEmpty());
	}

	@Test
	public void maxCashStacksDepositedTogetherDoNotOverflow() {
		// The server's deposit overlay already holds a max-cash stack, another is
		// deposited and a cargo hold of coins is banked: 2 x (2^31-1) + 705,032,706 = 5B.
		BankDepositLedger l = new BankDepositLedger(() -> qty(995, Integer.MAX_VALUE));
		l.onDepositInterface(true);
		assertTrue(l.onItemsRemoved(qty(995, Integer.MAX_VALUE), 1));
		l.onContainerEmptied(qty(995, 705_032_706L));
		assertEquals(Long.valueOf(5_000_000_000L), l.contents().get(995));
		// A third max-cash stack keeps counting past 5B.
		assertTrue(l.onItemsRemoved(qty(995, Integer.MAX_VALUE), 2));
		assertEquals(Long.valueOf(7_147_483_647L), l.contents().get(995));
	}
}
