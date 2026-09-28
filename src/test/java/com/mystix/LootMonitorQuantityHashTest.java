package com.mystix;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * The loot change-detection hash is persisted between sessions, so widening
 * quantities to long must not change the bytes for quantities that still fit
 * an int (which would force one redundant re-sync for every player).
 */
public class LootMonitorQuantityHashTest {
	@Test
	public void intRangeQuantitiesHashAsFourBytesLikeBefore() {
		assertArrayEquals(new byte[] {0, 0, 0, 5}, LootMonitor.quantityToBytes(5));
		assertArrayEquals(new byte[] {0x7f, (byte) 0xff, (byte) 0xff, (byte) 0xff},
				LootMonitor.quantityToBytes(Integer.MAX_VALUE));
	}

	@Test
	public void quantitiesPastMaxCashHashAsEightBytes() {
		byte[] bytes = LootMonitor.quantityToBytes(5_000_000_000L);
		assertEquals(8, bytes.length);
		assertArrayEquals(new byte[] {0, 0, 0, 1, 0x2a, 0x05, (byte) 0xf2, 0x00}, bytes);
	}
}
