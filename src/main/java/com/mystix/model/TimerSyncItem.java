package com.mystix.model;

import java.time.Instant;
import lombok.Value;

/**
 * Single timer item matching the backend TimersSyncSerializer format.
 * Sent in bulk as {"timers": [...]} to POST /api/runelite/timers/
 */
@Value
public class TimerSyncItem
{
	String name;
	Instant completedAt;
	boolean notificationsEnabled;
	String playerUsername;

	/**
	 * Append this timer as a JSON object to the given StringBuilder.
	 */
	void appendJson(StringBuilder sb)
	{
		sb.append("{\"name\":\"").append(escapeJson(name))
			.append("\",\"completed_at\":\"").append(completedAt.toString())
			.append("\",\"notifications_enabled\":").append(notificationsEnabled)
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
