package com.mystix.model;

import com.google.gson.Gson;
import java.util.List;

/**
 * Payload for one scrape of the Slayer Rewards task list interface: per-task
 * name, amount range, the game's own assignment percentage for this player,
 * and the status sprite (available / cannot assign / blocked / current).
 * Matches the format expected by POST /api/runelite/slayer/rewards/.
 */
public class SlayerRewardsPayload {
	public static class Row {
		private final String name;
		private final Integer amount_min;
		private final Integer amount_max;
		private final double percent;
		private final int status_sprite;

		public Row(String name, Integer amountMin, Integer amountMax,
				double percent, int statusSprite) {
			this.name = name;
			this.amount_min = amountMin;
			this.amount_max = amountMax;
			this.percent = percent;
			this.status_sprite = statusSprite;
		}

		public String getName() {
			return name;
		}

		public double getPercent() {
			return percent;
		}
	}

	private final String player_username;
	private final Integer master_id;
	private final List<Row> rows;
	private final String captured_at;

	public SlayerRewardsPayload(
			String playerUsername, Integer masterId, List<Row> rows, String capturedAt) {
		this.player_username = playerUsername;
		this.master_id = masterId;
		this.rows = rows;
		this.captured_at = capturedAt;
	}

	public String getPlayerUsername() {
		return player_username;
	}

	public List<Row> getRows() {
		return rows;
	}

	public String toJson(Gson gson) {
		return gson.toJson(this);
	}
}
