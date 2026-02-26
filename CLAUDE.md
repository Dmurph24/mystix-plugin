# Mystix Plugin - Claude Guidelines

See parent [`../CLAUDE.md`](../CLAUDE.md) for monorepo layout and cross-project paths.

## Cross-Project Paths

This project lives at `mystix-plugin/` within the monorepo at `/Users/danielmurphy/Documents/mystix/`.

- Backend: `../mystix-backend/`
- Shared monorepo rules (Cursor): `../.cursor/rules/monorepo-overview.mdc`

Java source: `src/main/java/com/mystix/`
Java tests: `src/test/java/com/mystix/`

---

## Plugin-hub Compliance

- **No new third-party dependencies.** Use only Java standard library and transitive RuneLite client dependencies (e.g. `java.net.http.HttpClient`). New dependencies require cryptographic hash verification and extend review time.
- **Java only.** No Kotlin, Scala, or other JVM languages. No reflection, JNI, subprocesses, or downloading/running external code.
- **License:** BSD 2-Clause for plugin-hub compatibility.
- **Rejected features:** Do not expose player information over HTTP in a way that broadcasts to others. Mystix is user-initiated sync with the user's own API key.

---

## RuneLite Patterns

- Use `@PluginDescriptor`, `@Inject`, `@Provides` for plugin setup.
- Config interfaces extend `Config` with `@ConfigGroup("mystix")` and `@ConfigItem` for each setting.
- One sync toggle per source plugin: `syncTimeTracking`, `sync{PluginName}` for future plugins.

---

## Time Tracking Integration

- Inject `FarmingTracker`, `BirdHouseTracker`, `ClockManager` from RuneLite (core Time Tracking plugin).
- Send bulk timers sync to `POST /api/runelite/timers/` with payload: `{timers: [{name, completed_at, notifications_enabled, player_username}]}`. Only send when timer data changes.
- Respect `syncTimeTracking` config toggle before syncing any Time Tracking data.

---

## Tests

- Write unit tests for `MystixConfig` (defaults, key retrieval).
- Write unit tests for `MystixApiClient` (URL, headers, payload shape; mock `HttpClient`).
- Write unit tests for `TimerMonitor` (sync toggle, deduplication).
- Keep `MystixPluginTest` as the RuneLite launcher for manual testing (`run` task).

---

## Documentation

- Update `README.md` when adding new features.
- Document setup (App Key from Mystix app), sync toggles, and feature list.

---

## GitHub Releases

```bash
gh release list --limit 5          # check latest tag
gh release create v0.1.4 --title "v0.1.4" --notes "Release notes here"
```
