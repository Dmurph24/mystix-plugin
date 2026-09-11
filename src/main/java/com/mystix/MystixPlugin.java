package com.mystix;

import com.google.inject.Provides;
import com.mystix.api.MystixApiClient;
import java.awt.image.BufferedImage;
import java.util.concurrent.ScheduledExecutorService;
import javax.inject.Inject;
import javax.swing.SwingUtilities;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameStateChanged;
import net.runelite.client.Notifier;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.timetracking.TimeTrackingConfig;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.util.ImageUtil;
import com.mystix.runelite.farming.CompostTracker;
import com.mystix.runelite.farming.FarmingTracker;
import com.mystix.runelite.farming.FarmingWorld;
import com.mystix.runelite.farming.PaymentTracker;

@Slf4j
@PluginDescriptor(name = "Mystix", description = "Syncs Farming Timers, Bank, Skills, Loadout, Loot, Collection Log, Quest, Achievement Diary, Combat Achievement, and Boss Kill Count data to the Mystix mobile app.")
public class MystixPlugin extends Plugin {
	private static final String TEARS_CAVE_MESSAGE = "Your stories have entertained me. I will let you into the cave for a short time.";

	@Inject
	private Client client;

	@Inject
	private MystixConfig config;

	private TimerMonitor timerMonitor;

	@Inject
	private PlayerSkillsMonitor playerSkillsMonitor;

	@Inject
	private BankMemoryMonitor bankMemoryMonitor;

	@Inject
	private VaultMonitor vaultMonitor;

	@Inject
	private PotionStorageMonitor potionStorageMonitor;

	@Inject
	private LoadoutMonitor loadoutMonitor;

	@Inject
	private LootMonitor lootMonitor;

	@Inject
	private CollectionLogMonitor collectionLogMonitor;

	@Inject
	private QuestMonitor questMonitor;

	@Inject
	private AchievementDiaryMonitor achievementDiaryMonitor;

	@Inject
	private HouseLocationMonitor houseLocationMonitor;

	@Inject
	private KingdomMonitor kingdomMonitor;

	@Inject
	private CombatAchievementMonitor combatAchievementMonitor;

	@Inject
	private KillCountMonitor killCountMonitor;

	@Inject
	private SlayerMonitor slayerMonitor;

	@Inject
	private SlayerCatalogMonitor slayerCatalogMonitor;

	@Inject
	private SlayerRewardsMonitor slayerRewardsMonitor;

	@Inject
	private EventBus eventBus;

	@Inject
	private Notifier notifier;

	@Inject
	private ConfigManager configManager;

	@Inject
	private ItemManager itemManager;

	@Inject
	private MystixApiClient apiClient;

	@Inject
	private ScheduledExecutorService executorService;

	@Inject
	private ClientToolbar clientToolbar;

	@Inject
	private OverlayManager overlayManager;

	@Inject
	private RoadmapManager roadmapManager;

	@Inject
	private NextGoalOverlay nextGoalOverlay;

	@Inject
	private GoalProgressTracker goalProgressTracker;

	@Inject
	private GoalCompletionNotifier goalCompletionNotifier;

	@Inject
	private GoalCompletionOverlay goalCompletionOverlay;

	@Inject
	private GoalImageCache goalImageCache;

	private RoadmapPanel roadmapPanel;
	private NavigationButton navButton;

