package com.mystix;

import com.mystix.api.MystixApiClient;
import com.mystix.model.TimerSyncItem;
import com.mystix.model.TimersSyncPayload;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Player;
import net.runelite.client.plugins.timetracking.SummaryState;
import net.runelite.client.plugins.timetracking.Tab;
import net.runelite.client.plugins.timetracking.farming.FarmingTracker;
import net.runelite.client.plugins.timetracking.hunter.BirdHouseTracker;

/**
 * Collects expected finish times from Time Tracking and sends them to the
 * Mystix API
 * so the server can schedule notifications when timers complete.
 * Only sends when timer data changes to avoid excess API calls.
 */
@Slf4j
@Singleton
public class TimerMonitor {
	private static final int INITIAL_DELAY_SECONDS = 10;
	private static final int SYNC_INTERVAL_SECONDS = 45;

	private final Client client;
	private final MystixConfig config;
	private final MystixApiClient apiClient;
	private final FarmingTracker farmingTracker;
	private final BirdHouseTracker birdHouseTracker;
	private final ScheduledExecutorService executorService;

	private ScheduledFuture<?> scheduledFuture;
	private String lastSentSnapshot;

	@Inject
	public TimerMonitor(
			Client client,
			MystixConfig config,
			MystixApiClient apiClient,
			FarmingTracker farmingTracker,
			BirdHouseTracker birdHouseTracker,
			ScheduledExecutorService executorService) {
		this.client = client;
		this.config = config;
		this.apiClient = apiClient;
		this.farmingTracker = farmingTracker;
		this.birdHouseTracker = birdHouseTracker;
		this.executorService = executorService;
	}

	public void start() {
		if (scheduledFuture != null) {
			return;
		}
		scheduledFuture = executorService.scheduleAtFixedRate(
				this::sync,
				INITIAL_DELAY_SECONDS,
				SYNC_INTERVAL_SECONDS,
				TimeUnit.SECONDS);
		log.info("TimerMonitor started; first sync in {}s, then every {}s", INITIAL_DELAY_SECONDS, SYNC_INTERVAL_SECONDS);
	}

	public void stop() {
		if (scheduledFuture != null) {
			scheduledFuture.cancel(false);
			scheduledFuture = null;
		}
		lastSentSnapshot = null;
		log.debug("TimerMonitor stopped");
	}

	private void sync() {
		if (config.mystixAppKey() == null || config.mystixAppKey().isBlank()) {
			log.debug("Mystix sync skipped: no App Key configured");
			return;
		}
		if (!config.syncTimeTracking()) {
			log.debug("Mystix sync skipped: sync disabled");
			return;
		}
		if (client.getGameState() != GameState.LOGGED_IN) {
			log.debug("Mystix sync skipped: not logged in");
			return;
		}

		Player localPlayer = client.getLocalPlayer();
		String playerUsername = localPlayer != null ? localPlayer.getName() : null;
		if (playerUsername == null || playerUsername.isBlank()) {
			log.warn("Mystix sync skipped: could not get player username (localPlayer={})", localPlayer != null);
			return;
		}

		// Refresh FarmingTracker from config (it only auto-updates when player is in a
		// farming region)
		farmingTracker.loadCompletionTimes();

		long now = Instant.now().getEpochSecond();
		boolean notificationsEnabled = config.syncTimeTracking();
		List<TimerSyncItem> timers = new ArrayList<>();

		// Farming patches (tab-level; per-patch names require package-private access)
		for (Tab tab : Tab.FARMING_TABS) {
			SummaryState summary = farmingTracker.getSummary(tab);
			long completionTime = farmingTracker.getCompletionTime(tab);
			if (summary == SummaryState.IN_PROGRESS && completionTime > 0 && completionTime > now) {
				String tabLabel = tab.getName();
				timers.add(new TimerSyncItem(
						(tabLabel != null && !tabLabel.isBlank() ? tabLabel : tab.name()).toLowerCase(),
						Instant.ofEpochSecond(completionTime),
						notificationsEnabled,
						playerUsername));
			}
		}

		if (timers.isEmpty()) {
			log.debug("Mystix: 0 timers; HERB summary={}, completionTime={}, now={}",
					farmingTracker.getSummary(Tab.HERB), farmingTracker.getCompletionTime(Tab.HERB), now);
		}

		// Bird houses
		if (birdHouseTracker.getSummary() == SummaryState.IN_PROGRESS) {
			long completionTime = birdHouseTracker.getCompletionTime();
			if (completionTime > 0 && completionTime > now) {
				timers.add(new TimerSyncItem(
						"birdhouse",
						Instant.ofEpochSecond(completionTime),
						notificationsEnabled,
						playerUsername));
			}
		}

		String snapshot = TimersSyncPayload.toJson(timers);
		if (!snapshot.equals(lastSentSnapshot)) {
			lastSentSnapshot = snapshot;
			log.info("Mystix syncing {} timer(s) for {}", timers.size(), playerUsername);
			apiClient.sendTimersSync(timers);
		}
	}
}
