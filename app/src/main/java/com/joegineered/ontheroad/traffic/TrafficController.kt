package com.joegineered.ontheroad.traffic

import android.util.Log
import com.joegineered.ontheroad.cameras.LatLng
import com.joegineered.ontheroad.cameras.MeasuredPolyline
import com.joegineered.ontheroad.cameras.PolylineIndex
import com.joegineered.ontheroad.settings.SettingsRepository
import com.stadiamaps.ferrostar.core.NavigationState
import com.stadiamaps.ferrostar.core.isNavigating
import java.time.Instant
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import uniffi.ferrostar.TripState

/**
 * Live traffic for Finland from Digitraffic: incidents, closures and roadworks for the map, slow
 * traffic at measuring stations, warnings for events on the route, and closures for routing to
 * avoid. Refreshes every few minutes while enabled.
 */
class TrafficController(
    private val client: DigitrafficClient,
    private val settings: SettingsRepository,
    private val navigationState: StateFlow<NavigationState>,
    private val announce: (TrafficWarning) -> Unit,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
  private val _events = MutableStateFlow<List<TrafficEvent>>(emptyList())
  /** Active incidents, closures and roadworks. */
  val events: StateFlow<List<TrafficEvent>> = _events.asStateFlow()

  private val _slowTraffic = MutableStateFlow<List<SlowTraffic>>(emptyList())
  val slowTraffic: StateFlow<List<SlowTraffic>> = _slowTraffic.asStateFlow()

  private val _activeWarning = MutableStateFlow<TrafficWarning?>(null)
  /** The event on the route being approached, for the on-screen warning. */
  val activeWarning: StateFlow<TrafficWarning?> = _activeWarning.asStateFlow()

  private val _currentRoadworks = MutableStateFlow<TrafficEvent?>(null)
  /** The roadwork stretch the driver is in, for the roadworks sign next to the speed limit. */
  val currentRoadworks: StateFlow<TrafficEvent?> = _currentRoadworks.asStateFlow()

  private val _announcements = MutableSharedFlow<TrafficWarning>(extraBufferCapacity = 4)
  val announcements: SharedFlow<TrafficWarning> = _announcements.asSharedFlow()

  private val _closureOnRoute = MutableSharedFlow<TrafficEvent>(extraBufferCapacity = 1)
  /** A closure found on the route being driven, after the route was planned; reroute around it. */
  val closureOnRoute: SharedFlow<TrafficEvent> = _closureOnRoute.asSharedFlow()

  private val _selected = MutableStateFlow<Any?>(null)
  /** The event or slow-traffic station the user tapped. */
  val selected: StateFlow<Any?> = _selected.asStateFlow()

  private val engine = TrafficAlertEngine()
  private var routeLength: Double? = null
  private var stations: Map<Int, LatLng> = emptyMap()
  private var stationsLoadedAt = Instant.EPOCH
  private val reroutedFor = mutableSetOf<String>()
  private var started = false

  @Synchronized
  fun start() {
    if (started) return
    started = true
    scope.launch { refreshWhileEnabled() }
    scope.launch { matchEventsToRoute() }
    scope.launch { trackProgress() }
  }

  /** Active closures, for the route provider to avoid. Empty when live traffic is off. */
  fun closures(): List<TrafficEvent> = _events.value.filter { it.isClosure }

  /** Current events along [route], to compare routes before starting one. */
  fun eventsOnRoute(route: List<LatLng>): List<TrafficEvent> {
    val events = _events.value
    if (events.isEmpty() || route.size < 2) return emptyList()
    // Indexed rather than projected point by point, which is slow for long routes.
    val line = PolylineIndex(route)
    return events.filter { event -> event.lines.flatten().any { line.isNear(it, ON_ROUTE_METRES) } }
  }

  fun select(item: Any?) {
    _selected.value = item
  }

  fun selectById(id: String) {
    _selected.value =
        _events.value.firstOrNull { it.id == id }
            ?: _slowTraffic.value.firstOrNull { "tms-${it.stationId}" == id }
  }

  private suspend fun refreshWhileEnabled() {
    settings.liveTrafficEnabled.collectLatest { enabled ->
      if (!enabled) {
        _events.value = emptyList()
        _slowTraffic.value = emptyList()
        return@collectLatest
      }
      while (true) {
        try {
          refresh()
        } catch (e: CancellationException) {
          throw e
        } catch (e: Exception) {
          Log.w(TAG, "Traffic refresh failed", e)
        }
        delay(REFRESH_INTERVAL)
      }
    }
  }

  private suspend fun refresh() = coroutineScope {
    val now = Instant.now()
    val announcements = async { DigitrafficParser.parseMessages(client.trafficAnnouncements(), now) }
    val roadworks = async { DigitrafficParser.parseMessages(client.roadworks(), now) }
    if (stations.isEmpty() || now.isAfter(stationsLoadedAt.plusSeconds(STATIONS_TTL.inWholeSeconds))) {
      stations = DigitrafficParser.parseStations(client.stations())
      stationsLoadedAt = now
    }
    val slow = async { DigitrafficParser.parseSlowTraffic(client.stationData(), stations, SLOW_PERCENT) }
    _events.value = announcements.await() + roadworks.await()
    _slowTraffic.value = slow.await()
    Log.i(TAG, "${_events.value.size} traffic events, ${_slowTraffic.value.size} slow stations")
  }

  @OptIn(ExperimentalCoroutinesApi::class)
  private suspend fun matchEventsToRoute() {
    val routes = navigationState.map { if (it.isNavigating()) it.routeGeometry else emptyList() }.distinctUntilChanged()
    combine(routes, _events) { route, events -> route to events }
        .collectLatest { (geometry, events) ->
          if (geometry.size < 2) {
            routeLength = null
            _activeWarning.value = null
            synchronized(engine) { if (!navigationState.value.isNavigating()) engine.reset() else engine.setEvents(emptyList()) }
            reroutedFor.clear()
            return@collectLatest
          }
          val route = geometry.map { LatLng(it.lat, it.lng) }
          routeLength = MeasuredPolyline(route).length
          val matched = synchronized(engine) { engine.match(route, events).also { engine.setEvents(it) } }

          // A closure ahead that the route already runs into: ask for a new route, once per closure.
          val along = currentAlong() ?: 0.0
          matched
              .firstOrNull { it.event.isClosure && it.alongRouteMetres > along + MIN_REROUTE_DISTANCE && it.event.id !in reroutedFor }
              ?.let {
                reroutedFor += it.event.id
                _closureOnRoute.tryEmit(it.event)
              }
        }
  }

  private suspend fun trackProgress() {
    navigationState.collect {
      val along = currentAlong()
      if (along == null) {
        _activeWarning.value = null
        _currentRoadworks.value = null
        return@collect
      }
      val update = synchronized(engine) { engine.update(along) }
      _activeWarning.value = update.active
      _currentRoadworks.value = synchronized(engine) { engine.roadworksAt(along) }
      update.announce?.let { warning ->
        announce(warning)
        _announcements.tryEmit(warning)
      }
    }
  }

  private fun currentAlong(): Double? {
    val trip = navigationState.value.tripState as? TripState.Navigating ?: return null
    val length = routeLength ?: return null
    return length - trip.progress.distanceRemaining
  }

  private companion object {
    const val TAG = "TrafficController"
    val REFRESH_INTERVAL = 5.minutes
    val STATIONS_TTL = 24.hours
    /** Below this share of free-flow speed, traffic counts as slow (Fintraffic's "slow" class). */
    const val SLOW_PERCENT = 75.0
    const val MIN_REROUTE_DISTANCE = 500.0
    /** As in [TrafficAlertEngine]: an event this close to the route is on it. */
    const val ON_ROUTE_METRES = 30.0
  }
}
