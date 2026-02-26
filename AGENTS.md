# Mystix Plugin - Agent Instructions

## Cursor Cloud specific instructions

This is a Java/Gradle RuneLite plugin project with zero runtime service dependencies. JDK 21 is pre-installed and compatible (source target is Java 11).

### Build, test, and run

Standard commands are documented in `README.md`. Quick reference:

- **Build:** `./gradlew build` (compiles Java, runs all JUnit 4 tests)
- **Tests only:** `./gradlew test`
- **Check (lint):** No separate linter is configured; `./gradlew check` runs tests only
- **Run (GUI):** `./gradlew run` launches the full RuneLite client with the plugin — requires a display/GUI environment

### Caveats

- The Gradle wrapper (`./gradlew`) auto-downloads Gradle 8.10 on first run. No manual Gradle installation needed.
- Compiler warnings about deprecated API (`CompostTracker.java`) and unchecked operations (`MystixPluginTest.java`) are expected and do not indicate build failures.
- `node update.js` is a one-time code-generation script for copying RuneLite timer classes. The generated files are already committed; this script only needs to run when updating from upstream RuneLite (requires `../runelite/` clone).
- The `run` task launches the full OSRS/RuneLite client with GUI. In headless environments, only `build` and `test` are meaningful.
