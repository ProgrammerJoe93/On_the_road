# AGENTS.md

Guidance for coding agents working on On the Road, an Android map and turn-by-turn navigation app
with Android Auto support, built on MapLibre Native (via maplibre-compose) and Ferrostar. See
[README.md](README.md) for features, configuration and licences.

## Build and test

```sh
./gradlew :app:assembleDebug          # build
./gradlew :app:testDebugUnitTest      # JVM unit tests; no network needed
./gradlew :app:installDebug           # install on a device or emulator
```

- Needs JDK 17–21 and Android SDK platform 36 (`sdk.dir` in `local.properties`, which is
  machine-specific and git-ignored).
- Add `--offline` when dependencies are already cached; it is faster and avoids network flakiness.
- `LiveCameraPipelineTest` calls the real Valhalla and Overpass servers and is skipped unless
  `OTR_LIVE_TESTS=1` is set. Don't make other tests depend on the network.
- Unit tests run on the JVM with `unitTests.isReturnDefaultValues = true`, so `android.util.Log`
  is a no-op there. Keep logic testable by putting it in plain Kotlin functions (see
  `offline/OfflineAreas.kt`, `traffic/DigitrafficParser.kt`) rather than in Android classes.
- Controllers are tested with `runTest`, passing `backgroundScope` as their scope (see
  `RoadsideStopControllerTest`). `advanceUntilIdle()` doesn't run background work, so a test that
  checks nothing happened would pass vacuously; use `advanceTimeBy(…)` and `runCurrent()`.
- Ferrostar's state types (`NavigationState`, `TripState.Navigating`) can be built in JVM tests,
  but anything that calls into its native core (`FerrostarCore`, `RouteAdapter`) can't run there.

### Running the app

`adb` is not on `PATH`; use `~/Android/Sdk/platform-tools/adb`. An emulator AVD named `otr_test`
exists:

```sh
~/Android/Sdk/emulator/emulator -avd otr_test -no-window -no-audio -no-snapshot-save &
adb emu geo fix 24.94 60.17          # put the device in Helsinki
adb shell svc wifi disable; adb shell svc data disable   # test offline behaviour
```

When the app starts, the map may sit at its default position (open ocean, plain grey) until you
tap the "my location" button. Use `adb shell uiautomator dump /dev/tty` to read on-screen text,
and `adb exec-out screencap -p > shot.png` for screenshots. For Android Auto, see the DHU steps in
the README.

## Architecture

- **`AppGraph`** holds process-wide singletons (Ferrostar core, location, TTS, the navigation
  view model, controllers). The phone UI and the Android Auto session share them, so one
  navigation session shows on both. Anything a surface starts (`speedCameras.start()`,
  `startTraffic()`, `offlineMaps.start()`) must be started from both `MainScreen` and
  `OtrCarSession.onCreateScreen`, and must be idempotent.
- **Phone UI** (`ui/`) is Compose around Ferrostar's `DynamicallyOrientingNavigationView`; app
  overlays go in `withCustomOverlayView`, and map layers in the map content lambda. Layers shared
  with the car live in `ui/MapLayers.kt`.
- **Car UI** (`car/`) draws the same MapLibre map on the car surface (`CarAppNavigationView`), and
  uses Car App Library templates for everything else. Templates can't hold arbitrary views; only
  the map surface is Compose.
- Packages by feature: `nav` (session, arrival, speed), `cameras`, `chargers`, `traffic`
  (Finland only, Digitraffic), `search` (Photon), `offline` (MapLibre offline packs), `net`
  (connectivity), `night`, `voice`, `settings` (DataStore).
- Controllers expose `StateFlow`s and take their dependencies as constructor parameters (often
  lambdas like `isOnline: () -> Boolean`), which keeps them testable without Android.

## External services and constraints

All map, routing and data services are free public servers. Treat them as shared resources:

| Service | Used for | Notes |
|---|---|---|
| OpenFreeMap | Map styles and tiles | Tile URLs carry the data release (`/planet/<release>/…`), which changes about weekly; offline packs depend on this (see below). |
| Valhalla (FOSSGIS) | Routing | Fair use only; overridable with `valhallaUrl` in `local.properties`. |
| Overpass API | Speed cameras, chargers | Rate-limited; queries go through `OverpassCache` (disk cache, max 2 in parallel, retries with backoff). Never call `OverpassClient` directly. |
| Photon (komoot) | Search, reverse geocoding | Debounce typing. |
| Digitraffic | Live traffic, Finland | Requires the `Digitraffic-User` header. |

- **Don't add Russian-hosted endpoints or fallbacks** (e.g. `maps.mail.ru` Overpass mirrors), even
  as backups.
- Prefer few, larger, cached queries over many small ones. Keep a `User-Agent` on every request.
- Keep the OpenStreetMap attribution visible on the map (ODbL), and credit Fintraffic wherever
  traffic data is shown (CC BY 4.0).

## Gotchas

- **Offline packs and OpenFreeMap releases.** MapLibre stores one TileJSON per URL. When the map
  loads a new release online, older offline packs no longer match it and go blank offline.
  `OfflineMaps` records the tile template in each pack's metadata and re-downloads outdated areas
  (on Wi-Fi automatically), keeping the old generation until the new one completes. Don't replace
  this with `invalidate()`, because the old tiles would stay in the database.
- **MapLibre offline APIs** (`OfflineManager`, packs) are used from the main thread, and expose
  Compose snapshot state; outside composition, read them with `snapshotFlow`.
- **Destinations and reroutes.** `startNavigation` is also used for reroutes (closures, chargers);
  a new trip is detected by comparing the `Destination` with the active one.
- **Speed camera legality.** Camera warnings default to off in
  `SettingsRepository.CAMERA_WARNING_RESTRICTED_COUNTRIES`; keep that behaviour when touching
  settings.
- **Offline behaviour.** `NetworkMonitor.isOnline` means a validated internet connection. Code that
  can fall back to cached data should fail fast when offline, rather than spending retries.

## Conventions

- Kotlin, 2-space indentation, about 100-column lines, trailing commas in multi-line argument
  lists. There's no formatter configured; match the surrounding code.
- KDoc on classes and non-obvious functions explains *why*, briefly; comment density is moderate.
- User-visible text goes in `res/values/strings.xml`, never hard-coded. Keep it short and plain
  ("You're offline. Finding a route needs an internet connection.").
- Icons are Material vector drawables in `res/drawable` (`ic_*` for UI, `map_*` for map symbols).
- New settings go through `SettingsRepository` (DataStore), and appear in `ui/SettingsScreen.kt`
  (the phone has full-screen settings pages; the car has none).
- Add unit tests for parsing and decision logic; tests use JUnit 4 with backtick test names.
- Update the README's feature list when adding a user-facing feature.

## Git

- Commit messages have a short imperative subject ("Add …",
  "Show …") and a bulleted body describing user-visible changes.
- Don't commit `local.properties`, signing keys, or build output.
