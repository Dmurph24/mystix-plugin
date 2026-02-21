package com.mystix;

import com.mystix.api.MystixApiClient;
import com.mystix.model.TimerUpdatePayload;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.client.plugins.timetracking.SummaryState;
import net.runelite.client.plugins.timetracking.Tab;
import net.runelite.client.plugins.timetracking.farming.FarmingTracker;
import net.runelite.client.plugins.timetracking.hunter.BirdHouseTracker;

/**
 * Collects expected finish times from Time Tracking and sends them to the Mystix API
 * so the server can schedule notifications when timers complete.
 */
@Slf4j
@Singleton
public class TimerMonitor
{
	private static final int SYNC_INTERVAL_SECONDS = 45;

	private final Client client;
	private final MystixConfig config;
	private final MystixApiClient apiClient;
	private final FarmingTracker farmingTracker;
	private final BirdHouseTracker birdHouseTracker;
	private final ScheduledExecutorService executorService;

	private ScheduledFuture<?> scheduledFuture;
	private final Map<String, Long> lastSentTimestamps = new HashMap<>();

	@Inject
	public TimerMonitor(
		Client client,
		MystixConfig config,
		MystixApiClient apiClient,
		FarmingTracker farmingTracker,
		BirdHouseTracker birdHouseTracker,
		ScheduledExecutorService executorService)
	{
		this.client = client;
		this.config = config;
		this.apiClient = apiClient;
		this.farmingTracker = farmingTracker;
		this.birdHouseTracker = birdHouseTracker;
		this.executorService = executorService;
	}

	public void start()
	{
		if (scheduledFuture != null)
		{
			return;
		}
		scheduledFuture = executorService.scheduleAtFixedRate(
			this::sync,
			SYNC_INTERVAL_SECONDS,
			SYNC_INTERVAL_SECONDS,
			TimeUnit.SECONDS
		);
		log.debug("TimerMonitor started");
	}

	public void stop()
	{
		if (scheduledFuture != null)
		{
			scheduledFuture.cancel(false);
			scheduledFuture = null;
		}
		lastSentTimestamps.clear();
		log.debug("TimerMonitor stopped");
	}

	private void sync()
	{
		if (config.mystixAppKey() == null || config.mystixAppKey().isBlank())
		{
			return;
		}
		if (!config.syncTimeTracking())
		{
			return;
		}
		if (client.getGameState() != GameState.LOGGED_IN)
		{
			return;
		}

		long now = Instant.now().getEpochSecond();

		// Farming patches (per tab)
		for (Tab tab : Tab.FARMING_TABS)
		{
			if (farmingTracker.getSummary(tab) == SummaryState.IN_PROGRESS)
			{
				long completionTime = farmingTracker.getCompletionTime(tab);
				if (completionTime > 0 && completionTime > now)
				{
					sendIfChanged("farming:" + tab.name(), completionTime,
						TimerUpdatePayload.farming(tab, completionTime));
				}
			}
		}

		// Bird houses
		if (birdHouseTracker.getSummary() == SummaryState.IN_PROGRESS)
		{
			long completionTime = birdHouseTracker.getCompletionTime();
			if (completionTime > 0 && completionTime > now)
			{
				sendIfChanged("birdhouse", completionTime,
					TimerUpdatePayload.birdHouse(completionTime));
			}
		}

		// Note: Timers & Stopwatches from ClockManager use package-private types
		// and cannot be accessed from external plugins. Farming and bird houses are synced.
	}

	private void sendIfChanged(String entityKey, long expectedFinishTime, TimerUpdatePayload payload)
	{
		Long lastSent = lastSentTimestamps.get(entityKey);
		if (lastSent == null || lastSent != expectedFinishTime)
		{
			lastSentTimestamps.put(entityKey, expectedFinishTime);
			apiClient.sendTimerUpdate(payload);
		}
	}
}
