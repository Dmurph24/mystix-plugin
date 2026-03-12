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
import net.runelite.api.events.GameStateChanged;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.plugins.timetracking.TimeTrackingConfig;
import com.mystix.runelite.farming.CropState;
import com.mystix.runelite.farming.FarmingPatch;
import com.mystix.runelite.farming.FarmingTracker;
import com.mystix.runelite.farming.PatchPrediction;
import com.mystix.runelite.farming.Produce;
import com.mystix.runelite.hunter.BirdHouse;
import com.mystix.runelite.hunter.BirdHouseData;
import com.mystix.runelite.hunter.BirdHouseSpace;
import com.mystix.runelite.hunter.BirdHouseState;
import com.mystix.runelite.hunter.BirdHouseTracker;

@Slf4j
@Singleton
public class TimerMonitor {
	private static final int INITIAL_DELAY_SECONDS = 10;
	private static final int SYNC_INTERVAL_SECONDS = 45;
	private static final int LOGIN_SYNC_DELAY_SECONDS = 3;
	private static final int TEARS_OF_GUTHIX_RESET_DAYS = 7;
	private static final int LEAGUES_GROWTH_RATE_DIVISOR = 5;

	private final Client client;
	private final MystixConfig config;
	private final MystixApiClient apiClient;
	private final ConfigManager configManager;
	private final ScheduledExecutorService executorService;
	private final EventBus eventBus;

	private FarmingTracker farmingTracker;
	private BirdHouseTracker birdHouseTracker;
	private com.mystix.runelite.farming.FarmingWorld farmingWorld;
	private ScheduledFuture<?> scheduledFuture;
	private String lastSentSnapshot;
	private volatile Instant tearsOfGuthixNextReset = null;
	private GameState previousGameState = GameState.UNKNOWN;

	@Inject
	public TimerMonitor(
			Client client,
			MystixConfig config,
			MystixApiClient apiClient,
			ConfigManager configManager,
			ScheduledExecutorService executorService,
			EventBus eventBus) {
		this.client = client;
		this.config = config;
		this.apiClient = apiClient;
		this.configManager = configManager;
		this.executorService = executorService;
		this.eventBus = eventBus;
	}

	public void initialize(FarmingTracker farmingTracker, BirdHouseTracker birdHouseTracker,
			com.mystix.runelite.farming.FarmingWorld farmingWorld) {
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
		eventBus.register(this);
		scheduledFuture = executorService.scheduleAtFixedRate(
				this::sync,
				INITIAL_DELAY_SECONDS,
				SYNC_INTERVAL_SECONDS,
				TimeUnit.SECONDS);
		log.info("TimerMonitor started; first sync in {}s, then every {}s", INITIAL_DELAY_SECONDS, SYNC_INTERVAL_SECONDS);
	}

	public void stop() {
		eventBus.unregister(this);
		if (scheduledFuture != null) {
			scheduledFuture.cancel(false);
			scheduledFuture = null;
		}
		lastSentSnapshot = null;
		tearsOfGuthixNextReset = null;
		previousGameState = GameState.UNKNOWN;
		log.debug("TimerMonitor stopped");
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
		log.info("Tears of Guthix completed; next reset at {}", tearsOfGuthixNextReset);
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
		birdHouseTracker.loadFromConfig();
		birdHouseTracker.updateCompletionTime();
		farmingTracker.updateCompletionTime();

		boolean syncEnabled = config.syncTimeTracking();
		List<TimerSyncItem> timers = new ArrayList<>();

		collectFarmingTimers(timers, playerUsername, syncEnabled);
		collectBirdHouseTimers(timers, playerUsername, syncEnabled);
		collectTearsOfGuthixTimer(timers, playerUsername, syncEnabled);

		String snapshot = TimersSyncPayload.toJson(timers);
		if (!snapshot.equals(lastSentSnapshot)) {
			lastSentSnapshot = snapshot;
			log.info("Mystix syncing {} timer(s) for {}", timers.size(), playerUsername);
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
	 * Iterates bird house spaces, builds a TimerSyncItem for each seeded house
	 * that has not yet completed, and appends them to the timers list.
	 */
	private void collectBirdHouseTimers(List<TimerSyncItem> timers, String playerUsername, boolean syncEnabled) {
		boolean birdHouseNotify = syncEnabled && isBirdHouseNotifyEnabled();

		for (var bhEntry : birdHouseTracker.getBirdHouseData().entrySet()) {
			BirdHouseSpace space = bhEntry.getKey();
			BirdHouseData data = bhEntry.getValue();

			if (BirdHouseState.fromVarpValue(data.getVarp()) != BirdHouseState.SEEDED) {
				continue;
			}

			long spaceCompletionTime = data.getTimestamp() + BirdHouseTracker.BIRD_HOUSE_DURATION;
			if (spaceCompletionTime <= Instant.now().getEpochSecond()) {
				continue;
			}

			Instant completedAt = Instant.ofEpochSecond(spaceCompletionTime);
			Instant startedAt = Instant.ofEpochSecond(data.getTimestamp());
			BirdHouse birdHouse = BirdHouse.fromVarpValue(data.getVarp());
			Integer birdHouseEntityId = birdHouse != null ? birdHouse.getItemID() : null;
			String entityName = birdHouse != null ? birdHouse.getName() : "Bird House";

			timers.add(new TimerSyncItem(
					"bird house",
					space.getName(),
					entityName,
					completedAt,
					birdHouseNotify,
					playerUsername,
					startedAt,
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
