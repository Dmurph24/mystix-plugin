package com.mystix.model;

import java.time.Instant;
import lombok.Value;

/**
 * Single timer item matching the backend TimersSyncSerializer format.
 * Sent in bulk as {"timers": [...]} to POST /api/runelite/timers/
 * All string fields are lowercased with spaces (no underscores).
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
			.append("\",\"completed_at\":\"").append(completedAt.toString())
			.append("\",\"notifications_enabled\":").append(notificationsEnabled)
			.append(",\"player_username\":\"").append(escapeJson(toApiFormat(playerUsername)))
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
