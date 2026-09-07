package com.mystix;

import com.mystix.model.GoalType;
import net.runelite.client.util.QuantityFormatter;

/**
 * Formats the "current / target" label under a goal's progress bar the same way
 * the Mystix app does: XP and net worth abbreviated (12.4K, 1.3M), counts with
 * thousands separators, and a single-crop farming goal as "47% grown".
 */
final class GoalProgressLabel {
	private GoalProgressLabel() {
	}

	static String format(GoalType type, int current, int target, Integer percent) {
		if (type == GoalType.FARMING_TIMER && target <= 1) {
			return (percent == null ? 0 : percent) + "% grown";
		}
		boolean abbreviate = type.isSkill() || type == GoalType.NET_WORTH;
		String unit = type.isSkill() ? " XP" : "";
		return fmt(current, abbreviate) + unit + " / " + fmt(target, abbreviate) + unit;
	}

	/** "63%", or null when the goal has no percentage. */
	static String percent(Integer percent) {
		return percent == null ? null : Math.max(0, Math.min(100, percent)) + "%";
	}

	/** Bar fill fraction in [0, 1]. */
	static double fraction(Integer percent) {
		return percent == null ? 0 : Math.max(0, Math.min(100, percent)) / 100d;
	}

	private static String fmt(int value, boolean abbreviate) {
		long v = Math.max(0, value);
		return abbreviate ? QuantityFormatter.quantityToStackSize(v) : QuantityFormatter.formatNumber(v);
	}
}
