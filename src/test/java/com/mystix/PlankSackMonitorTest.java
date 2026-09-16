package com.mystix;

import java.util.Map;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.VarbitID;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class PlankSackMonitorTest {
	@Test
	public void varbitsMapToPlankItems() {
		Map<Integer, Integer> contents = PlankSackMonitor.contents(varbit ->
				varbit == VarbitID.PLANK_SACK_OAK ? 20 : varbit == VarbitID.PLANK_SACK_MAHOGANY ? 8 : 0);
		assertEquals(Integer.valueOf(20), contents.get(ItemID.PLANK_OAK));
		assertEquals(Integer.valueOf(8), contents.get(ItemID.PLANK_MAHOGANY));
		assertEquals(2, contents.size());
		assertTrue(PlankSackMonitor.contents(varbit -> 0).isEmpty());
	}
}
