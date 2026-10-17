package com.joegineered.ontheroad

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import androidx.core.content.ContextCompat
import android.util.Log
import com.joegineered.ontheroad.cameras.CameraAlertEngine
import com.joegineered.ontheroad.cameras.CameraWarningAnnouncer
import com.joegineered.ontheroad.cameras.OverpassCache
import com.joegineered.ontheroad.cameras.OverpassClient
import com.joegineered.ontheroad.cameras.SpeedCameraController
import com.joegineered.ontheroad.cameras.SpeedCameraRepository
import com.joegineered.ontheroad.chargers.ChargerController
import com.joegineered.ontheroad.chargers.ChargerRepository
import com.joegineered.ontheroad.config.AppConfig
import com.joegineered.ontheroad.cameras.LatLng
import com.joegineered.ontheroad.nav.DeadReckoner
import com.joegineered.ontheroad.nav.DeadReckoningLocationProvider
import com.joegineered.ontheroad.nav.OtrNavigationViewModel
import com.joegineered.ontheroad.nav.RouteAvoidance
import com.joegineered.ontheroad.nav.ShortLinkResolver
import com.joegineered.ontheroad.nav.valhallaOptions
import com.joegineered.ontheroad.nav.TrafficAwareRouteProvider
import com.joegineered.ontheroad.traffic.DigitrafficClient
import com.joegineered.ontheroad.traffic.TrafficController
import com.joegineered.ontheroad.traffic.trafficAnnouncement
import com.joegineered.ontheroad.net.NetworkMonitor
import com.joegineered.ontheroad.night.NightModeController
import com.joegineered.ontheroad.offline.OfflineMaps
import com.joegineered.ontheroad.search.PhotonGeocoder
import com.joegineered.ontheroad.search.QuickPlace
import com.joegineered.ontheroad.search.RecentDestinations
import com.joegineered.ontheroad.search.SavedPlaces
import com.joegineered.ontheroad.search.quickPlaces
import com.joegineered.ontheroad.settings.SettingsRepository
import com.joegineered.ontheroad.stops.RoadsideStopController
import com.joegineered.ontheroad.voice.NavigationVoice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectIndexed
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import com.stadiamaps.ferrostar.composeui.notification.DefaultForegroundNotificationBuilder
import com.stadiamaps.ferrostar.core.AndroidTtsObserver
import com.stadiamaps.ferrostar.core.FerrostarCore
import com.stadiamaps.ferrostar.core.http.OkHttpClientProvider.Companion.toOkHttpClientProvider
import com.stadiamaps.ferrostar.core.location.NavigationLocationProvider
import com.stadiamaps.ferrostar.core.location.SimulatedLocationProvider
import com.stadiamaps.ferrostar.core.service.FerrostarForegroundServiceManager
import com.stadiamaps.ferrostar.googleplayservices.FusedNavigationLocationProvider
import com.stadiamaps.ferrostar.ui.formatters.LocalizedDistanceFormatter
import java.time.Duration
import okhttp3.OkHttpClient
import org.maplibre.compose.offline.getOfflineManager
import uniffi.ferrostar.CourseFiltering
import uniffi.ferrostar.NavigationControllerConfig
import uniffi.ferrostar.RouteDeviationTracking
import uniffi.ferrostar.TripState
import uniffi.ferrostar.WaypointAdvanceMode
import uniffi.ferrostar.stepAdvanceDistanceEntryAndExit
import uniffi.ferrostar.stepAdvanceDistanceToEndOfStep

/**
 * Process-wide singletons. The phone UI and the Android Auto session share these, so a navigation
 * session started on either surface shows on both.
 */
object AppGraph {
  private const val TAG = "AppGraph"

  private lateinit var appContext: Context

  fun init(context: Context) {
    if (::appContext.isInitialized) return
    appContext = context.applicationContext
    voice.start()
    syncSettings()
  }

