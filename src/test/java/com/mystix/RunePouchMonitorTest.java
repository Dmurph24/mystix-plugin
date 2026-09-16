package com.mystix;

import java.util.Map;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class RunePouchMonitorTest {
	@Test
	public void slotsResolveThroughTheRuneEnumAndMerge() {
		// Rune index 1 -> item 561 (nature), 2 -> 560 (death); slot 3 empty, slot 4 nature again.
		Map<Integer, Integer> contents = RunePouchMonitor.contents(
				new int[]{1, 2, 0, 1, 0, 0}, new int[]{1000, 250, 7, 500, 0, 0},
				rune -> rune == 1 ? 561 : rune == 2 ? 560 : -1);
		assertEquals(Integer.valueOf(1500), contents.get(561));
		assertEquals(Integer.valueOf(250), contents.get(560));
		assertEquals(2, contents.size());
	}

	@Test
	public void emptyPouchIsEmpty() {
		assertTrue(RunePouchMonitor.contents(new int[6], new int[6], rune -> 561).isEmpty());
		// An unknown rune index is skipped rather than uploaded as a bogus item.
		assertTrue(RunePouchMonitor.contents(new int[]{9}, new int[]{5}, rune -> -1).isEmpty());
	}
}
