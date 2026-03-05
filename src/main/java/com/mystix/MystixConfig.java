package com.mystix;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup("mystix")
public interface MystixConfig extends Config {
	@ConfigItem(keyName = "mystixAppKey", name = "Mystix App Key", description = "API key for authenticating with Mystix. Get this from the Mystix app after installing this plugin.", position = 0, secret = true)
	default String mystixAppKey() {
		return "";
	}

	@ConfigItem(keyName = "syncTimeTracking", name = "Farming Time Tracking", description = "Sync farming patches and bird houses to Mystix.", position = 1)
	default boolean syncTimeTracking() {
		return true;
	}

	@ConfigItem(keyName = "syncBankMemory", name = "Bank Memory", description = "Sync your bank contents to Mystix when you open your bank.", position = 2)
	default boolean syncBankMemory() {
		return true;
	}

	@ConfigItem(keyName = "syncWiseOldMan", name = "Sync with Wise Old Man", description = "Update your Wise Old Man profile automatically when you log in or out.", position = 3)
	default boolean syncWiseOldMan() {
		return true;
	}
}
