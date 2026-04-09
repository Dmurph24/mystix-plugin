package com.mystix;

import com.mystix.api.MystixApiClient;
import com.mystix.model.TimerSyncItem;
import com.mystix.model.TimersSyncPayload;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import net.runelite.api.events.GameStateChanged;
import net.runelite.client.eventbus.Subscribe;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.plugins.timetracking.TimeTrackingConfig;
import com.mystix.runelite.farming.CropState;
import com.mystix.runelite.farming.FarmingPatch;
import com.mystix.runelite.farming.FarmingTracker;
import com.mystix.runelite.farming.FarmingWorld;
import com.mystix.runelite.farming.PatchPrediction;
import com.mystix.runelite.farming.Produce;
import com.mystix.runelite.hunter.BirdHouse;
import com.mystix.runelite.hunter.BirdHouseSpace;

@Slf4j
public class TimerMonitor {
	private static final int INITIAL_DELAY_SECONDS = 10;
	private static final int SYNC_INTERVAL_SECONDS = 45;
	private static final int LOGIN_SYNC_DELAY_SECONDS = 3;
	private static final int TEARS_OF_GUTHIX_RESET_DAYS = 7;
	private static final int LEAGUES_GROWTH_RATE_DIVISOR = 5;
	// Average time to harvest 10 birds, matches RuneLite's BirdHouseTracker.BIRD_HOUSE_DURATION.
	private static final long BIRD_HOUSE_DURATION_SECONDS = Duration.ofMinutes(50).getSeconds();

	private final Client client;
	private final MystixConfig config;
	private final MystixApiClient apiClient;
	private final ConfigManager configManager;
	private final ScheduledExecutorService executorService;
	private final FarmingTracker farmingTracker;
	private final FarmingWorld farmingWorld;

	private ScheduledFuture<?> scheduledFuture;
	private String lastSentSnapshot;
	private volatile Instant tearsOfGuthixNextReset = null;
	private GameState previousGameState = GameState.UNKNOWN;

	public TimerMonitor(
			Client client,
			MystixConfig config,
			MystixApiClient apiClient,
			ConfigManager configManager,
			ScheduledExecutorService executorService,
			FarmingTracker farmingTracker,
			FarmingWorld farmingWorld) {
		this.client = client;
		this.config = config;
		this.apiClient = apiClient;
		this.configManager = configManager;
		this.executorService = executorService;
		this.farmingTracker = farmingTracker;
		this.farmingWorld = farmingWorld;
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
	}

	public void stop() {
		if (scheduledFuture != null) {
			scheduledFuture.cancel(false);
			scheduledFuture = null;
		}
		lastSentSnapshot = null;
		tearsOfGuthixNextReset = null;
		previousGameState = GameState.UNKNOWN;
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event) {
		GameState newState = event.getGameState();
		if (newState == GameState.LOGGED_IN && previousGameState != GameState.LOGGED_IN) {
			log.debug("Player logged in, scheduling timer sync in {}s", LOGIN_SYNC_DELAY_SECONDS);
			lastSentSnapshot = null;
			executorService.schedule(this::sync, LOGIN_SYNC_DELAY_SECONDS, TimeUnit.SECONDS);
		}
		previousGameState = newState;
	}

	/**
	 * Called when the player enters the Tears of Guthix cave.
	 * ToG is playable again 7 days after completion, rounded down to 00:00 UTC.
	 */
	public void onTearsOfGuthixCompleted() {
		ZonedDateTime nowUtc = Instant.now().atZone(ZoneOffset.UTC);
		ZonedDateTime completionDayStart = nowUtc.toLocalDate().atStartOfDay(ZoneOffset.UTC);
		tearsOfGuthixNextReset = completionDayStart.plusDays(TEARS_OF_GUTHIX_RESET_DAYS).toInstant();
		lastSentSnapshot = null;
		log.debug("Tears of Guthix completed; next reset at {}", tearsOfGuthixNextReset);
	}

