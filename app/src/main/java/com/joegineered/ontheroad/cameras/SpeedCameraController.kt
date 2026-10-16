package com.joegineered.ontheroad.cameras

import android.util.Log
import com.joegineered.ontheroad.settings.SettingsRepository
import com.stadiamaps.ferrostar.core.NavigationState
import com.stadiamaps.ferrostar.core.isNavigating
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uniffi.ferrostar.GeographicCoordinate
import uniffi.ferrostar.TripState

/**
 * Connects speed camera warnings to the navigation session: fetches cameras for each new route,
 * tracks progress along it, and publishes warnings for the phone and car UIs.
 */
class SpeedCameraController(
    private val navigationState: StateFlow<NavigationState>,
    private val repository: SpeedCameraRepository,
    private val alertEngine: CameraAlertEngine,
    private val announcer: CameraWarningAnnouncer,
    private val settings: SettingsRepository,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
  private val _cameras = MutableStateFlow<List<MatchedCamera>>(emptyList())
  /** Cameras on the current route, for drawing on the map. */
  val cameras: StateFlow<List<MatchedCamera>> = _cameras.asStateFlow()

  private val _activeWarning = MutableStateFlow<CameraWarning?>(null)
  /** The camera currently being approached, for the on-screen warning. */
  val activeWarning: StateFlow<CameraWarning?> = _activeWarning.asStateFlow()

  private val _announcements = MutableSharedFlow<CameraWarning>(extraBufferCapacity = 4)
  /** Emits once per camera when it is first announced (used for the Android Auto alert). */
  val announcements: SharedFlow<CameraWarning> = _announcements.asSharedFlow()

  @Volatile private var routeLength: Double? = null
  private var started = false

  @Synchronized
  fun start() {
    if (started) return
    started = true
    scope.launch { fetchCamerasForEachRoute() }
    scope.launch { trackProgress() }
  }

  @OptIn(ExperimentalCoroutinesApi::class)
  private suspend fun fetchCamerasForEachRoute() {
    combine(settings.cameraWarningsEnabled, navigationState) { enabled, state ->
          if (enabled && state.isNavigating()) state.routeGeometry else emptyList()
        }
        .distinctUntilChanged()
        .collectLatest { geometry ->
          if (geometry.size < 2) {
            routeLength = null
            _cameras.value = emptyList()
            _activeWarning.value = null
            synchronized(alertEngine) {
              alertEngine.setCameras(emptyList())
              if (!navigationState.value.isNavigating()) alertEngine.reset()
            }
            return@collectLatest
          }

          val route = geometry.map { it.toLatLng() }
          routeLength = MeasuredPolyline(route).length
          val maneuvers = maneuverPoints(navigationState.value.tripState)

          val matched =
              try {
                withContext(Dispatchers.IO) {
                  repository.camerasForRoute(route, maneuvers, onPreliminary = ::publish)
                }
              } catch (e: CancellationException) {
                // A newer route replaced this one; let its fetch publish instead.
                throw e
              } catch (e: Exception) {
                Log.w(TAG, "Could not load speed cameras", e)
                emptyList()
              }
          Log.i(TAG, "${matched.size} speed cameras on route")
          matched.forEach {
            Log.d(
                TAG,
                "  camera ${it.camera.id} at ${it.camera.position.lat},${it.camera.position.lng}, " +
                    "${it.alongRouteMetres.toInt()} m along route",
            )
          }
          publish(matched)
        }
  }

  private fun publish(cameras: List<MatchedCamera>) {
    synchronized(alertEngine) { alertEngine.setCameras(cameras) }
    _cameras.value = cameras
  }

  private suspend fun trackProgress() {
    navigationState.collect { state ->
      val trip = state.tripState as? TripState.Navigating
      val length = routeLength
      if (trip == null || length == null) {
        _activeWarning.value = null
        return@collect
      }

      val along = length - trip.progress.distanceRemaining
      val update =
          synchronized(alertEngine) { alertEngine.update(along, trip.userLocation.speed?.value) }
      _activeWarning.value = update.active
      update.announce?.let { warning ->
        announcer.announce(warning)
        _announcements.tryEmit(warning)
      }
    }
  }

  private fun maneuverPoints(tripState: TripState): List<LatLng> =
      (tripState as? TripState.Navigating)
          ?.remainingSteps
          ?.mapNotNull { it.geometry.lastOrNull()?.toLatLng() }
          .orEmpty()

  private fun GeographicCoordinate.toLatLng() = LatLng(lat, lng)

  private companion object {
    const val TAG = "SpeedCameraController"
  }
}
