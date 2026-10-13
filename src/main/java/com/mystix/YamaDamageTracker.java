package com.mystix;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.IntSupplier;

/**
 * Sums the hitsplats on Yama over one fight, so the Yama loot can carry the damage behind it.
 * Yama scales each player's loot by their own damage, which the loot itself often doesn't show.
 *
 * <p>A fight runs from Yama appearing (or the previous Yama loot) to the next Yama loot. Yama
 * leaves the scene during the Judge of Yama phases, so a reappearance only starts a new fight
 * when nothing has been hit for a while. A fight joined part way through sends nothing.
 */
class YamaDamageTracker
{
	static final String YAMA = "Yama";
	static final String JUDGE = "Judge of Yama";
	/** About a minute with no hits on Yama or the Judge: a fight left or lost, not a phase change. */
	static final int STALE_TICKS = 100;

	private boolean watching;
	private int lastHitTick = -1;
	private int mine;
	private int others;
	private int dot;
	private int judge;
	private int players;

	void onNpcSpawned(String name, int tick)
	{
		if (YAMA.equals(name) && (!watching || lastHitTick < 0 || tick - lastHitTick > STALE_TICKS))
		{
			start();
		}
	}

	/**
	 * Heal hitsplats aren't damage; hits that are neither yours nor another player's (poison, burn) are
	 * damage over time. {@code players} is everyone in the scene, you included.
	 */
	void onHitsplat(String npcName, int amount, boolean heal, boolean isMine, boolean isOthers, IntSupplier players, int tick)
	{
		boolean yama = YAMA.equals(npcName);
		if (!watching || heal || !(yama || JUDGE.equals(npcName)))
		{
			return;
		}
		lastHitTick = tick;
		this.players = Math.max(this.players, players.getAsInt());
		if (!yama)
		{
			judge += isMine ? amount : 0;
		}
		else if (isMine)
		{
			mine += amount;
		}
		else if (isOthers)
		{
			others += amount;
		}
		else
		{
			dot += amount;
		}
	}

	/**
	 * The damage summed for the fight the loot just ended, or null when the fight wasn't seen from
	 * the start; the next fight is counted from here.
	 */
	Map<String, Object> finish()
	{
		Map<String, Object> damage = null;
		if (watching)
		{
			damage = new LinkedHashMap<>();
			damage.put("mine", mine);
			damage.put("others", others);
			damage.put("dot", dot);
			damage.put("judge", judge);
			damage.put("players", players);
		}
		start();
		return damage;
	}

	void reset()
	{
		watching = false;
	}

	private void start()
	{
		watching = true;
		lastHitTick = -1;
		mine = 0;
		others = 0;
		dot = 0;
		judge = 0;
		players = 0;
	}
}
