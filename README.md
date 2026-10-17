# On the Road

An open-source Android map and turn-by-turn navigation app with Android Auto support. It is built on
[MapLibre Native](https://maplibre.org/) and [Ferrostar](https://github.com/stadiamaps/ferrostar).

## Features

- **Map:** vector map from [OpenFreeMap](https://openfreemap.org/) (no API key needed).
- **Routing:** car routes from [Valhalla](https://github.com/valhalla/valhalla), with instructions in the device language.
- **Route choice:** up to three routes to each destination, compared by time, distance, main road
  and (in Finland) traffic events on them; avoid tolls, motorways or ferries.
- **Saved places:** home, work and named favourites, listed first in search on the phone and in
  the car.
- **Multi-stop trips:** add stops on the way when planning a trip; skip one during the trip if
  plans change.
- **Stops on the way:** while navigating, find fuel, EV chargers, food or rest areas ahead on the
  route (from OpenStreetMap), sorted by distance ahead, and add one to the trip in route order.
  Picking a charger during a trip works the same way.
- **Voice destinations:** Assistant's "navigate to …", "add a stop at …" and "directions to …" work
  on the phone and in Android Auto. A spoken name or address lists the matching places to pick
  from. During a trip, a new destination asks whether to replace the trip or add a stop, unless
  you asked for a stop.
- **Shared places:** share a place to the app from another app: a Google Maps, OpenStreetMap or
  Apple Maps link (Google's short links are followed online), coordinates, or an address to search
  for. It opens the routes to it, ready to start.
- **Turn-by-turn navigation:**
  - lane guidance and a speed-limit sign;
  - spoken instructions, with a choice of speech rate, or warnings only;
  - metric or imperial units, by region or chosen in settings;
  - rerouting when you leave the route;
  - through tunnels and other places without GPS, the position keeps moving along the route at your
    last speed for up to 2 minutes, so the instructions keep up;
  - background navigation through a foreground service.
- **Speed camera warnings:**
  - camera data comes from OpenStreetMap through the Overpass API;
  - a camera is matched to the route by its distance from the route and its direction;
  - cameras on parallel and crossing roads are rejected;
  - a chime, a spoken warning and an on-screen badge, plus a car alert in Android Auto.
- **Live traffic (Finland):** from Fintraffic's open Digitraffic service:
  - accidents, closures and roadworks on the map, with Finnish details and English summaries;
  - poor and very poor driving conditions on main roads, from Fintraffic's road weather
    forecasts, with a slippery road (or general danger) sign while you drive through them;
  - slow traffic at measuring stations;
  - spoken warnings for events on your route;
  - routes avoid closed roads.
- **Offline use:**
  - download map areas picked on the map, with the EV chargers in them;
  - the map along each route is saved when navigation starts (the last 3 routes);
  - saved areas update on Wi-Fi when OpenFreeMap publishes new map data;
  - speed cameras, chargers and recently viewed map areas come from the cache when offline;
  - search falls back to saved places and recent destinations;
  - routing still needs a connection.
- **Android Auto:**
  - the MapLibre map on the car screen;
  - Ferrostar's `NavigationTemplate`;
  - voice destinations (see above), with the search results and the question on the car screen;
  - trip updates (`updateTrip`) that let supported cars show the next turn in the cluster or HUD.

## Build

Requirements:
- JDK 17–21. If your default `java` is newer, point Gradle at a supported JDK with `org.gradle.java.home=<path>` in `~/.gradle/gradle.properties`.
- The Android SDK with platform 36. Set `sdk.dir` in `local.properties`.

```sh
./gradlew :app:assembleDebug        # build
./gradlew :app:installDebug         # install on a connected device or emulator
./gradlew :app:testDebugUnitTest    # unit tests (offline)
```

The camera pipeline also has a live test, which calls the real Valhalla and Overpass servers:

```sh
OTR_LIVE_TESTS=1 ./gradlew :app:testDebugUnitTest --tests '*LiveCameraPipelineTest*' -i
```

### Configuration

Add these to `local.properties`:

| Key | Default | Purpose |
|---|---|---|
| `valhallaUrl` | `https://valhalla1.openstreetmap.de/route` | Valhalla route endpoint |

The default routing server is the FOSSGIS public server, which is fair-use only. For regular use,
self-host Valhalla or use a commercial endpoint.

## Using the app

1. Grant the location permission.
2. Long-press the map to choose a destination.
3. Tap **Start**.

In debug builds, the **Simulate drive** switch replays the route instead of using GPS.

To test the app in the emulator without driving, set its position:
```sh
adb emu geo fix <lon> <lat>
```

## Android Auto

To test Android Auto with the Desktop Head Unit (DHU):

1. On the phone or emulator, install Android Auto. In Android Auto's settings, tap the version
   number repeatedly to enable developer mode. Then use the overflow menu to enable
   *Unknown sources* and *Start head unit server*.
2. Install the DHU through the SDK Manager (`extras;google;auto`).
3. Forward the head unit port and start the DHU:
   ```sh
   adb forward tcp:5277 tcp:5277
   ~/Android/Sdk/extras/google/auto/desktop-head-unit
   ```
4. The app appears in the DHU launcher.
   - Start navigation from the phone, or with a `geo:` intent (`geo:0,0?q=<place>` searches;
     add `&intent=add_a_stop` to add a stop).
   - In the car, *auto drive* enables the simulated drive.

The DHU can also emulate an instrument cluster: see its `--config` options and the configuration
files in the DHU directory. Whether a real car shows the next turn in its cluster or HUD depends on
the carmaker.

## Speed camera warnings

- **Legality:** speed camera warnings are restricted in some countries. The setting defaults to off
  in the countries listed in `SettingsRepository.CAMERA_WARNING_RESTRICTED_COUNTRIES`. That list is
  a conservative default, not legal advice.
- **Data load:** each new route queries the public Overpass API. Responses are cached on disk for
  7 days, and busy servers are retried with backoff. However, the public servers are meant for
  human-scale use. If the app gets real users, serve a prebuilt camera dataset (for example, a
  daily extract) or run your own Overpass instance.

## Project layout

```
app/src/main/java/com/joegineered/ontheroad/
  AppGraph.kt          shared singletons (Ferrostar core, location, TTS, view model)
  config/              map style, routing endpoint, instruction language
  nav/                 the navigation session shared by the phone and car
  ui/                  phone UI (Compose + Ferrostar's MapLibre navigation view)
  car/                 Android Auto (CarAppService, navigation screen, search, chargers)
  cameras/             speed cameras: Overpass client and cache, route matching, alert engine
  chargers/            EV chargers from OpenStreetMap, fetched per map tile
  traffic/             live traffic for Finland from Digitraffic
  search/              Photon address search, saved places and recent destinations
  stops/               Fuel, chargers, food and rest areas along the route
  offline/             offline map areas and route corridors (MapLibre offline packs)
  net/                 connectivity monitoring
  night/               day/night switching from the sun's position
  voice/               spoken guidance and warnings
  settings/            DataStore preferences
```

## Licences and attribution

On the Road is released under the BSD 3-Clause License; see [LICENSE](LICENSE).


| Component | Licence |
|---|---|
| MapLibre | BSD-2-Clause |
| Ferrostar | BSD-3-Clause |
| Valhalla | MIT |
| AndroidX Car App Library | Apache-2.0 |

Map data, routing data, speed camera and EV charger data are © OpenStreetMap contributors
(ODbL); the map's attribution button must stay visible. Live traffic data is from Fintraffic /
digitraffic.fi, licensed CC BY 4.0, and is credited on each traffic card.