  /**
   * Applies settings that live outside Compose. The voice guidance setting and the mute button
   * stay in sync both ways, so muting during navigation is remembered for the next trip.
   */
  private fun syncSettings() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    scope.launch {
      settings.unitSystem.collect { units ->
        AppConfig.unitSystem.value = units
        notificationDistanceFormatter.distanceMeasurementSystemOverride =
            AppConfig.distanceMeasurementSystem()
      }
    }
    scope.launch {
      // A change replans the route preview; the first value is the stored setting.
      settings.routeAvoidance.distinctUntilChanged().collectIndexed { i, avoid ->
        routeAvoidance = avoid
        if (i > 0) navigationViewModel.refreshPreview()
      }
    }
    scope.launch { settings.turnInstructionsSpoken.collect { voice.turnInstructionsSpoken = it } }
    scope.launch { settings.speechRate.collect { voice.speechRate = it.multiplier } }
    scope.launch {
      settings.voiceGuidanceEnabled.collect { enabled ->
        if (ttsObserver.isMuted == enabled) ttsObserver.setMuted(!enabled)
      }
    }
    scope.launch {
      ttsObserver.muteState.drop(1).collect { muted ->
        if (settings.voiceGuidanceEnabled.first() == muted) {
          settings.setVoiceGuidanceEnabled(!muted)
        }
      }
    }
  }

  val httpClient: OkHttpClient by lazy {
    OkHttpClient.Builder().callTimeout(Duration.ofSeconds(20)).build()
  }

  /** Overpass queries can take tens of seconds on the public server. */
  private val overpassHttpClient: OkHttpClient by lazy {
    httpClient.newBuilder().callTimeout(Duration.ofSeconds(90)).readTimeout(Duration.ofSeconds(90)).build()
  }

  val network: NetworkMonitor by lazy { NetworkMonitor(appContext) }

  /** In app storage rather than the cache, so data saved with offline areas isn't cleared. */
  val overpass: OverpassCache by lazy {
    OverpassCache(OverpassClient(overpassHttpClient), appContext.filesDir, isOnline = { network.isOnline })
  }

  private val chargerRepository: ChargerRepository by lazy { ChargerRepository(overpass) }

  val offlineMaps: OfflineMaps by lazy {
    OfflineMaps(
        manager = getOfflineManager(appContext),
        httpClient = httpClient,
        network = network.status,
        chargers = chargerRepository,
        saveRouteMaps = settings.saveRouteMaps,
        tripStarts = navigationViewModel.tripStarts,
    )
  }

  /** GPS, kept moving along the route through tunnels; see [DeadReckoner]. */
  val liveLocation: DeadReckoningLocationProvider by lazy {
    DeadReckoningLocationProvider(FusedNavigationLocationProvider(appContext)) {
      ferrostarCore.state.value.takeIf { it.tripState is TripState.Navigating }?.routeGeometry
    }
  }

  val locationProvider: NavigationLocationProvider by lazy {
    NavigationLocationProvider(
        liveProviding = liveLocation,
        simulatedProvider = SimulatedLocationProvider(warpFactor = 2u),
    )
  }

  val ttsObserver: AndroidTtsObserver by lazy { AndroidTtsObserver(appContext) }

  val voice: NavigationVoice by lazy { NavigationVoice(appContext, ttsObserver) }

  /** Distances in the navigation notification; its units follow the setting. */
  private val notificationDistanceFormatter: LocalizedDistanceFormatter by lazy {
    LocalizedDistanceFormatter(distanceMeasurementSystemOverride = AppConfig.distanceMeasurementSystem())
  }

  /** The roads-to-avoid setting, kept current by [syncSettings] for route requests. */
  @Volatile private var routeAvoidance = RouteAvoidance()

  /** Valhalla, avoiding roads that live traffic reports as closed. */
  private val routeProvider: TrafficAwareRouteProvider by lazy {
    TrafficAwareRouteProvider(
        endpoint = AppConfig.valhallaUrl,
        profile = AppConfig.VALHALLA_COSTING,
        // Read per request, so spoken distances ("in 500 metres") and roads to avoid follow the
        // settings.
        baseOptions = { alternates ->
          valhallaOptions(
              language = AppConfig.instructionLanguage(),
              metric = AppConfig.usesMetricUnits,
              avoid = routeAvoidance,
              alternates = alternates,
          )
        },
        httpClient = httpClient.toOkHttpClientProvider(),
        closures = { traffic.closures() },
    )
  }

  val ferrostarCore: FerrostarCore by lazy {
    FerrostarCore(
            routeProvider,
            httpClient = httpClient.toOkHttpClientProvider(),
            locationProvider = locationProvider,
            foregroundServiceManager =
                FerrostarForegroundServiceManager(
                    appContext,
                    DefaultForegroundNotificationBuilder(
                        appContext,
                        distanceFormatter = notificationDistanceFormatter,
                    ),
                ),
            navigationControllerConfig = navigationControllerConfig(),
        )
        .also {
          // Must be set before the view model is created: it captures the mute state flow.
          it.spokenInstructionObserver = voice.instructionObserver
        }
  }

  val navigationViewModel: OtrNavigationViewModel by lazy {
    OtrNavigationViewModel(
        ferrostarCore,
        locationProvider,
        recentDestinations,
        planRoutes = { origin, waypoints -> routeProvider.getRoutes(origin, waypoints, alternates = 2) },
        trafficEventsOnRoute = { route -> traffic.eventsOnRoute(route.map { LatLng(it.lat, it.lng) }).size },
        isSpeaking = { ttsObserver.tts?.isSpeaking == true },
        isOnline = { network.isOnline },
    )
  }

  val settings: SettingsRepository by lazy { SettingsRepository(appContext) }

  val geocoder: PhotonGeocoder by lazy { PhotonGeocoder(httpClient) }

  val shortLinks: ShortLinkResolver by lazy { ShortLinkResolver(httpClient) }

  val chargers: ChargerController by lazy {
    ChargerController(chargerRepository, CoroutineScope(SupervisorJob() + Dispatchers.IO))
  }

  val traffic: TrafficController by lazy {
    TrafficController(
        client = DigitrafficClient(httpClient),
        settings = settings,
        navigationState = ferrostarCore.state,
        announce = { warning -> voice.announce(trafficAnnouncement(appContext, warning)) },
    )
  }

  private var trafficStarted = false

  /** Starts live traffic refreshes and rerouting around closures found on the active route. */
  @Synchronized
  fun startTraffic() {
    if (trafficStarted) return
    trafficStarted = true
    traffic.start()
    CoroutineScope(SupervisorJob() + Dispatchers.Main).launch {
      traffic.closureOnRoute.collect {
        voice.announce(appContext.getString(R.string.rerouting_closure))
        navigationViewModel.rerouteToActiveDestination()
      }
    }
  }

  val nightMode: NightModeController by lazy {
    NightModeController(
        settings = settings,
        location = navigationViewModel.location,
        systemDarkTheme = {
          (appContext.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
              Configuration.UI_MODE_NIGHT_YES
        },
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    )
  }

  val recentDestinations: RecentDestinations by lazy { RecentDestinations(appContext) }

  val savedPlaces: SavedPlaces by lazy { SavedPlaces(appContext) }

  /** Saved, then recent places, listed before typing a search and searched offline. */
  val quickPlaces: Flow<List<QuickPlace>> by lazy {
    combine(savedPlaces.places, recentDestinations.places) { saved, recents ->
      quickPlaces(
          saved,
          recents,
          appContext.getString(R.string.saved_home),
          appContext.getString(R.string.saved_work),
      )
    }
  }

  /**
   * Starts location updates if permission has been granted. The phone UI asks for permission; the
   * car can't, so an Android Auto session relies on it having been granted on the phone.
   */
  fun refreshLocationPermission() {
    val granted =
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
    navigationViewModel.setLocationPermission(granted)
  }

  val speedCameras: SpeedCameraController by lazy {
    SpeedCameraController(
        navigationState = ferrostarCore.state,
        repository = SpeedCameraRepository(overpass),
        alertEngine = CameraAlertEngine(),
        announcer = CameraWarningAnnouncer(appContext, voice),
        settings = settings,
    )
  }

  /** Fuel, chargers, food and rest areas ahead on the route. */
  val roadsideStops: RoadsideStopController by lazy {
    RoadsideStopController(
        navigationState = ferrostarCore.state,
        query = overpass::query,
        plugTypes = { settings.chargerPlugTypes.first() },
        isOnline = { network.isOnline },
    )
  }

  private fun navigationControllerConfig() =
      NavigationControllerConfig(
          WaypointAdvanceMode.WaypointWithinRange(100.0),
          stepAdvanceDistanceEntryAndExit(30u, 5u, 32u),
          stepAdvanceDistanceToEndOfStep(10u, 32u),
          RouteDeviationTracking.StaticThreshold(15U, 50.0),
          CourseFiltering.SNAP_TO_ROUTE,
      )
}
