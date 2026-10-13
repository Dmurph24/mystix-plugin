package com.mystix;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import com.google.gson.Gson;
import org.junit.Test;

public class YamaDamageTrackerTest
{
	private final YamaDamageTracker tracker = new YamaDamageTracker();

	private void hit(String npc, int amount, boolean mine, boolean others, int players, int tick)
	{
		tracker.onHitsplat(npc, amount, false, mine, others, () -> players, tick);
	}

	private String finish()
	{
		return new Gson().toJson(tracker.finish());
	}

	@Test
	public void soloFightSumsYourDamageOnYamaAndTheJudge()
	{
		tracker.onNpcSpawned("Yama", 100);
		hit("Yama", 40, true, false, 1, 101);
		hit("Judge of Yama", 30, true, false, 1, 150);
		tracker.onNpcSpawned("Yama", 170);
		hit("Yama", 25, true, false, 1, 171);
		hit("Yama", 5, false, false, 1, 172);
		tracker.onHitsplat("Yama", 12, true, false, false, () -> 1, 173);
		hit("Voidflare", 9, true, false, 1, 174);

		assertEquals("{\"mine\":65,\"others\":0,\"dot\":5,\"judge\":30,\"players\":1}", finish());
	}

	@Test
	public void duoFightKeepsThePartnersDamageApart()
	{
		tracker.onNpcSpawned("Yama", 100);
		hit("Yama", 40, true, false, 2, 101);
		hit("Yama", 35, false, true, 2, 102);
		hit("Judge of Yama", 20, false, true, 2, 103);

		assertEquals("{\"mine\":40,\"others\":35,\"dot\":0,\"judge\":0,\"players\":2}", finish());
	}

	@Test
	public void fightJoinedPartWaySendsNothing()
	{
		hit("Yama", 40, true, false, 1, 101);

		assertNull(tracker.finish());
	}

	@Test
	public void nextKillCountsFromThePreviousLoot()
	{
		tracker.onNpcSpawned("Yama", 100);
		hit("Yama", 40, true, false, 1, 101);
		tracker.finish();
		hit("Yama", 7, true, false, 1, 300);

		assertEquals("{\"mine\":7,\"others\":0,\"dot\":0,\"judge\":0,\"players\":1}", finish());
	}

	@Test
	public void yamaAppearingAfterAQuietSpellStartsAFreshFight()
	{
		tracker.onNpcSpawned("Yama", 100);
		hit("Yama", 40, true, false, 1, 101);
		tracker.onNpcSpawned("Yama", 101 + YamaDamageTracker.STALE_TICKS + 1);
		hit("Yama", 9, true, false, 1, 400);

		assertEquals("{\"mine\":9,\"others\":0,\"dot\":0,\"judge\":0,\"players\":1}", finish());
	}

	@Test
	public void logoutStopsCounting()
	{
		tracker.onNpcSpawned("Yama", 100);
		hit("Yama", 40, true, false, 1, 101);
		tracker.reset();

		assertNull(tracker.finish());
	}
}
