package com.joegineered.ontheroad.nav

import android.util.Log
import com.joegineered.ontheroad.cameras.LatLng
import com.joegineered.ontheroad.cameras.MeasuredPolyline
import com.joegineered.ontheroad.search.Place
import com.joegineered.ontheroad.search.RecentDestinations
import java.util.Locale
import androidx.lifecycle.viewModelScope
import com.stadiamaps.ferrostar.core.DefaultNavigationViewModel
import com.stadiamaps.ferrostar.core.FerrostarCore
import com.stadiamaps.ferrostar.core.NavigationUiState
import com.stadiamaps.ferrostar.core.annotation.valhalla.valhallaExtendedOSRMAnnotationPublisher
import com.stadiamaps.ferrostar.core.location.NavigationLocationProvider
import com.stadiamaps.ferrostar.core.location.toUserLocation
import com.stadiamaps.ferrostar.core.NavigationState
import com.stadiamaps.ferrostar.core.isNavigating
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import uniffi.ferrostar.GeographicCoordinate
import uniffi.ferrostar.Route
import uniffi.ferrostar.TripState
import uniffi.ferrostar.TripSummary
import uniffi.ferrostar.UserLocation
import uniffi.ferrostar.Waypoint
import uniffi.ferrostar.WaypointKind

data class Destination(
    val coordinate: GeographicCoordinate,
    val label: String? = null,
    val subtitle: String? = null,
) {
  /** The destination as a place to remember; an unnamed point is titled by its coordinates. */
  fun toPlace() = Place(label ?: coordinateText(), subtitle, coordinate.lat, coordinate.lng)

  fun coordinateText() = "%.5f, %.5f".format(Locale.ROOT, coordinate.lat, coordinate.lng)

  companion object {
    fun from(place: Place) =
        Destination(GeographicCoordinate(place.lat, place.lng), place.title, place.subtitle)
  }
}

/** Route request progress, shown on the phone while no navigation session is active. */
sealed interface RoutingStatus {
  data object Idle : RoutingStatus

  data object Loading : RoutingStatus

  data class Failed(val message: String) : RoutingStatus

  /** The router found no drivable route, e.g. to a pedestrian zone or a bus-only terminal. */
  data object NoRoute : RoutingStatus

  /** No internet connection; routes are planned online. */
  data object Offline : RoutingStatus
}

/** One of the routes offered before starting: [via] names it, [trafficEvents] are on it now. */
data class RouteOption(val route: Route, val via: String?, val trafficEvents: Int) {
  val durationSeconds: Double
    get() = route.steps.sumOf { it.duration }
}

/**
 * Routes to the selected destination via its [stops], best first, for the driver to choose from.
 * With stops there is one route: the router offers alternatives only between two places.
 */
data class RoutePreview(
    val destination: Destination,
    val options: List<RouteOption>,
    val selectedIndex: Int = 0,
    val stops: List<Destination> = emptyList(),
) {
  val selected: RouteOption
    get() = options[selectedIndex]
}

/** What to tell the driver when routing failed with [e]. */
fun routingFailureStatus(e: Exception, online: Boolean): RoutingStatus {
  // Valhalla answers HTTP 400 ("NoRoute") when the destination can't be reached by car.
  val noRoute = e.message?.contains("status code 400") == true
  return when {
    noRoute -> RoutingStatus.NoRoute
    !online -> RoutingStatus.Offline
    else -> RoutingStatus.Failed(e.message ?: e.javaClass.simpleName)
  }
}

/** A trip to a new destination has started (not a reroute). */
data class TripStart(val destinationName: String, val route: List<GeographicCoordinate>)

