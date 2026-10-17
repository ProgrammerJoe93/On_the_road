package com.joegineered.ontheroad.traffic

import android.util.Log
import com.joegineered.ontheroad.cameras.LatLng
import com.joegineered.ontheroad.cameras.MeasuredPolyline
import com.stadiamaps.ferrostar.core.NavigationState
import com.stadiamaps.ferrostar.core.isNavigating
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import uniffi.ferrostar.TripState

/**
 * Variable signs on the route being driven (Finland, from Digitraffic): variable speed limits,
 * which replace the mapped limit on screen, warning signs and message panels. Signs are only
 * fetched while navigating with live traffic on, as they change within minutes.
 */
class VariableSignController(
    private val client: DigitrafficClient,
    private val liveTrafficEnabled: Flow<Boolean>,
    private val navigationState: StateFlow<NavigationState>,
    /** The mapped speed limit at the current position, in km/h. */
    private val mappedSpeedLimitKmh: () -> Int?,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
  private val _state = MutableStateFlow(VariableSignState())
  val state: StateFlow<VariableSignState> = _state.asStateFlow()

  private val signs = MutableStateFlow<List<VariableSign>>(emptyList())
  private val tracker = VariableSignTracker()
  private var routeLength: Double? = null
  private var started = false

  @Synchronized
  fun start() {
    if (started) return
    started = true
    scope.launch { refreshWhileNavigating() }
    scope.launch { matchSignsToRoute() }
    scope.launch { trackProgress() }
  }

  private suspend fun refreshWhileNavigating() {
    val navigating = navigationState.map { it.isNavigating() }.distinctUntilChanged()
    combine(liveTrafficEnabled, navigating) { enabled, nav -> enabled && nav }
        .distinctUntilChanged()
        .collectLatest { active ->
          if (!active) {
            signs.value = emptyList()
            return@collectLatest
          }
          while (true) {
            try {
              signs.value = DigitrafficParser.parseVariableSigns(client.variableSigns())
            } catch (e: CancellationException) {
              throw e
            } catch (e: Exception) {
              Log.w(TAG, "Variable sign refresh failed", e)
            }
            delay(REFRESH_INTERVAL)
          }
        }
  }

  private suspend fun matchSignsToRoute() {
    val routes = navigationState.map { if (it.isNavigating()) it.routeGeometry else emptyList() }.distinctUntilChanged()
    combine(routes, signs) { route, signs -> route to signs }
        .collectLatest { (geometry, signs) ->
          if (geometry.size < 2) {
            routeLength = null
            synchronized(tracker) { if (!navigationState.value.isNavigating()) tracker.reset() else tracker.setSigns(emptyList()) }
            _state.value = VariableSignState()
            return@collectLatest
          }
          val route = geometry.map { LatLng(it.lat, it.lng) }
          val matched = matchVariableSigns(route, signs)
          routeLength = MeasuredPolyline(route).length
          synchronized(tracker) { tracker.setSigns(matched) }
        }
  }

  private suspend fun trackProgress() {
    navigationState.collect {
      val along = currentAlong()
      _state.value =
          if (along == null) VariableSignState()
          else synchronized(tracker) { tracker.update(along, mappedSpeedLimitKmh()) }
    }
  }

  private fun currentAlong(): Double? {
    val trip = navigationState.value.tripState as? TripState.Navigating ?: return null
    val length = routeLength ?: return null
    return length - trip.progress.distanceRemaining
  }

  private companion object {
    const val TAG = "VariableSigns"
    /** Variable limits follow traffic and weather, and can change within minutes. */
    val REFRESH_INTERVAL = 2.minutes
  }
}
