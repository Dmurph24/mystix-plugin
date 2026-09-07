package com.mystix.model;

import java.util.Locale;

/**
 * Roadmap goal types as sent by the backend's {@code goal_type} field. Unknown or
 * missing values map to {@link #UNKNOWN} so a new backend type never breaks the
 * plugin; such goals simply render with the server's values.
 */
public enum GoalType {
	SKILL_LEVEL("skill_level"),
	SKILL_XP("skill_xp"),
	QUEST("quest"),
	NPC_DROP("npc_drop"),
	CLOG_ITEM("clog_item"),
	COMBAT_ACHIEVEMENT("combat_achievement"),
	DIARY_TASK("diary_task"),
	KC("kc"),
	ITEM_QUANTITY("item_quantity"),
	ITEM_OWNED("item_owned"),
	NET_WORTH("net_worth"),
	FARMING_TIMER("farming_timer"),
	TEARS_OF_GUTHIX("tears_of_guthix"),
	CUSTOM("custom"),
	UNKNOWN("");

	private final String wire;

	GoalType(String wire) {
		this.wire = wire;
	}

	public String getWire() {
		return wire;
	}

	public static GoalType fromWire(String wire) {
		if (wire == null) {
			return UNKNOWN;
		}
		String needle = wire.trim().toLowerCase(Locale.ROOT);
		for (GoalType type : values()) {
			if (type != UNKNOWN && type.wire.equals(needle)) {
				return type;
			}
		}
		return UNKNOWN;
	}

	/** Skill level / XP goals: progress is XP gained since the goal was created. */
	public boolean isSkill() {
		return this == SKILL_LEVEL || this == SKILL_XP;
	}

	/** Goals whose progress counts loot events: kills or dropped item quantity. */
	public boolean isLootCounted() {
		return this == KC || this == ITEM_QUANTITY;
	}

	/** Binary goals completed by obtaining a specific item. */
	public boolean isItemObtain() {
		return this == NPC_DROP || this == CLOG_ITEM;
	}

	/** Goals only the server can move (no in-game event the plugin tracks locally). */
	public boolean isServerDriven() {
		return this == NET_WORTH || this == TEARS_OF_GUTHIX || this == UNKNOWN;
	}

	/** Goals the backend reports a progress percentage for (drawn with a bar). */
	public boolean isMeasurable() {
		return isSkill() || isLootCounted() || this == ITEM_OWNED || this == NET_WORTH || this == FARMING_TIMER;
	}
}
