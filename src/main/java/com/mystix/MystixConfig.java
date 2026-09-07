package com.mystix;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;

@ConfigGroup(MystixConfig.CONFIG_GROUP)
public interface MystixConfig extends Config {
	String CONFIG_GROUP = "mystix";
	String APP_KEY = "mystixAppKey";

	@ConfigSection(
			name = "Data Syncing",
			description = "Which game data the plugin sends to the Mystix app.",
			position = 1)
	String SYNC_SECTION = "syncSection";

	@ConfigSection(
			name = "Plugin Features",
			description = "In-game overlay, goal completion popup and sound.",
			position = 2)
	String FEATURES_SECTION = "featuresSection";

	@ConfigItem(
			keyName = APP_KEY,
			name = "Mystix App Key",
			description = "API key for authenticating with Mystix. Get this from the Mystix app after installing this plugin.",
			position = 0,
			secret = true)
	default String mystixAppKey() {
		return "";
	}

	// --- Data Syncing ---

	@ConfigItem(
			keyName = "syncTimeTracking",
			name = "Farming Time Tracking",
			description = "Sync farming patches and bird houses to Mystix.",
			section = SYNC_SECTION,
			position = 0)
	default boolean syncTimeTracking() {
		return true;
	}

	@ConfigItem(
			keyName = "syncBankMemory",
			name = "Bank Memory",
			description = "Sync your bank, seed vault, looting bag, and potion storage contents to Mystix.",
			section = SYNC_SECTION,
			position = 1)
	default boolean syncBankMemory() {
		return true;
	}

	@ConfigItem(
			keyName = "syncCollectionLog",
			name = "Collection Log",
			description = "Sync your Collection Log to Mystix. Reads automatically the first time you open your collection log each session, then updates as you unlock new items.",
			section = SYNC_SECTION,
			position = 2)
	default boolean syncCollectionLog() {
		return true;
	}

	@ConfigItem(
			keyName = "syncLoadouts",
			name = "Sync Loadouts",
			description = "Sync your active equipment and Inventory Setups loadouts to Mystix.",
			section = SYNC_SECTION,
			position = 3)
	default boolean syncLoadouts() {
		return true;
	}

	@ConfigItem(
			keyName = "syncLoot",
			name = "Loot Tracking",
			description = "Sync loot drops and kill counts to Mystix.",
			section = SYNC_SECTION,
			position = 4)
	default boolean syncLoot() {
		return true;
	}

	@ConfigItem(
			keyName = "syncQuests",
			name = "Quests",
			description = "Sync your quest progress to Mystix. Reads on login and updates in real time as you complete quests.",
			section = SYNC_SECTION,
			position = 5)
	default boolean syncQuests() {
		return true;
	}

	@ConfigItem(
			keyName = "syncAchievementDiaries",
			name = "Achievement Diaries",
			description = "Sync your achievement diary completion to Mystix. Reads on login and updates in real time as you complete diary tasks.",
			section = SYNC_SECTION,
			position = 6)
	default boolean syncAchievementDiaries() {
		return true;
	}

	@ConfigItem(
			keyName = "syncCombatAchievements",
			name = "Combat Achievements",
			description = "Sync your combat achievement completion to Mystix. Reads on login and updates in real time as you complete combat achievements.",
			section = SYNC_SECTION,
			position = 7)
	default boolean syncCombatAchievements() {
		return true;
	}

	@ConfigItem(
			keyName = "syncKillCounts",
			name = "Boss Kill Counts",
			description = "Sync your boss kill counts to Mystix, read from RuneLite's stored kill counts (the same source the !kc command uses). Updates on login and as you get new kills.",
			section = SYNC_SECTION,
			position = 8)
	default boolean syncKillCounts() {
		return true;
	}

	@ConfigItem(
			keyName = "syncSlayer",
			name = "Slayer",
			description = "Sync your slayer task, points, streak, block list and unlocks to Mystix. Reads on login and updates as tasks progress.",
			section = SYNC_SECTION,
			position = 9)
	default boolean syncSlayer() {
		return true;
	}

	// --- Plugin Features ---

	@ConfigItem(
			keyName = "showNextGoal",
			name = "Show current goal overlay",
			description = "Show your current (next uncompleted) goal from the selected roadmap as an overlay in the game window.",
			section = FEATURES_SECTION,
			position = 0)
	default boolean showNextGoal() {
		return true;
	}

	@ConfigItem(
			keyName = "showGoalProgress",
			name = "Show goal progress bar",
			description = "Show a progress bar with current / target numbers under the goal in the overlay.",
			section = FEATURES_SECTION,
			position = 1)
	default boolean showGoalProgress() {
		return true;
	}

	@ConfigItem(
			keyName = "showGoalPopup",
			name = "Goal completion popup",
			description = "Show an in-game popup (like the collection log one) when you complete a roadmap goal.",
			section = FEATURES_SECTION,
			position = 2)
	default boolean showGoalPopup() {
		return true;
	}

	@ConfigItem(
			keyName = "goalCompleteSound",
			name = "Goal completion sound",
			description = "Sound to play when you complete a roadmap goal. Custom file: ~/.runelite/mystix/goal-complete.wav",
			section = FEATURES_SECTION,
			position = 3)
	default GoalCompleteSound goalCompleteSound() {
		return GoalCompleteSound.IN_GAME;
	}
}
