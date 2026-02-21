package com.mystix;

import com.google.inject.Provides;
import javax.inject.Inject;
import javax.swing.SwingUtilities;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.Notifier;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.PluginDependency;
import net.runelite.client.plugins.timetracking.TimeTrackingPlugin;

@Slf4j
@PluginDescriptor(name = "Mystix")
@PluginDependency(TimeTrackingPlugin.class)
public class MystixPlugin extends Plugin
{
	@Inject
	private TimerMonitor timerMonitor;

	@Inject
	private Notifier notifier;

	@Override
	protected void startUp() throws Exception
	{
		log.debug("Mystix started");
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
