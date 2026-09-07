package com.mystix;

import net.runelite.api.gameval.InterfaceID;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class GoalCompletionNotifierTest {
	@Test
	public void mapsKnownLayoutsToTheirNotificationSlot() {
		assertEquals(InterfaceID.ToplevelOsrsStretch.NOTIFICATIONS,
				GoalCompletionNotifier.parentComponentFor(InterfaceID.TOPLEVEL_OSRS_STRETCH));
		assertEquals(InterfaceID.ToplevelPreEoc.NOTIFICATIONS,
				GoalCompletionNotifier.parentComponentFor(InterfaceID.TOPLEVEL_PRE_EOC));
		assertEquals(InterfaceID.ToplevelDisplay.NOTIFICATIONS,
				GoalCompletionNotifier.parentComponentFor(InterfaceID.TOPLEVEL_DISPLAY));
		assertEquals(InterfaceID.Toplevel.NOTIFICATIONS,
				GoalCompletionNotifier.parentComponentFor(InterfaceID.TOPLEVEL));
		assertEquals(-1, GoalCompletionNotifier.parentComponentFor(12345));
	}

	@Test
	public void bodyStripsTagsAndReservesProgressSpace() {
		assertEquals("<col=ffffff>Level 80 Slayer</col><br><col=c6c6c6>Maxing</col>"
						+ GoalCompletionNotifier.PROGRESS_SPACER,
				GoalCompletionNotifier.body("<col=ff0000>Level 80 Slayer</col>", "Maxing", 7, 12));
		assertEquals("<col=ffffff>Fire cape</col>" + GoalCompletionNotifier.PROGRESS_SPACER,
				GoalCompletionNotifier.body("Fire cape", " ", 3, 5));
		assertEquals("<col=ffffff>Fire cape</col>", GoalCompletionNotifier.body("Fire cape", null, 0, 0));
	}
}
