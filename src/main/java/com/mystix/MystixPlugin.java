package com.mystix;

import com.google.inject.Provides;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.events.ChatMessage;
import net.runelite.client.Notifier;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.timetracking.TimeTrackingConfig;
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

	@Inject
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

		timerMonitor.initialize(farmingTracker, farmingWorld);

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
		log.debug("Mystix stopped");
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
