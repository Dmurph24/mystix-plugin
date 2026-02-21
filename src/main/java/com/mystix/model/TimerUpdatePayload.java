package com.mystix.model;

import lombok.Value;
import net.runelite.client.plugins.timetracking.Tab;

/**
 * Payload sent to the Mystix API with expected finish times.
 * The server uses expectedFinishTime to schedule notifications.
 */
@Value
public class TimerUpdatePayload
{
	public enum TimerType
	{
		FARMING("farming"),
		BIRD_HOUSE("birdhouse"),
		TIMER("timer");

		private final String jsonValue;

		TimerType(String jsonValue)
		{
			this.jsonValue = jsonValue;
		}

		public String getJsonValue()
		{
			return jsonValue;
		}
	}

	TimerType type;
	String entity;
	long expectedFinishTime;

	public static TimerUpdatePayload farming(Tab tab, long expectedFinishTime)
	{
		return new TimerUpdatePayload(TimerType.FARMING, tab.name(), expectedFinishTime);
	}

	public static TimerUpdatePayload birdHouse(long expectedFinishTime)
	{
		return new TimerUpdatePayload(TimerType.BIRD_HOUSE, "birdhouse", expectedFinishTime);
	}

	public static TimerUpdatePayload timer(String timerName, long expectedFinishTime)
	{
		return new TimerUpdatePayload(TimerType.TIMER, timerName, expectedFinishTime);
	}

	/**
	 * Serialize to JSON without adding Gson dependency.
	 */
	public String toJson()
	{
		return "{\"type\":\"" + type.getJsonValue() +
			"\",\"entity\":\"" + escapeJson(entity) +
			"\",\"expectedFinishTime\":" + expectedFinishTime + "}";
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