	@Override
	protected void startUp() throws Exception {
		log.debug("Mystix started");

		TimeTrackingConfig timeTrackingConfig = configManager.getConfig(TimeTrackingConfig.class);
		FarmingWorld farmingWorld = new FarmingWorld();
		CompostTracker compostTracker = new CompostTracker(client, farmingWorld, configManager);
		PaymentTracker paymentTracker = new PaymentTracker(client, configManager, farmingWorld);
		FarmingTracker farmingTracker = new FarmingTracker(
				client,
				itemManager,
				configManager,
				timeTrackingConfig,
				farmingWorld,
				notifier,
				compostTracker,
				paymentTracker);

		timerMonitor = new TimerMonitor(
				client, config, apiClient, configManager, executorService,
				farmingTracker, farmingWorld);

		eventBus.register(this);
		eventBus.register(timerMonitor);
		eventBus.register(playerSkillsMonitor);
		eventBus.register(bankMemoryMonitor);
		eventBus.register(vaultMonitor);
		eventBus.register(potionStorageMonitor);
		eventBus.register(loadoutMonitor);
		eventBus.register(lootMonitor);
		eventBus.register(collectionLogMonitor);
		eventBus.register(questMonitor);
		eventBus.register(achievementDiaryMonitor);
		eventBus.register(houseLocationMonitor);
		eventBus.register(kingdomMonitor);
		eventBus.register(combatAchievementMonitor);
		eventBus.register(killCountMonitor);
		eventBus.register(slayerMonitor);
		eventBus.register(slayerCatalogMonitor);
		eventBus.register(slayerRewardsMonitor);
		eventBus.register(goalProgressTracker);

		timerMonitor.start();
		lootMonitor.start();
		playerSkillsMonitor.start();

		// Local goal progress: monitors report what they upload, the tracker
		// layers it on the server's roadmap and asks for a re-read afterwards.
		roadmapManager.setRoadmapListener(goalProgressTracker::onServerRoadmap);
		roadmapManager.setRoadmapSetListener(goalProgressTracker::retainRoadmaps);
		playerSkillsMonitor.setUploadListener(goalProgressTracker::onSkillsUploaded);
		lootMonitor.setDropListener(goalProgressTracker::onLootDrop);
		lootMonitor.setSyncedListener(goalProgressTracker::onSourceSynced);
		collectionLogMonitor.setObtainedListener(goalProgressTracker::onCollectionLogItemObtained);
		collectionLogMonitor.setSyncedListener(goalProgressTracker::onSourceSynced);
		questMonitor.setSyncedListener(goalProgressTracker::onSourceSynced);
		questMonitor.setStatesListener(goalProgressTracker::onQuestStates);
		achievementDiaryMonitor.setSyncedListener(goalProgressTracker::onSourceSynced);
		achievementDiaryMonitor.setReadListener(goalProgressTracker::onDiaryRead);
		combatAchievementMonitor.setSyncedListener(goalProgressTracker::onSourceSynced);
		combatAchievementMonitor.setCompletedListener(goalProgressTracker::onCombatTasksCompleted);
		killCountMonitor.setSyncedListener(goalProgressTracker::onSourceSynced);
		timerMonitor.setSyncedListener(goalProgressTracker::onSourceSynced);
		timerMonitor.setTimersListener(goalProgressTracker::onFarmingTimers);
		bankMemoryMonitor.setSyncedListener(goalProgressTracker::onSourceSynced);
		bankMemoryMonitor.setPayloadListener(goalProgressTracker::onBankPayload);
		roadmapManager.startPeriodicRefresh();

		// Side-panel roadmap tab.
		roadmapPanel = new RoadmapPanel(roadmapManager, goalProgressTracker);
		BufferedImage icon = ImageUtil.loadImageResource(getClass(), "panel_icon.png");
		navButton = NavigationButton.builder()
				.tooltip("Mystix Roadmaps")
				.icon(icon)
				.priority(7)
				.panel(roadmapPanel)
				.build();
		clientToolbar.addNavigation(navButton);

		// Next-goal game overlay (rendered only while showNextGoal is enabled).
		overlayManager.add(nextGoalOverlay);
		// Roadmap progress drawn under the goal completion popup while it shows.
		overlayManager.add(goalCompletionOverlay);
	}

	@Override
	protected void shutDown() throws Exception {
		eventBus.unregister(this);
		eventBus.unregister(timerMonitor);
		eventBus.unregister(playerSkillsMonitor);
		eventBus.unregister(bankMemoryMonitor);
		eventBus.unregister(vaultMonitor);
		eventBus.unregister(potionStorageMonitor);
		eventBus.unregister(loadoutMonitor);
		eventBus.unregister(lootMonitor);
		eventBus.unregister(collectionLogMonitor);
		eventBus.unregister(questMonitor);
		eventBus.unregister(achievementDiaryMonitor);
		eventBus.unregister(houseLocationMonitor);
		eventBus.unregister(kingdomMonitor);
		eventBus.unregister(combatAchievementMonitor);
		eventBus.unregister(killCountMonitor);
		eventBus.unregister(slayerMonitor);
		eventBus.unregister(slayerCatalogMonitor);
		eventBus.unregister(slayerRewardsMonitor);
		eventBus.unregister(goalProgressTracker);

		roadmapManager.stopPeriodicRefresh();
		roadmapManager.setRoadmapListener(null);
		roadmapManager.setRoadmapSetListener(null);
		roadmapManager.setPanelListener(null);
		lootMonitor.setDropListener(null);
		lootMonitor.setSyncedListener(null);
		collectionLogMonitor.setObtainedListener(null);
		collectionLogMonitor.setSyncedListener(null);
		questMonitor.setSyncedListener(null);
		questMonitor.setStatesListener(null);
		achievementDiaryMonitor.setSyncedListener(null);
		achievementDiaryMonitor.setReadListener(null);
		combatAchievementMonitor.setSyncedListener(null);
		combatAchievementMonitor.setCompletedListener(null);
		killCountMonitor.setSyncedListener(null);
		timerMonitor.setSyncedListener(null);
		timerMonitor.setTimersListener(null);
		bankMemoryMonitor.setSyncedListener(null);
		bankMemoryMonitor.setPayloadListener(null);
		goalCompletionNotifier.clear();
		goalProgressTracker.clear();
		goalImageCache.clear();

		timerMonitor.stop();
		playerSkillsMonitor.stop();
		bankMemoryMonitor.stop();
		vaultMonitor.stop();
		potionStorageMonitor.stop();
		loadoutMonitor.stop();
		lootMonitor.stop();
		collectionLogMonitor.stop();
		questMonitor.stop();
		achievementDiaryMonitor.stop();
		houseLocationMonitor.stop();
		kingdomMonitor.stop();
		combatAchievementMonitor.stop();
		killCountMonitor.stop();
		slayerMonitor.stop();
		slayerCatalogMonitor.stop();
		slayerRewardsMonitor.stop();

		if (navButton != null) {
			clientToolbar.removeNavigation(navButton);
			navButton = null;
		}
		roadmapPanel = null;
		overlayManager.remove(nextGoalOverlay);
		overlayManager.remove(goalCompletionOverlay);
		roadmapManager.clear();
		log.debug("Mystix stopped");
	}

