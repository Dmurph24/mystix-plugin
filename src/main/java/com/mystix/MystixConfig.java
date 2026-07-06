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

	@ConfigItem(keyName = "syncBankMemory", name = "Bank Memory", description = "Sync your bank, seed vault, looting bag, and potion storage contents to Mystix.", position = 2)
	default boolean syncBankMemory() {
		return true;
	}

	@ConfigItem(keyName = "syncCollectionLog", name = "Collection Log", description = "Sync your Collection Log to Mystix. Reads automatically the first time you open your collection log each session, then updates as you unlock new items.", position = 3)
	default boolean syncCollectionLog() {
		return true;
	}

	@ConfigItem(keyName = "syncLoadouts", name = "Sync Loadouts", description = "Sync your active equipment and Inventory Setups loadouts to Mystix.", position = 4)
	default boolean syncLoadouts() {
		return true;
	}

	@ConfigItem(keyName = "syncLoot", name = "Loot Tracking", description = "Sync loot drops and kill counts to Mystix.", position = 5)
	default boolean syncLoot() {
		return true;
	}

	@ConfigItem(keyName = "syncQuests", name = "Quests", description = "Sync your quest progress to Mystix. Reads on login and updates in real time as you complete quests.", position = 6)
	default boolean syncQuests() {
		return true;
	}

	@ConfigItem(keyName = "showNextGoal", name = "Show current goal overlay", description = "Show your current (next uncompleted) goal from the selected roadmap as an overlay in the game window.", position = 7)
	default boolean showNextGoal() {
		return true;
	}
}
