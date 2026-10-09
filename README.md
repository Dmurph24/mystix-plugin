# Mystix

A RuneLite plugin that syncs your game data (farming timers, bank, skills, loadouts, loot, collection log, quests, diaries, combat achievements, boss kill counts, slayer, deaths and your Kingdom of Miscellania) to the Mystix app, and shows your current roadmap goal in game.

## Settings

Settings are grouped into **Data Syncing** (which data the plugin sends to the app; one toggle per source) and **Plugin Features** (the in-game current goal overlay with its progress bar, the goal completion popup and its sound, and the sync problem warning). Your Mystix App Key sits at the top.

The goal overlay updates as you play: XP, kills, drops and collection log unlocks move the bar immediately and the plugin syncs with the app in the background. For the completion sound, "Custom file" plays `~/.runelite/mystix/goal-complete.wav`.

If Mystix rejects your App Key (for example after you regenerate it in the app), the plugin tells you in red in the chatbox, and again on each login until it's fixed. It does the same when Mystix has been unreachable for a couple of minutes, and says so when syncing works again. "Sync problem warning" switches this to a banner at the top of the game window, or turns it off.