	/**
	 * Collects all timer data (farming, bird houses, Tears of Guthix) and sends
	 * to the Mystix API if anything changed since the last sync.
	 */
	private void sync() {
		if (!SyncGuard.hasAppKey(config)) {
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
		if (GameModeUtil.isSpecialGameMode(client)) {
			log.debug("Mystix sync skipped: special game mode detected (Leagues, DMM, etc.)");
			return;
		}

		String playerUsername = SyncGuard.getPlayerUsername(client);
		if (playerUsername == null) {
			log.warn("Mystix sync skipped: could not get player username");
			return;
		}

		farmingTracker.loadCompletionTimes();
		farmingTracker.updateCompletionTime();

		boolean syncEnabled = config.syncTimeTracking();
		List<TimerSyncItem> timers = new ArrayList<>();

		collectFarmingTimers(timers, playerUsername, syncEnabled);
		collectBirdHouseTimers(timers, playerUsername, syncEnabled);
		collectTearsOfGuthixTimer(timers, playerUsername, syncEnabled);

		String snapshot = TimersSyncPayload.toJson(timers);
		if (!snapshot.equals(lastSentSnapshot)) {
			lastSentSnapshot = snapshot;
			log.debug("Mystix syncing {} timer(s) for {}", timers.size(), playerUsername);
			apiClient.sendTimersSync(timers);
		}
	}

	/**
	 * Iterates all farming patches across all tabs, builds a TimerSyncItem for each
	 * patch with a valid, in-progress prediction, and appends them to the timers list.
	 */
	private void collectFarmingTimers(List<TimerSyncItem> timers, String playerUsername, boolean syncEnabled) {
		for (var entry : farmingWorld.getTabs().entrySet()) {
			for (FarmingPatch patch : entry.getValue()) {
				PatchPrediction prediction = farmingTracker.predictPatch(patch);
				if (!isValidFarmingPrediction(prediction)) {
					continue;
				}

				long doneEstimate = prediction.getDoneEstimate();
				if (doneEstimate <= 0) {
					continue;
				}

				boolean notificationsEnabled = syncEnabled && isFarmingNotifyEnabled(patch);
				String regionName = resolveRegionName(patch, entry.getKey());
				String tabName = resolveTabName(entry.getKey());

				Instant completedAt = Instant.ofEpochSecond(doneEstimate);
				Instant startedAt = computeFarmingStartedAt(prediction, doneEstimate);
				int produceItemId = prediction.getProduce().getItemID();

				timers.add(new TimerSyncItem(
						tabName,
						regionName,
						prediction.getProduce().getName(),
						completedAt,
						notificationsEnabled,
						playerUsername,
						startedAt,
						prediction.getCropState().name().toLowerCase(),
						produceItemId >= 0 ? produceItemId : null,
						patch.getVarbit()));
			}
		}
	}

	private boolean isValidFarmingPrediction(PatchPrediction prediction) {
		if (prediction == null || prediction.getProduce().getItemID() < 0) {
			return false;
		}
		if (prediction.getProduce() == Produce.WEEDS || prediction.getProduce() == Produce.SCARECROW) {
			return false;
		}
		return prediction.getCropState() != CropState.EMPTY && prediction.getCropState() != CropState.FILLING;
	}

	private String resolveRegionName(FarmingPatch patch, com.mystix.runelite.Tab tab) {
		String regionName = patch.getRegion().getName();
		if (regionName == null || regionName.isBlank()) {
			return tab.name().toLowerCase();
		}
		return regionName;
	}

	private String resolveTabName(com.mystix.runelite.Tab tab) {
		String tabName = tab.getName();
		if (tabName == null || tabName.isBlank()) {
			return tab.name().toLowerCase();
		}
		return tabName;
	}

	/**
	 * Reads bird house state directly from the {@code timetracking} RS profile config
	 * (written by the core Time Tracking plugin) and emits a TimerSyncItem for each
	 * seeded house that has not yet completed.
	 */
	private void collectBirdHouseTimers(List<TimerSyncItem> timers, String playerUsername, boolean syncEnabled) {
		boolean birdHouseNotify = syncEnabled && isBirdHouseNotifyEnabled();
		long now = Instant.now().getEpochSecond();

		for (BirdHouseSpace space : BirdHouseSpace.values()) {
			String key = TimeTrackingConfig.BIRD_HOUSE + "." + space.getVarp();
			String stored = configManager.getRSProfileConfiguration(TimeTrackingConfig.CONFIG_GROUP, key);
			if (stored == null) {
				continue;
			}

			String[] parts = stored.split(":");
			if (parts.length != 2) {
				continue;
			}

			int varp;
			long timestamp;
			try {
				varp = Integer.parseInt(parts[0]);
				timestamp = Long.parseLong(parts[1]);
			} catch (NumberFormatException e) {
				continue;
			}

			// Seeded when varp is a positive multiple of 3; see RuneLite BirdHouseState.
			if (varp <= 0 || varp % 3 != 0) {
				continue;
			}

			long spaceCompletionTime = timestamp + BIRD_HOUSE_DURATION_SECONDS;
			if (spaceCompletionTime <= now) {
				continue;
			}

			BirdHouse birdHouse = BirdHouse.fromVarpValue(varp);
			Integer birdHouseEntityId = birdHouse != null ? birdHouse.getItemID() : null;
			String entityName = birdHouse != null ? birdHouse.getName() : "Bird House";

			timers.add(new TimerSyncItem(
					"bird house",
					space.getName(),
					entityName,
					Instant.ofEpochSecond(spaceCompletionTime),
					birdHouseNotify,
					playerUsername,
					Instant.ofEpochSecond(timestamp),
					null,
					birdHouseEntityId,
					space.getVarp()));
		}
	}

	private void collectTearsOfGuthixTimer(List<TimerSyncItem> timers, String playerUsername, boolean syncEnabled) {
		Instant togReset = tearsOfGuthixNextReset;
		if (togReset != null && togReset.isAfter(Instant.now())) {
			timers.add(new TimerSyncItem(
					"tears of guthix",
					"tears of guthix",
					"tears of guthix",
					togReset,
					syncEnabled,
					playerUsername,
					null,
					null,
					null,
					0));
		}
	}

	private boolean isFarmingNotifyEnabled(FarmingPatch patch) {
		String notifyKey = TimeTrackingConfig.NOTIFY + "." + patch.getRegion().getRegionID() + "." + patch.getVarbit();
		String profileKey = configManager.getRSProfileKey();
		return Boolean.TRUE
				.equals(configManager.getConfiguration(TimeTrackingConfig.CONFIG_GROUP, profileKey, notifyKey, Boolean.class));
	}

	private boolean isBirdHouseNotifyEnabled() {
		return Boolean.TRUE.equals(configManager.getRSProfileConfiguration(TimeTrackingConfig.CONFIG_GROUP,
				TimeTrackingConfig.BIRDHOUSE_NOTIFY, boolean.class));
	}

	/**
	 * Computes started_at for a farming patch by subtracting the total growth
	 * duration from the estimated completion time. Adjusts tick rate for Leagues worlds.
	 */
	private Instant computeFarmingStartedAt(PatchPrediction prediction, long doneEstimate) {
		int tickRate = prediction.getProduce().getTickrate();
		if (GameModeUtil.isLeaguesWorld(client)) {
			tickRate = tickRate / LEAGUES_GROWTH_RATE_DIVISOR;
		}
		int stages = prediction.getStages();
		if (tickRate <= 0 || stages <= 1) {
			return null;
		}
		long growthSeconds = (long) (stages - 1) * tickRate * 60;
		return Instant.ofEpochSecond(doneEstimate - growthSeconds);
	}
}
