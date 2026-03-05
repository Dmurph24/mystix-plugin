# Mystix

A RuneLite plugin that syncs Time Tracking data to the Mystix app. Sends expected finish timestamps for farming patches and bird houses so the Mystix server can notify you when timers complete.

## Setup

1. Install the Mystix plugin from the Plugin Hub (or build from source).
2. Open the Mystix app and obtain your App Key.
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

## Building

Before building, copy the farming/hunter timer classes from RuneLite (required for per-patch timer names):

```bash
# 1. Clone RuneLite as a sibling directory (one-time)
cd .. && git clone https://github.com/runelite/runelite.git

# 2. Copy and patch the timer classes into this project
cd mystix-plugin && npm install && node update.js
```

Then build:

```bash
./gradlew build
```

The `update.js` script copies `farming/` and `hunter/` from RuneLite and patches package names/visibility. See [runelite-time-tracking-reminder](https://github.com/queicherius/runelite-time-tracking-reminder) for the pattern.

## Distribution (sending the plugin to someone else)

You can package the plugin in two ways:

### Option 1: Single runnable JAR (easiest for the recipient)

Build a fat JAR that includes RuneLite and the plugin. The recipient only needs Java 11+ installed; they do not need to install RuneLite separately.

```bash
./gradlew shadowJar
```

Send them the file **`build/libs/mystix-plugin-1.0-SNAPSHOT-all.jar`**. They run (on macOS the `--add-exports` flag is required for the client to use Apple’s window APIs):

```bash
java -ea --add-exports java.desktop/com.apple.eawt=ALL-UNNAMED -jar mystix-plugin-1.0-SNAPSHOT-all.jar
```

For Jagex accounts you may need developer mode:  
`java -ea --add-exports java.desktop/com.apple.eawt=ALL-UNNAMED -jar mystix-plugin-1.0-SNAPSHOT-all.jar --developer-mode`

### Option 2: Plugin JAR for sideloading (if they already use RuneLite)

Build the plugin-only JAR (this is produced by `./gradlew build`):

```bash
./gradlew build
```

Send them **`build/libs/mystix-plugin-1.0-SNAPSHOT.jar`** (the one *without* `-all`). They then:

1. Install and open RuneLite normally.
2. Enable developer mode: **Configuration** → **Developer tools** → enable **Developer mode**, or run RuneLite with `--developer-mode`.
3. Put the JAR in RuneLite’s sideloaded-plugins folder:
   - **macOS**: `~/Library/Application Support/RuneLite/sideloaded-plugins/`
   - **Windows**: `%USERPROFILE%\.runelite\sideloaded-plugins\`
   - **Linux**: `~/.runelite/sideloaded-plugins/`
4. Restart RuneLite. Mystix will appear in the plugin list.

## Running Locally

```bash
./gradlew run
```

Or use the **RuneLite (Mystix Plugin)** launch configuration in VS Code (Run and Debug).

### Jagex Accounts

If you use a Jagex account and can't log in when running from the IDE, you need to allow credential storage first. See [Using Jagex Accounts](https://github.com/runelite/runelite/wiki/Using-Jagex-Accounts) for details:

1. Run RuneLite with `--configure` (Mac: `/Applications/RuneLite.app/Contents/MacOS/RuneLite --configure`)
2. Add `--insecure-write-credentials` to **Client arguments**
3. Save, then launch RuneLite via the Jagex launcher once so it writes credentials to `~/.runelite/credentials.properties`
4. After that, running from the IDE will use the saved credentials
