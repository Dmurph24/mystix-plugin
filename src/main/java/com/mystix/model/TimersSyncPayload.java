package com.mystix.model;

import java.util.List;

/**
 * Bulk timers sync payload matching the backend TimersSyncSerializer.
 * {"timers": [{name, completed_at, notifications_enabled, player_username}, ...]}
 */
public final class TimersSyncPayload
{
	private TimersSyncPayload() {}

	/**
	 * Serialize the timers list to JSON for the sync endpoint.
	 */
	public static String toJson(List<TimerSyncItem> timers)
	{
		StringBuilder sb = new StringBuilder("{\"timers\":[");
		for (int i = 0; i < timers.size(); i++)
		{
			if (i > 0)
			{
				sb.append(",");
			}
			timers.get(i).appendJson(sb);
		}
		sb.append("]}");
		return sb.toString();
	}
}
