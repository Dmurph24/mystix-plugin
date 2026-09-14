# Mystix

A RuneLite plugin that syncs your game data (farming timers, bank, skills, loadouts, loot, collection log, quests, diaries, combat achievements, boss kill counts, slayer and your Kingdom of Miscellania) to the Mystix app, and shows your current roadmap goal in game.

## Settings

Settings are grouped into **Data Syncing** (which data the plugin sends to the app; one toggle per source) and **Plugin Features** (the in-game current goal overlay with its progress bar, the goal completion popup and its sound). Your Mystix App Key sits at the top.

The goal overlay updates as you play: XP, kills, drops and collection log unlocks move the bar immediately and the plugin syncs with the app in the background. For the completion sound, "Custom file" plays `~/.runelite/mystix/goal-complete.wav`.
