package com.joegineered.ontheroad

import android.content.Context
import android.speech.tts.TextToSpeech
import android.util.Log
import com.joegineered.ontheroad.cameras.CameraAlertEngine
import com.joegineered.ontheroad.cameras.CameraWarningAnnouncer
import com.joegineered.ontheroad.cameras.OverpassClient
import com.joegineered.ontheroad.cameras.SpeedCameraController
import com.joegineered.ontheroad.cameras.SpeedCameraRepository
import com.joegineered.ontheroad.config.AppConfig
import com.joegineered.ontheroad.nav.OtrNavigationViewModel
import com.joegineered.ontheroad.settings.SettingsRepository
import com.stadiamaps.ferrostar.composeui.notification.DefaultForegroundNotificationBuilder
import com.stadiamaps.ferrostar.core.AndroidTtsObserver
import com.stadiamaps.ferrostar.core.AndroidTtsStatusListener
import com.stadiamaps.ferrostar.core.FerrostarCore
import com.stadiamaps.ferrostar.core.http.OkHttpClientProvider.Companion.toOkHttpClientProvider
import com.stadiamaps.ferrostar.core.location.NavigationLocationProvider
import com.stadiamaps.ferrostar.core.location.SimulatedLocationProvider
import com.stadiamaps.ferrostar.core.service.FerrostarForegroundServiceManager
import com.stadiamaps.ferrostar.core.withJsonOptions
import com.stadiamaps.ferrostar.googleplayservices.FusedNavigationLocationProvider
import java.time.Duration
import java.util.Locale
import okhttp3.OkHttpClient
import uniffi.ferrostar.CourseFiltering
import uniffi.ferrostar.NavigationControllerConfig
import uniffi.ferrostar.RouteDeviationTracking
import uniffi.ferrostar.WaypointAdvanceMode
import uniffi.ferrostar.WellKnownRouteProvider
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
    ttsObserver.statusObserver = ttsStatusListener
    ttsObserver.start()
  }

  val httpClient: OkHttpClient by lazy {
    OkHttpClient.Builder().callTimeout(Duration.ofSeconds(20)).build()
  }

  /** Overpass queries can take tens of seconds on the public server. */
  private val overpassHttpClient: OkHttpClient by lazy {
    httpClient.newBuilder().callTimeout(Duration.ofSeconds(90)).readTimeout(Duration.ofSeconds(90)).build()
  }

  val locationProvider: NavigationLocationProvider by lazy {
    NavigationLocationProvider(
        liveProviding = FusedNavigationLocationProvider(appContext),
        simulatedProvider = SimulatedLocationProvider(warpFactor = 2u),
    )
  }

  val ttsObserver: AndroidTtsObserver by lazy { AndroidTtsObserver(appContext) }

  private val ttsStatusListener =
      object : AndroidTtsStatusListener {
        override fun onTtsInitialized(tts: TextToSpeech?, status: Int) {
          if (tts == null) {
            Log.e(TAG, "TTS setup failed: $status")
            return
          }
          tts.language = Locale.forLanguageTag(AppConfig.instructionLanguage())
        }

        override fun onTtsSpeakError(utteranceId: String, status: Int) {
          Log.e(TAG, "TTS failed for utterance $utteranceId: $status")
        }

        override fun onTtsShutdownAndRelease() {
          Log.i(TAG, "TTS released")
        }
      }

  val ferrostarCore: FerrostarCore by lazy {
    val options =
        mapOf(
            "language" to AppConfig.instructionLanguage(),
            "units" to if (AppConfig.usesMetricUnits) "kilometers" else "miles",
        )
    val routeProvider =
        WellKnownRouteProvider.Valhalla(AppConfig.valhallaUrl, AppConfig.VALHALLA_COSTING)
            .withJsonOptions(options)

    FerrostarCore(
            routeProvider,
            httpClient = httpClient.toOkHttpClientProvider(),
            locationProvider = locationProvider,
            foregroundServiceManager =
                FerrostarForegroundServiceManager(
                    appContext,
                    DefaultForegroundNotificationBuilder(appContext),
                ),
            navigationControllerConfig = navigationControllerConfig(),
        )
        .also {
          // Must be set before the view model is created: it captures the mute state flow.
          it.spokenInstructionObserver = ttsObserver
        }
  }

  val navigationViewModel: OtrNavigationViewModel by lazy {
    OtrNavigationViewModel(ferrostarCore, locationProvider)
  }

  val settings: SettingsRepository by lazy { SettingsRepository(appContext) }

  val speedCameras: SpeedCameraController by lazy {
    SpeedCameraController(
        navigationState = ferrostarCore.state,
        repository = SpeedCameraRepository(OverpassClient(overpassHttpClient), appContext.cacheDir),
        alertEngine = CameraAlertEngine(),
        announcer = CameraWarningAnnouncer(appContext, ttsObserver),
        settings = settings,
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