/**
 * The single navigation session for the app. Both the phone UI and the Android Auto screens
 * observe this instance (see [com.joegineered.ontheroad.AppGraph]).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OtrNavigationViewModel(
    private val ferrostarCore: FerrostarCore,
    private val locationProvider: NavigationLocationProvider,
    private val recentDestinations: RecentDestinations,
    /** The best route followed by alternatives, for the route preview. */
    private val planRoutes: suspend (UserLocation, List<Waypoint>) -> List<Route>,
    /** How many current traffic events are on a route. */
    private val trafficEventsOnRoute: (List<GeographicCoordinate>) -> Int = { 0 },
    /** Whether the navigation voice is still speaking, so arrival doesn't cut it off. */
    private val isSpeaking: () -> Boolean = { false },
    private val isOnline: () -> Boolean = { true },
) : DefaultNavigationViewModel(ferrostarCore, valhallaExtendedOSRMAnnotationPublisher()) {

  private val hasLocationPermission = MutableStateFlow(false)

  private val _location = MutableStateFlow<UserLocation?>(null)
  val location: StateFlow<UserLocation?> = _location.asStateFlow()

  private val _selectedDestination = MutableStateFlow<Destination?>(null)
  val selectedDestination: StateFlow<Destination?> = _selectedDestination.asStateFlow()

  private val _routingStatus = MutableStateFlow<RoutingStatus>(RoutingStatus.Idle)
  val routingStatus: StateFlow<RoutingStatus> = _routingStatus.asStateFlow()

  private val _plannedStops = MutableStateFlow<List<Destination>>(emptyList())
  /** Stops on the way to the selected destination, in driving order, while planning a trip. */
  val plannedStops: StateFlow<List<Destination>> = _plannedStops.asStateFlow()

  private val _preview = MutableStateFlow<RoutePreview?>(null)
  /** Routes to the selected destination once planned; null while none are available. */
  val preview: StateFlow<RoutePreview?> = _preview.asStateFlow()
  private var previewJob: Job? = null

  private val _destinationPrompt = MutableStateFlow<DestinationRequest?>(null)
  /**
   * A destination request from another app waiting for the driver on the phone: a
   * [DestinationRequest.Search] to pick a result from, or a [DestinationRequest.Go] to choose
   * between replacing the trip and adding a stop. The car asks on its own screens instead.
   */
  val destinationPrompt: StateFlow<DestinationRequest?> = _destinationPrompt.asStateFlow()

  private val _simulated = MutableStateFlow(false)
  val simulated: StateFlow<Boolean> = _simulated.asStateFlow()

  private val _arrival = MutableStateFlow<Arrival?>(null)
  /** Set when a trip ends at its destination; cleared with [dismissArrival]. */
  val arrival: StateFlow<Arrival?> = _arrival.asStateFlow()

  private val speedEstimator = SpeedEstimator()
  private val _speed = MutableStateFlow<Double?>(null)
  /** Current speed in m/s while navigating; null when unknown or not navigating. */
  val speedMetresPerSecond: StateFlow<Double?> = _speed.asStateFlow()

  private val _tripStarts = MutableSharedFlow<TripStart>(extraBufferCapacity = 1)
  val tripStarts: SharedFlow<TripStart> = _tripStarts.asSharedFlow()

  /** The destination of the trip in progress, and its stops on the way as last routed. */
  private var activeDestination: Destination? = null
  @Volatile private var activeStops: List<Destination> = emptyList()
  private val parkedNearby = ParkedNearbyDetector()
  private var finishing = false

  /** The trip's stops not yet reached, in order. */
  val remainingStops: StateFlow<List<Destination>> =
      ferrostarCore.state
          .map { state ->
            (state.tripState as? TripState.Navigating)
                ?.let { remainingStops(activeStops, it.remainingWaypoints.size) }
                .orEmpty()
          }
          .distinctUntilChanged()
          .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

  /** Shows the user's location on the map while browsing, not only while navigating. */
  override val navigationUiState: StateFlow<NavigationUiState> =
      combine(super.navigationUiState, _location) { uiState, location ->
            if (uiState.isNavigating()) uiState else uiState.copy(location = location)
          }
          .stateIn(viewModelScope, SharingStarted.Eagerly, NavigationUiState.empty())

  init {
    viewModelScope.launch {
      hasLocationPermission
          .flatMapLatest { permitted ->
            if (permitted) locationProvider.locationUpdates(1000L).map { it.toUserLocation() }
            else emptyFlow()
          }
          .collect { location ->
            val firstFix = _location.value == null
            _location.value = location
            // A destination chosen before the first fix gets its routes now.
            val destination = _selectedDestination.value
            if (firstFix && destination != null && _routingStatus.value == RoutingStatus.Idle) {
              planPreview(destination)
            }
          }
    }
    viewModelScope.launch { ferrostarCore.state.collect(::watchForArrival) }
  }

  private fun watchForArrival(state: NavigationState) {
    val trip = state.tripState
    if (trip !is TripState.Navigating) {
      speedEstimator.reset()
      _speed.value = null
    }
    when (trip) {
      is TripState.Complete -> finishTrip(trip.summary)
      is TripState.Navigating -> {
        val location = trip.userLocation
        val speed =
            speedEstimator.update(
                LatLng(location.coordinates.lat, location.coordinates.lng),
                location.timestamp.toEpochMilli(),
                location.speed?.value,
            )
        _speed.value = speed
        val parked =
            parkedNearby.update(
                trip.progress.distanceRemaining,
                speed,
                System.currentTimeMillis(),
            )
        if (parked) finishTrip(trip.summary)
      }
      is TripState.Idle -> Unit
    }
  }

  /** Ends the session at the destination, once the arrival announcement has been spoken. */
  private fun finishTrip(summary: TripSummary) {
    if (finishing) return
    finishing = true
    val endedAt = summary.endedAt?.time ?: System.currentTimeMillis()
    _arrival.value =
        Arrival(
            destination = activeDestination,
            distanceMetres = summary.distanceTraveled,
            durationSeconds = ((endedAt - summary.startedAt.time) / 1000).coerceAtLeast(0),
        )
    viewModelScope.launch {
      // Stopping navigation clears the speech queue; let "You have arrived" finish first.
      var waited = 0L
      while (isSpeaking() && waited < MAX_ARRIVAL_SPEECH_WAIT_MS) {
        delay(250)
        waited += 250
      }
      endSession()
      finishing = false
    }
  }

  fun dismissArrival() {
    _arrival.value = null
  }

  /**
   * Adds [stop] (fuel, a charger, food) to the trip in progress, among its other stops in the
   * order they come along the route. Without a trip, simply navigates to [stop].
   */
  fun stopOnTheWay(stop: Destination) {
    val active = activeDestination
    val state = ferrostarCore.state.value
    if (active == null || !state.isNavigating()) {
      startNavigation(stop)
      return
    }
    val route = MeasuredPolyline(state.routeGeometry.map { LatLng(it.lat, it.lng) })
    startNavigation(active, insertStop(route, remainingStops.value, stop))
  }

  /** Leaves [stop] out of the trip in progress, and routes on to the next stop or destination. */
  fun skipStop(stop: Destination) {
    val active = activeDestination ?: return
    if (!navigationUiState.value.isNavigating()) return
    startNavigation(active, remainingStops.value - stop)
  }

  /**
   * Adds a stop on the way to the selected destination, last before it. With no destination yet,
   * [stop] becomes the destination.
   */
  fun addStop(stop: Destination) {
    val destination = _selectedDestination.value ?: return selectDestination(stop)
    _plannedStops.update { it + stop }
    planPreview(destination)
  }

  fun removeStop(stop: Destination) {
    val destination = _selectedDestination.value ?: return
    _plannedStops.update { it - stop }
    planPreview(destination)
  }

  fun setLocationPermission(permitted: Boolean) {
    hasLocationPermission.value = permitted
  }

  fun selectDestination(coordinate: GeographicCoordinate, label: String? = null) {
    selectDestination(Destination(coordinate, label))
  }

  /** Chooses a destination and plans routes to it, to preview before starting. */
  fun selectDestination(destination: Destination) {
    _plannedStops.value = emptyList()
    _selectedDestination.value = destination
    _arrival.value = null
    planPreview(destination)
  }

  fun clearDestination() {
    previewJob?.cancel()
    _selectedDestination.value = null
    _plannedStops.value = emptyList()
    _preview.value = null
    _routingStatus.value = RoutingStatus.Idle
  }

  fun selectRoute(index: Int) {
    _preview.update { preview ->
      preview?.copy(selectedIndex = index.coerceIn(0, preview.options.lastIndex))
    }
  }

  /** Plans the previewed routes again, e.g. after the roads to avoid changed. */
  fun refreshPreview() {
    val destination = _selectedDestination.value ?: return
    if (navigationUiState.value.isNavigating()) return
    planPreview(destination)
  }

  private fun planPreview(destination: Destination) {
    previewJob?.cancel()
    _preview.value = null
    val origin = _location.value
    if (origin == null) {
      // Planned on the first location fix instead.
      _routingStatus.value = RoutingStatus.Idle
      return
    }
    if (!isOnline()) {
      _routingStatus.value = RoutingStatus.Offline
      return
    }
    _routingStatus.value = RoutingStatus.Loading
    val stops = _plannedStops.value
    previewJob =
        viewModelScope.launch(Dispatchers.IO) {
          try {
            val routes = planRoutes(origin, (stops + destination).map { it.toWaypoint() })
            if (routes.isEmpty()) error("no route returned")
            val via = distinctiveRoads(routes.map { route -> route.steps.map { it.roadName to it.distance } })
            val options =
                routes.mapIndexed { i, route -> RouteOption(route, via[i], trafficEventsOnRoute(route.geometry)) }
            ensureActive()
            if (_selectedDestination.value != destination || _plannedStops.value != stops) return@launch
            _preview.value = RoutePreview(destination, options, stops = stops)
            _routingStatus.value = RoutingStatus.Idle
          } catch (e: CancellationException) {
            throw e
          } catch (e: Exception) {
            Log.e(TAG, "Route preview failed", e)
            _routingStatus.value = routingFailure(e)
          }
        }
  }

  /**
   * Carries out [action] for [destination] (see [destinationOutcome]). Returns false, doing
   * nothing, when the driver must first choose between replacing the trip and adding a stop.
   */
  fun goTo(destination: Destination, action: TripAction): Boolean {
    val outcome =
        destinationOutcome(
            action = action,
            navigating = navigationUiState.value.isNavigating(),
            previewing = _selectedDestination.value != null,
            hasLocation = _location.value != null,
        )
    when (outcome) {
      DestinationOutcome.START -> startNavigation(destination)
      DestinationOutcome.PREVIEW -> selectDestination(destination)
      DestinationOutcome.ADD_TO_PREVIEW -> addStop(destination)
      DestinationOutcome.ADD_TO_TRIP -> stopOnTheWay(destination)
      DestinationOutcome.ASK -> return false
    }
    return true
  }

  /** Handles [request] on the phone, asking the driver through [destinationPrompt] if needed. */
  fun handleDestinationRequest(request: DestinationRequest) {
    _destinationPrompt.value =
        when (request) {
          is DestinationRequest.Go -> request.takeUnless { goTo(it.destination, it.action) }
          is DestinationRequest.Search -> request
        }
  }

  fun dismissDestinationPrompt() {
    _destinationPrompt.value = null
  }

  fun setSimulated(simulated: Boolean) {
    _simulated.value = simulated
    if (!simulated) locationProvider.disableSimulation()
  }

  /** Starts the previewed route chosen, or plans one if there is no preview (e.g. it failed). */
  fun startSelectedDestination() {
    val destination = _selectedDestination.value ?: return
    val stops = _plannedStops.value
    val preview = _preview.value?.takeIf { it.destination == destination && it.stops == stops }
    if (preview == null) {
      startNavigation(destination, stops)
      return
    }
    _routingStatus.value = RoutingStatus.Loading
    viewModelScope.launch(Dispatchers.IO) {
      try {
        beginTrip(destination, preview.selected.route, stops)
      } catch (e: Exception) {
        Log.e(TAG, "Could not start navigation", e)
        _routingStatus.value = RoutingStatus.Failed(e.message ?: e.javaClass.simpleName)
      }
    }
  }

  /** Navigates to [destination] via [stops]; with the active destination, this is a reroute. */
  fun startNavigation(destination: Destination, stops: List<Destination> = emptyList()) {
    val origin = _location.value
    if (origin == null) {
      _routingStatus.value = RoutingStatus.Failed("no location fix")
      return
    }
    _routingStatus.value = RoutingStatus.Loading

    viewModelScope.launch(Dispatchers.IO) {
      try {
        val routes = ferrostarCore.getRoutes(origin, (stops + destination).map { it.toWaypoint() })
        val route = routes.firstOrNull() ?: error("no route returned")
        beginTrip(destination, route, stops)
      } catch (e: Exception) {
        Log.e(TAG, "Routing failed", e)
        _routingStatus.value = routingFailure(e)
      }
    }
  }

  /** Starts navigating [route], or switches the active session to it (a reroute). */
  private suspend fun beginTrip(destination: Destination, route: Route, stops: List<Destination>) {
    if (_simulated.value) locationProvider.enableSimulationOn(route)

    val newTrip = destination != activeDestination
    setDestination(destination.label)
    activeDestination = destination
    activeStops = stops
    parkedNearby.reset()
    _arrival.value = null
    if (navigationUiState.value.isNavigating()) {
      ferrostarCore.replaceRoute(route)
    } else {
      ferrostarCore.startNavigation(route)
    }
    previewJob?.cancel()
    _selectedDestination.value = null
    _plannedStops.value = emptyList()
    _preview.value = null
    _routingStatus.value = RoutingStatus.Idle
    val place = destination.toPlace()
    if (newTrip) _tripStarts.tryEmit(TripStart(place.title, route.geometry))
    // Remembering the destination (not the stops, such as fuel on the way) is best-effort; it must
    // not fail an active session.
    runCatching { recentDestinations.add(place) }
        .onFailure { Log.w(TAG, "Could not save recent destination", it) }
  }

  private fun routingFailure(e: Exception): RoutingStatus = routingFailureStatus(e, isOnline())

  /**
   * Plans a new route to the current destination via the stops still ahead, e.g. around a newly
   * reported closure.
   */
  fun rerouteToActiveDestination() {
    val destination = activeDestination ?: return
    if (!navigationUiState.value.isNavigating()) return
    startNavigation(destination, remainingStops.value)
  }

  override fun stopNavigation() {
    endSession()
  }

  private fun endSession() {
    locationProvider.disableSimulation()
    ferrostarCore.stopNavigation()
    setDestination(null)
    activeDestination = null
    activeStops = emptyList()
    parkedNearby.reset()
  }

  private fun Destination.toWaypoint() = Waypoint(coordinate = coordinate, kind = WaypointKind.BREAK)

  private companion object {
    const val TAG = "OtrNavigationViewModel"
    const val MAX_ARRIVAL_SPEECH_WAIT_MS = 8_000L
  }
}