	/**
	 * Re-pushes every login sync the monitors normally send (timers, skills, bank,
	 * loadout, loot). Used when an App Key is entered mid-session. Each monitor
	 * schedules its own work on the executor / client thread, so this never
	 * blocks the caller.
	 */
	private void forceSyncAll() {
		// This is an explicit user re-sync: clear the request-dedupe cache so an
		// unchanged payload still gets sent (the monitors each reset their own
		// dedupe state in forceSync(), but the client-side cache is separate).
		apiClient.clearDedupeCache();
		timerMonitor.forceSync();
		playerSkillsMonitor.forceSync();
		bankMemoryMonitor.forceSync();
		loadoutMonitor.forceSync();
		lootMonitor.forceSync();
		collectionLogMonitor.forceSync();
		questMonitor.forceSync();
		achievementDiaryMonitor.forceSync();
		houseLocationMonitor.forceSync();
		kingdomMonitor.forceSync();
		combatAchievementMonitor.forceSync();
		killCountMonitor.forceSync();
		slayerMonitor.forceSync();
		slayerCatalogMonitor.forceSync();
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event) {
		if (event.getGameState() == GameState.LOGGED_IN) {
			// Load the roadmap almost immediately so it shows right after login
			// (a plain read, NOT a forced re-sync), then again once this session's
			// login syncs have landed so completion/progress is fresh.
			executorService.schedule(
					this::loadRoadmapQuietly, 1, java.util.concurrent.TimeUnit.SECONDS);
			executorService.schedule(
					this::loadRoadmapQuietly, 8, java.util.concurrent.TimeUnit.SECONDS);
		}
	}

	/**
	 * Fires a full re-sync the moment a complete App Key is entered while the
	 * player is already logged in. The per-monitor login syncs only run on the
	 * login transition, so users who paste their key mid-session would otherwise
	 * see nothing sync until their next login. Gated on a minimum key length so we
	 * don't fire on partial edits, and on being logged in (at the login screen the
	 * normal login sync already covers it).
	 */
	@Subscribe
	public void onConfigChanged(ConfigChanged event) {
		if (!SyncGuard.isCompleteAppKeyEntry(event.getGroup(), event.getKey(), event.getNewValue())) {
			return;
		}
		if (client.getGameState() != GameState.LOGGED_IN) {
			return;
		}
		log.debug("Mystix App Key entered while logged in; running full sync");
		forceSyncAll();
	}

	/**
	 * Reloads the roadmap without forcing a data re-sync: refreshes the open panel
	 * (which also warms the overlay cache) or, when the panel is closed, just warms
	 * the overlay cache directly.
	 */
	private void loadRoadmapQuietly() {
		RoadmapPanel panel = roadmapPanel;
		if (panel != null) {
			SwingUtilities.invokeLater(panel::loadRoadmaps);
		} else {
			roadmapManager.refreshAllQuietly();
		}
	}

	@Subscribe
	public void onChatMessage(ChatMessage event) {
		if (event.getType() != ChatMessageType.GAMEMESSAGE && event.getType() != ChatMessageType.DIALOG) {
			return;
		}
		String msg = event.getMessage();
		if (msg != null && msg.contains(TEARS_CAVE_MESSAGE)) {
			timerMonitor.onTearsOfGuthixCompleted();
		}
	}

	@Provides
	MystixConfig provideConfig(ConfigManager configManager) {
		return configManager.getConfig(MystixConfig.class);
	}
}
