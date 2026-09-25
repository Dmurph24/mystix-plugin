package com.mystix;

import com.mystix.model.GoalType;
import java.util.EnumSet;
import java.util.Set;

/**
 * A kind of upload the server evaluates roadmap goals against. After an upload
 * the roadmap is only worth re-reading when it has an unfinished goal of a type
 * that upload can move: a bank upload cannot change quest progress, and loot
 * cannot change a net worth goal.
 */
enum SyncSource {
	LOOT(GoalType.KC, GoalType.ITEM_QUANTITY, GoalType.NPC_DROP, GoalType.CLOG_ITEM),
	COLLECTION_LOG(GoalType.NPC_DROP, GoalType.CLOG_ITEM),
	QUESTS(GoalType.QUEST),
	DIARIES(GoalType.DIARY_TASK),
	COMBAT_ACHIEVEMENTS(GoalType.COMBAT_ACHIEVEMENT),
	KILL_COUNTS(GoalType.KC),
	TIMERS(GoalType.FARMING_TIMER, GoalType.TEARS_OF_GUTHIX),
	BANK(GoalType.ITEM_OWNED, GoalType.NET_WORTH);

	private final Set<GoalType> moves;

	SyncSource(GoalType first, GoalType... rest) {
		this.moves = EnumSet.of(first, rest);
	}

	/** Whether this upload can change a goal of {@code type}. Types this plugin
	 * version doesn't know (added on the server later) always count. */
	boolean affects(GoalType type) {
		return type == GoalType.UNKNOWN || moves.contains(type);
	}
}
