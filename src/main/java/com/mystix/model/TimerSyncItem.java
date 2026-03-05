package com.mystix.model;

import java.time.Instant;
import lombok.Value;

/**
 * Single timer item matching the backend TimersSyncSerializer format.
 * Sent in bulk as {"timers": [...]} to POST /api/runelite/timers/
 * Enum-like string fields are lowercased with spaces (no underscores).
 * playerUsername is sent as-is (case-sensitive).
 */
@Value
public class TimerSyncItem
{
	String timerType;
	String region;
	String entity;
	Instant completedAt;
	boolean notificationsEnabled;
	String playerUsername;
	/** When the timer started; null if unknown (e.g. Tears of Guthix weekly reset). */
	Instant startedAt;
	/** Crop state for farming patches only: growing, harvestable, diseased, dead, empty, filling; null otherwise. */
	String cropState;
	/** OSRS item ID for the entity (farming produce, bird house); null if not applicable. */
	Integer osrsItemId;
	/** Stable patch identifier (varbit ID for farming patches); 0 for non-farming timers. */
	int patchId;

	/**
	 * Format string for API: lowercase, spaces instead of underscores.
	 */
	static String toApiFormat(String s)
	{
		if (s == null || s.isBlank())
		{
			return s != null ? s : "";
		}
		return s.toLowerCase().replace("_", " ").trim();
	}

	/**
	 * Append this timer as a JSON object to the given StringBuilder.
	 */
	void appendJson(StringBuilder sb)
	{
		sb.append("{\"timer_type\":\"").append(escapeJson(toApiFormat(timerType)))
			.append("\",\"region\":\"").append(escapeJson(toApiFormat(region)))
			.append("\",\"entity\":\"").append(escapeJson(toApiFormat(entity)))
			.append("\",\"completed_at\":\"").append(completedAt.toString()).append("\"");
		if (startedAt != null)
		{
			sb.append(",\"started_at\":\"").append(startedAt.toString()).append("\"");
		}
		if (cropState != null && !cropState.isBlank())
		{
			sb.append(",\"crop_state\":\"").append(escapeJson(toApiFormat(cropState))).append("\"");
		}
		if (osrsItemId != null)
		{
			sb.append(",\"osrs_item_id\":").append(osrsItemId);
		}
		sb.append(",\"patch_id\":").append(patchId)
			.append(",\"notifications_enabled\":").append(notificationsEnabled)
			.append(",\"player_username\":\"").append(escapeJson(playerUsername))
			.append("\"}");
	}

	private static String escapeJson(String s)
	{
		if (s == null)
		{
			return "";
		}
		return s.replace("\\", "\\\\")
			.replace("\"", "\\\"")
			.replace("\n", "\\n")
			.replace("\r", "\\r")
			.replace("\t", "\\t");
	}
}
