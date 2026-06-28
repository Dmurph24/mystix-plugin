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
@PluginDescriptor(name = "Mystix", description = "Syncs Farming Timers, Bank, Skills, Loadout, and Loot data to the Mystix mobile app.")
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

		timerMonitor.start();
		lootMonitor.start();

		// Side-panel roadmap tab.
		roadmapPanel = new RoadmapPanel(roadmapManager, executorService, this::forceSyncAll);
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

		timerMonitor.stop();
		playerSkillsMonitor.stop();
		bankMemoryMonitor.stop();
		vaultMonitor.stop();
		potionStorageMonitor.stop();
		loadoutMonitor.stop();
		lootMonitor.stop();

		if (navButton != null) {
			clientToolbar.removeNavigation(navButton);
			navButton = null;
		}
		roadmapPanel = null;
		overlayManager.remove(nextGoalOverlay);
		roadmapManager.clear();
		log.debug("Mystix stopped");
	}

	/**
	 * Re-pushes every login sync the monitors normally send (timers, skills, bank,
	 * loadout, loot). Used by the panel's "Sync &amp; refresh" button before the
	 * backend recompute. Each monitor schedules its own work on the executor /
	 * client thread, so this never blocks the caller (the EDT).
	 */
	private void forceSyncAll() {
		timerMonitor.forceSync();
		playerSkillsMonitor.forceSync();
		bankMemoryMonitor.forceSync();
		loadoutMonitor.forceSync();
		lootMonitor.forceSync();
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event) {
		if (event.getGameState() == GameState.LOGGED_IN) {
			// Give the username/data a moment to settle after login, then load the
			// roadmaps so the panel populates without a manual "Reload". When the
			// panel is open its load also warms the overlay cache; when it's closed
			// we just warm the cache directly for the overlay.
			executorService.schedule(() -> {
				RoadmapPanel panel = roadmapPanel;
				if (panel != null) {
					SwingUtilities.invokeLater(panel::loadRoadmaps);
				} else {
					roadmapManager.refreshSelectedQuietly();
				}
			}, 8, java.util.concurrent.TimeUnit.SECONDS);
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
