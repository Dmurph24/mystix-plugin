package com.mystix;

import com.mystix.api.MystixApiClient;
import com.mystix.model.TimerSyncItem;
import com.mystix.model.TimersSyncPayload;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
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
import net.runelite.client.config.ConfigManager;
import net.runelite.client.plugins.timetracking.TimeTrackingConfig;
import net.runelite.client.plugins.timetracking.SummaryState;
import com.mystix.runelite.farming.FarmingPatch;
import com.mystix.runelite.farming.FarmingTracker;
import com.mystix.runelite.farming.PatchPrediction;
import com.mystix.runelite.farming.Produce;
import com.mystix.runelite.hunter.BirdHouseTracker;

/**
 * Collects expected finish times from Time Tracking and sends them to the
 * Mystix API
 * so the server can schedule notifications when timers complete.
 * Only sends when timer data changes to avoid excess API calls.
 * Uses copied farming/hunter classes (see update.js) for per-patch names.
 */
@Slf4j
@Singleton
public class TimerMonitor {
	private static final int INITIAL_DELAY_SECONDS = 10;
	private static final int SYNC_INTERVAL_SECONDS = 45;

	private final Client client;
	private final MystixConfig config;
	private final MystixApiClient apiClient;
	private final ConfigManager configManager;
	private final ScheduledExecutorService executorService;

	private FarmingTracker farmingTracker;
	private BirdHouseTracker birdHouseTracker;
	private com.mystix.runelite.farming.FarmingWorld farmingWorld;
	private ScheduledFuture<?> scheduledFuture;
	private String lastSentSnapshot;
	private volatile Instant tearsOfGuthixNextReset = null;

	@Inject
	public TimerMonitor(
			Client client,
			MystixConfig config,
			MystixApiClient apiClient,
			ConfigManager configManager,
			ScheduledExecutorService executorService) {
		this.client = client;
		this.config = config;
		this.apiClient = apiClient;
		this.configManager = configManager;
		this.executorService = executorService;
	}

	public void initialize(FarmingTracker farmingTracker, BirdHouseTracker birdHouseTracker, com.mystix.runelite.farming.FarmingWorld farmingWorld) {
		this.farmingTracker = farmingTracker;
		this.birdHouseTracker = birdHouseTracker;
		this.farmingWorld = farmingWorld;
	}

	public void start() {
		if (farmingTracker == null || birdHouseTracker == null) {
			log.warn("TimerMonitor not initialized; skipping start");
			return;
		}
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
		tearsOfGuthixNextReset = null;
		log.debug("TimerMonitor stopped");
	}

