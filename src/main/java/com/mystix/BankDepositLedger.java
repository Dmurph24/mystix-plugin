package com.mystix;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Items the player deposited through a deposit box, the Sailing bank boat
 * or any other "Deposit" interface that does not send the bank container to
 * the client. Without this the deposited items simply vanish from the
 * plugin's holdings until the next real bank visit. Kept as bank-memory
 * source {@code bank_deposits}, seeded from the server's figure on the
 * first deposit of a session, and cleared when the bank proper is read
 * (which then holds everything). Pure, for unit tests.
 */
final class BankDepositLedger {
	static final String SOURCE = "bank_deposits";
	/** Ticks after a "Deposit" click (or with a deposit interface open) during which removed items count as deposited. */
	static final int DEPOSIT_WINDOW_TICKS = 3;

	private final Supplier<Map<Integer, Integer>> seed;
	private final Map<Integer, Integer> deposits = new LinkedHashMap<>();
	private boolean seeded;
	private boolean interfaceOpen;
	private int lastDepositClickTick = Integer.MIN_VALUE;

	BankDepositLedger(Supplier<Map<Integer, Integer>> seed) {
		this.seed = seed == null ? Map::of : seed;
	}

	void onDepositInterface(boolean open) {
		interfaceOpen = open;
	}

	/** A "Deposit…" menu option was clicked on the given tick. */
	void onDepositClick(int tick) {
		lastDepositClickTick = tick;
	}

	boolean inDepositContext(int tick) {
		return interfaceOpen || (long) tick - lastDepositClickTick < DEPOSIT_WINDOW_TICKS;
	}

	/**
	 * Items that left the inventory or gear (canonical id to quantity) on
	 * {@code tick}. Returns true when they were counted as deposits.
	 */
	boolean onItemsRemoved(Map<Integer, Integer> removed, int tick) {
		if (removed == null || removed.isEmpty() || !inDepositContext(tick)) {
			return false;
		}
		ensureSeeded();
		removed.forEach((id, qty) -> {
			if (qty != null && qty > 0) {
				deposits.merge(id, qty, Integer::sum);
			}
		});
		return true;
	}

	/** The bank proper was read: it now holds everything, so the deposits are folded in. Returns true if there were any. */
	boolean onBankRead() {
		seeded = true; // the server's old figure is superseded by the bank read
		boolean had = !deposits.isEmpty();
		deposits.clear();
		return had;
	}

	/** Logout: seed again next session from whatever the server has then. */
	void resetSession() {
		seeded = false;
		interfaceOpen = false;
		lastDepositClickTick = Integer.MIN_VALUE;
		deposits.clear();
	}

	Map<Integer, Integer> contents() {
		return new LinkedHashMap<>(deposits);
	}

	private void ensureSeeded() {
		if (seeded) {
			return;
		}
		seeded = true;
		Map<Integer, Integer> server = seed.get();
		if (server != null) {
			server.forEach((id, qty) -> {
				if (id != null && qty != null && qty > 0) {
					deposits.merge(id, qty, Integer::sum);
				}
			});
		}
	}
}
