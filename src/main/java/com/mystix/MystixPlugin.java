package com.mystix;

import com.google.inject.Provides;
import javax.inject.Inject;
import javax.swing.SwingUtilities;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Player;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameStateChanged;
import net.runelite.client.Notifier;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.PluginDependency;
import net.runelite.client.plugins.timetracking.TimeTrackingConfig;
import net.runelite.client.plugins.timetracking.TimeTrackingPlugin;
import com.mystix.runelite.farming.CompostTracker;
import com.mystix.runelite.farming.FarmingTracker;
import com.mystix.runelite.farming.FarmingWorld;
import com.mystix.runelite.farming.PaymentTracker;
import com.mystix.runelite.hunter.BirdHouseTracker;
import com.mystix.wom.WomSyncService;

@Slf4j
@PluginDescriptor(name = "Mystix", description = "Syncs Farming Timers, Bank, Skills, and Loadout data to the Mystix mobile app.")
@PluginDependency(TimeTrackingPlugin.class)
public class MystixPlugin extends Plugin {
	private static final String TEARS_CAVE_MESSAGE = "Your stories have entertained me. I will let you into the cave for a short time.";

	@Inject
	private Client client;

	@Inject
	private MystixConfig config;

	@Inject
	private TimerMonitor timerMonitor;

	@Inject
	private PlayerSkillsMonitor playerSkillsMonitor;

	@Inject
	private BankMemoryMonitor bankMemoryMonitor;

	@Inject
	private LoadoutMonitor loadoutMonitor;

	@Inject
	private WomSyncService womSyncService;

	@Inject
	private EventBus eventBus;

	@Inject
	private Notifier notifier;

	@Inject
	private ConfigManager configManager;

	@Inject
	private ItemManager itemManager;

	private String lastUsername;

	@Override
	protected void startUp() throws Exception {
		log.debug("Mystix started");

		TimeTrackingConfig timeTrackingConfig = configManager.getConfig(TimeTrackingConfig.class);
		FarmingWorld farmingWorld = new FarmingWorld();
		CompostTracker compostTracker = new CompostTracker(client, farmingWorld, configManager);
		PaymentTracker paymentTracker = new PaymentTracker(client, configManager, farmingWorld);
		BirdHouseTracker birdHouseTracker = new BirdHouseTracker(
				client,
				itemManager,
				configManager,
				timeTrackingConfig,
				notifier);
		FarmingTracker farmingTracker = new FarmingTracker(
				client,
				itemManager,
				configManager,
				timeTrackingConfig,
				farmingWorld,
				notifier,
				compostTracker,
				paymentTracker);

		timerMonitor.initialize(farmingTracker, birdHouseTracker, farmingWorld);
		timerMonitor.start();

		playerSkillsMonitor.start();
		bankMemoryMonitor.start();
		loadoutMonitor.start();

		eventBus.register(this);

		SwingUtilities.invokeLater(() -> notifier.notify(
				"Mystix syncs enabled plugins to the external Mystix server outside of RuneLite. Disable any plugins in Configuration > Mystix you don't want synced to your app."));
	}

	@Override
	protected void shutDown() throws Exception {
		eventBus.unregister(this);
		timerMonitor.stop();
		playerSkillsMonitor.stop();
		bankMemoryMonitor.stop();
		loadoutMonitor.stop();
		lastUsername = null;
		log.debug("Mystix stopped");
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event) {
		if (!config.syncWiseOldMan()) {
			return;
		}
		if (GameModeUtil.isSpecialGameMode(client)) {
			log.debug("WOM sync skipped: special game mode detected (Leagues, DMM, etc.)");
			return;
		}

		GameState state = event.getGameState();

		if (state == GameState.LOGGED_IN) {
			Player local = client.getLocalPlayer();
			if (local != null && local.getName() != null && !local.getName().isBlank()) {
				lastUsername = local.getName();
				womSyncService.updatePlayer(lastUsername);
			}
		} else if (state == GameState.LOGIN_SCREEN || state == GameState.HOPPING) {
			if (lastUsername != null) {
				womSyncService.updatePlayer(lastUsername);
				lastUsername = null;
			}
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
