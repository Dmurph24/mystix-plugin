# Mystix

A RuneLite plugin that syncs Time Tracking data to the Mystix app. Sends expected finish timestamps for farming patches and bird houses so the Mystix server can notify you when timers complete.

Mystix is the companion plugin for the Mystix mobile app. For app download links and developer build/testing instructions, see [DEVELOPMENT.md](DEVELOPMENT.md).

## How It Works

Mystix is a companion app for Old School RuneScape that receives gameplay data from this RuneLite plugin. The plugin sends data such as farming/bird house timer timestamps and skill levels to the Mystix server, which then delivers push notifications to your phone (e.g., when a farming patch is ready to harvest) and displays your character's stats.

All data transmission is user-initiated and requires an App Key that you generate in the Mystix app. No data is sent without the plugin being configured with a valid key, and sync toggles let you control exactly what is shared.

For full details on what information is collected and stored, see our [Privacy Policy](https://mystix.app/privacy).

## Setup

1. Install the Mystix plugin from the Plugin Hub.
2. Open the Mystix app and obtain your App Key (see [DEVELOPMENT.md](DEVELOPMENT.md#companion-app) for download links).
3. In RuneLite, go to **Configuration** > **Mystix** and paste your App Key into the **Mystix App Key** field.

## Sync Toggles

Each source plugin has an on/off toggle. Enable only the plugins you want synced to Mystix:

- **Sync Time Tracking** — Sync farming patches and bird houses to Mystix (default: on)

## Features

- **Time Tracking sync** — Sends expected finish timestamps for farming patches and bird houses to the Mystix server
  - The Mystix server uses these timestamps to schedule notifications when your timers complete (e.g., when a farming patch is ready to harvest)
  - Requires the RuneLite **Time Tracking** plugin to be enabled
- **Player Skills sync** — Automatically sends all player skill levels to the Mystix server when logging in or out
  - Allows the Mystix app to display and track your character's skill progression
  - Syncs on login and logout events when a valid App Key is configured
- **Roadmap side panel** — Adds a Mystix tab to the RuneLite side bar that lists your roadmaps and their goals
  - Pick which roadmap to view (you can have several)
  - **Sync & refresh** button re-pushes all your data (timers, skills, bank, loadout, loot) then recomputes the selected roadmap on the server and re-renders it
  - Logging out and back in helps push the latest progress; a completed goal can take a few minutes to update
- **Next goal overlay** (opt-in) — Enable **Show next goal overlay** in the plugin settings to display the next uncompleted goal of your selected roadmap (name + progress) in the game window

## Development

App download links and instructions for building, distributing, and running the plugin from source live in [DEVELOPMENT.md](DEVELOPMENT.md).
