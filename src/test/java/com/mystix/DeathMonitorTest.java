package com.mystix;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.mystix.DeathMonitor.KillerCandidate;
import com.mystix.model.DeathEvent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import net.runelite.api.WorldType;
import org.junit.Test;

/**
 * Tests for DeathMonitor's killer attribution, queue cap and config toggle.
 */
public class DeathMonitorTest {
	private static KillerCandidate npc(String name, int tick) {
		return new KillerCandidate(DeathEvent.KILLER_NPC, 12223, name, tick);
	}

	@Test
	public void recentAttackerIsTheKiller() {
		KillerCandidate attacker = npc("Vardorvis", 100);
		assertSame(attacker, DeathMonitor.chooseKiller(attacker, npc("Zulrah", 105), 104));
	}

	@Test
	public void staleAttackerFallsBackToWhatThePlayerWasFighting() {
		KillerCandidate target = npc("Zulrah", 100);
		int deathTick = 100 + DeathMonitor.KILLER_WINDOW_TICKS;
		assertSame(target, DeathMonitor.chooseKiller(npc("Vardorvis", 50), target, deathTick));
	}

	@Test
	public void nothingRecentMeansUnknown() {
		assertNull(DeathMonitor.chooseKiller(npc("Vardorvis", 10), npc("Zulrah", 20), 200));
		assertNull(DeathMonitor.chooseKiller(null, null, 200));
	}

	@Test
	public void queueKeepsTheNewestDeathsPastTheCap() {
		List<DeathEvent> events = new ArrayList<>();
		for (int i = 0; i < DeathMonitor.MAX_PENDING + 5; i++) {
			events.add(new DeathEvent("death-" + i, "2026-09-27T21:14:00Z", DeathEvent.KILLER_UNKNOWN,
					null, null, null, null, null, null, false, 330, new ArrayList<>()));
		}
		DeathMonitor.trimPending(events);
		assertEquals(DeathMonitor.MAX_PENDING, events.size());
		assertEquals("death-5", events.get(0).getEventUuid());
	}

	@Test
	public void worldTypesAreSentByName() {
		assertEquals(Arrays.asList("MEMBERS", "PVP"),
				DeathMonitor.worldTypeNames(EnumSet.of(WorldType.MEMBERS, WorldType.PVP)));
		assertTrue(DeathMonitor.worldTypeNames(null).isEmpty());
	}

	@Test
	public void syncDeathsIsOnByDefaultAndCanBeTurnedOff() {
		TestMystixConfig config = new TestMystixConfig();
		assertTrue(config.syncDeaths());
		config.setSyncDeaths(false);
		assertFalse(config.syncDeaths());
	}
}
