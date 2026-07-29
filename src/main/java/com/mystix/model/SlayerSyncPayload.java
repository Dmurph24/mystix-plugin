package com.mystix.model;

import com.google.gson.Gson;
import java.util.List;
import java.util.Map;

/**
 * Payload for syncing slayer state + task transition events to the Mystix API.
 * Matches the format expected by POST /api/runelite/slayer/.
 */
public class SlayerSyncPayload {
	/** Raw slayer varp/varbit snapshot. Field names match the wire format. */
	public static class State {
		private final Integer task_id;
		private final String task_name;
		private final Integer boss_task_id;
		private final Integer area_id;
		private final Integer master_id;
		private final int amount_remaining;
		private final int amount_original;
		private final int points;
		private final int streak;
		private final int wilderness_streak;
		private final List<Integer> block_list;
		private final Map<String, Integer> unlock_bitfields;
		private final Map<String, Object> extra;
		private final Integer slayer_level;
		private final Long slayer_xp;
		private final String first_kill_at;
		private final String last_kill_at;
		private final Long active_seconds;
		private final String captured_at;

		public State(
				Integer taskId,
				String taskName,
				Integer bossTaskId,
				Integer areaId,
				Integer masterId,
				int amountRemaining,
				int amountOriginal,
				int points,
				int streak,
				int wildernessStreak,
				List<Integer> blockList,
				Map<String, Integer> unlockBitfields,
				Map<String, Object> extra,
				Integer slayerLevel,
				Long slayerXp,
				String firstKillAt,
				String lastKillAt,
				Long activeSeconds,
				String capturedAt) {
			this.task_id = taskId;
			this.task_name = taskName;
			this.boss_task_id = bossTaskId;
			this.area_id = areaId;
			this.master_id = masterId;
			this.amount_remaining = amountRemaining;
			this.amount_original = amountOriginal;
			this.points = points;
			this.streak = streak;
			this.wilderness_streak = wildernessStreak;
			this.block_list = blockList;
			this.unlock_bitfields = unlockBitfields;
			this.extra = extra;
			this.slayer_level = slayerLevel;
			this.slayer_xp = slayerXp;
			this.first_kill_at = firstKillAt;
			this.last_kill_at = lastKillAt;
			this.active_seconds = activeSeconds;
			this.captured_at = capturedAt;
		}

		public String getTaskName() {
			return task_name;
		}

		public int getAmountRemaining() {
			return amount_remaining;
		}
	}

	private final String player_username;
	private final State state;
	private final List<SlayerTaskEvent> events;

	public SlayerSyncPayload(String playerUsername, State state, List<SlayerTaskEvent> events) {
		this.player_username = playerUsername;
		this.state = state;
		this.events = events;
	}

	public String getPlayerUsername() {
		return player_username;
	}

	public State getState() {
		return state;
	}

	public List<SlayerTaskEvent> getEvents() {
		return events;
	}

	public String toJson(Gson gson) {
		return gson.toJson(this);
	}
}
