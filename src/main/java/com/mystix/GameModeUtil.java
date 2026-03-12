package com.mystix;

import java.util.EnumSet;
import net.runelite.api.Client;
import net.runelite.api.WorldType;

/**
 * Shared utility for detecting special game modes that should not sync
 * to avoid data conflicts with main game progression.
 */
public final class GameModeUtil {

	private static final EnumSet<WorldType> SPECIAL_GAME_MODES = EnumSet.of(
			WorldType.SEASONAL,
			WorldType.DEADMAN,
			WorldType.FRESH_START_WORLD,
			WorldType.TOURNAMENT_WORLD,
			WorldType.BETA_WORLD,
			WorldType.NOSAVE_MODE,
			WorldType.QUEST_SPEEDRUNNING);

	private GameModeUtil() {
	}

	/**
	 * Returns true if the player is on a special game mode world (Leagues, DMM,
	 * Fresh Start, Tournaments, Beta, Speedrunning) where syncing should be skipped
	 * to avoid data conflicts with main game progression.
	 */
	public static boolean isSpecialGameMode(Client client) {
		EnumSet<WorldType> worldTypes = client.getWorldType();
		for (WorldType special : SPECIAL_GAME_MODES) {
			if (worldTypes.contains(special)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Returns true if the player is on a Leagues (seasonal) world but not Deadman mode.
	 * Used to adjust growth rates for farming timers.
	 */
	public static boolean isLeaguesWorld(Client client) {
		EnumSet<WorldType> worldTypes = client.getWorldType();
		return worldTypes.contains(WorldType.SEASONAL) && !worldTypes.contains(WorldType.DEADMAN);
	}
}
