package com.mystix;

import com.google.inject.Provides;
import javax.inject.Inject;
import javax.swing.SwingUtilities;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.Notifier;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.PluginDependency;
import net.runelite.api.Client;
import net.runelite.client.plugins.timetracking.TimeTrackingConfig;
import net.runelite.client.plugins.timetracking.TimeTrackingPlugin;
import com.mystix.runelite.farming.CompostTracker;
import com.mystix.runelite.farming.FarmingTracker;
import com.mystix.runelite.farming.FarmingWorld;
import com.mystix.runelite.farming.PaymentTracker;
import com.mystix.runelite.hunter.BirdHouseTracker;

@Slf4j
@PluginDescriptor(name = "Mystix")
@PluginDependency(TimeTrackingPlugin.class)
public class MystixPlugin extends Plugin
{
	@Inject
	private Client client;

	@Inject
	private TimerMonitor timerMonitor;

	@Inject
	private Notifier notifier;

	@Inject
	private ConfigManager configManager;

	@Inject
	private ItemManager itemManager;

	@Override
	protected void startUp() throws Exception
	{
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
				notifier
		);
		FarmingTracker farmingTracker = new FarmingTracker(
				client,
				itemManager,
				configManager,
				timeTrackingConfig,
				farmingWorld,
				notifier,
				compostTracker,
				paymentTracker
		);

		timerMonitor.initialize(farmingTracker, birdHouseTracker, farmingWorld);
		timerMonitor.start();

		SwingUtilities.invokeLater(() -> notifier.notify(
				"Mystix syncs enabled plugins to the external Mystix server outside of RuneLite. Disable any plugins in Configuration > Mystix you don't want synced to your app."
		));
	}

	@Override
	protected void shutDown() throws Exception
	{
		timerMonitor.stop();
		log.debug("Mystix stopped");
	}

	@Provides
	MystixConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(MystixConfig.class);
	}
}
