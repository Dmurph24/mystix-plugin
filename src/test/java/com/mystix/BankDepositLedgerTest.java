package com.mystix;

import java.util.HashMap;
import java.util.Map;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class BankDepositLedgerTest {
	private static Map<Integer, Integer> qty(int id, int q) {
		Map<Integer, Integer> m = new HashMap<>();
		m.put(id, q);
		return m;
	}

	@Test
	public void itemsRemovedInADepositContextAreBankedUntilTheBankIsRead() {
		BankDepositLedger l = new BankDepositLedger(Map::of);
		// Dropping a fish is not a deposit.
		assertFalse(l.onItemsRemoved(qty(13439, 1), 10));
		// "Deposit-All" at a deposit box / bank boat: the next inventory change is.
		l.onDepositClick(20);
		assertTrue(l.onItemsRemoved(qty(13439, 12), 21));
		assertEquals(Integer.valueOf(12), l.contents().get(13439));
		// The window closes.
		assertFalse(l.onItemsRemoved(qty(13439, 1), 24));
		// With the deposit box interface open every removal counts.
		l.onDepositInterface(true);
		assertTrue(l.onItemsRemoved(qty(13439, 3), 40));
		l.onDepositInterface(false);
		assertEquals(Integer.valueOf(15), l.contents().get(13439));
		// Reading the bank proper folds them in.
		assertTrue(l.onBankRead());
		assertTrue(l.contents().isEmpty());
		assertFalse(l.onBankRead());
	}

	@Test
	public void firstDepositOfASessionBuildsOnTheServersFigure() {
		Map<Integer, Integer> server = qty(13439, 5);
		BankDepositLedger l = new BankDepositLedger(() -> server);
		l.onDepositClick(1);
		l.onItemsRemoved(qty(13439, 2), 1);
		assertEquals(Integer.valueOf(7), l.contents().get(13439));
		// A bank read supersedes the server's figure; later deposits start from zero.
		l.onBankRead();
		l.onDepositClick(50);
		l.onItemsRemoved(qty(13439, 1), 50);
		assertEquals(Integer.valueOf(1), l.contents().get(13439));
		// Next session seeds again.
		l.resetSession();
		server.put(13439, 9);
		l.onDepositClick(100);
		l.onItemsRemoved(qty(13439, 1), 100);
		assertEquals(Integer.valueOf(10), l.contents().get(13439));
	}
}