	/**
	 * Called when the player enters the Tears of Guthix cave.
	 * ToG is playable again 7 days after completion. Reset is rounded down to
	 * the nearest day (00:00 UTC) for a clean weekly boundary.
	 */
	public void onTearsOfGuthixCompleted() {
		ZonedDateTime nowUtc = Instant.now().atZone(ZoneOffset.UTC);
		ZonedDateTime completionDayStart = nowUtc.toLocalDate().atStartOfDay(ZoneOffset.UTC);
		Instant nextReset = completionDayStart.plusDays(7).toInstant();
		tearsOfGuthixNextReset = nextReset;
		lastSentSnapshot = null; // force sync on next tick
		log.info("Tears of Guthix completed; next reset at {}", tearsOfGuthixNextReset);
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
		birdHouseTracker.loadFromConfig();
		birdHouseTracker.updateCompletionTime();
		farmingTracker.updateCompletionTime();

		boolean syncEnabled = config.syncTimeTracking();
		List<TimerSyncItem> timers = new ArrayList<>();

		// Per-patch timers with readable names (e.g. "Farming Guild", "Catherby")
		// Each patch has its own bell/notification toggle in the Time Tracking panel
		for (var entry : farmingWorld.getTabs().entrySet()) {
			for (FarmingPatch patch : entry.getValue()) {
				PatchPrediction prediction = farmingTracker.predictPatch(patch);
				if (prediction == null || prediction.getProduce().getItemID() < 0) {
					continue;
				}
				if (prediction.getProduce() == Produce.WEEDS || prediction.getProduce() == Produce.SCARECROW) {
					continue;
				}
				long doneEstimate = prediction.getDoneEstimate();
				if (doneEstimate <= 0) {
					continue;
				}
				boolean notificationsEnabled = syncEnabled && isFarmingNotifyEnabled(patch);
				String regionName = patch.getRegion().getName();
				if (regionName == null || regionName.isBlank()) {
					regionName = entry.getKey().name().toLowerCase();
				}
				String tabName = entry.getKey().getName();
				if (tabName == null || tabName.isBlank()) {
					tabName = entry.getKey().name().toLowerCase();
				}
				timers.add(new TimerSyncItem(
						tabName,
						regionName,
						prediction.getProduce().getName(),
						Instant.ofEpochSecond(doneEstimate),
						notificationsEnabled,
						playerUsername));
			}
		}

<<<<<<< Updated upstream
		// Bird houses
		if (birdHouseTracker.getSummary() == SummaryState.IN_PROGRESS) {
			long completionTime = birdHouseTracker.getCompletionTime();
			if (completionTime > 0) {
=======
		// Bird houses — started_at is when the latest bird house was seeded (completionTime - 50 min duration)
		// Bird houses have a single notify toggle in Time Tracking
		if (birdHouseTracker.getSummary() == SummaryState.IN_PROGRESS) {
			long completionTime = birdHouseTracker.getCompletionTime();
			if (completionTime > 0) {
				boolean birdHouseNotify = syncEnabled && isBirdHouseNotifyEnabled();
				Instant birdHouseStartedAt = Instant.ofEpochSecond(completionTime - BirdHouseTracker.BIRD_HOUSE_DURATION);
>>>>>>> Stashed changes
				timers.add(new TimerSyncItem(
						"bird house",
						"fossil island",
						"bird house",
						Instant.ofEpochSecond(completionTime),
<<<<<<< Updated upstream
						notificationsEnabled,
=======
						birdHouseStartedAt,
						birdHouseNotify,
>>>>>>> Stashed changes
						playerUsername));
			}
		}

		// Tears of Guthix — playable again 7 days after completion; timer set when player enters cave
		// No per-timer bell in RuneLite; use sync master switch
		Instant togReset = tearsOfGuthixNextReset;
		if (togReset != null && togReset.isAfter(Instant.now())) {
			timers.add(new TimerSyncItem(
					"tears of guthix",
					"tears of guthix",
					"tears of guthix",
					togReset,
<<<<<<< Updated upstream
					notificationsEnabled,
=======
					tearsOfGuthixCompletedAt,
					syncEnabled,
>>>>>>> Stashed changes
					playerUsername));
		}

		String snapshot = TimersSyncPayload.toJson(timers);
		if (!snapshot.equals(lastSentSnapshot)) {
			lastSentSnapshot = snapshot;
			log.info("Mystix syncing {} timer(s) for {}", timers.size(), playerUsername);
			apiClient.sendTimersSync(timers);
		}
	}

	/**
	 * Reads the per-patch notification setting (bell icon) from Time Tracking config.
	 * Config key format matches FarmingPatch.notifyConfigKey().
	 */
	private boolean isFarmingNotifyEnabled(FarmingPatch patch) {
		String notifyKey = TimeTrackingConfig.NOTIFY + "." + patch.getRegion().getRegionID() + "." + patch.getVarbit();
		String profileKey = configManager.getRSProfileKey();
		return Boolean.TRUE.equals(configManager.getConfiguration(TimeTrackingConfig.CONFIG_GROUP, profileKey, notifyKey, Boolean.class));
	}

	/**
	 * Reads the bird house notification toggle from Time Tracking config.
	 */
	private boolean isBirdHouseNotifyEnabled() {
		return Boolean.TRUE.equals(configManager.getRSProfileConfiguration(TimeTrackingConfig.CONFIG_GROUP, TimeTrackingConfig.BIRDHOUSE_NOTIFY, boolean.class));
	}
}
