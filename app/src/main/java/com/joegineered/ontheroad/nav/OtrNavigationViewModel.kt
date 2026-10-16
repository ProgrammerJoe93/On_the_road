package com.joegineered.ontheroad.nav

import android.util.Log
import com.joegineered.ontheroad.cameras.LatLng
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import uniffi.ferrostar.GeographicCoordinate
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
  fun toPlace(fallbackTitle: String) =
      Place(label ?: fallbackTitle, subtitle, coordinate.lat, coordinate.lng)

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
}

/**
 * The single navigation session for the app. Both the phone UI and the Android Auto screens
 * observe this instance (see [com.joegineered.ontheroad.AppGraph]).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OtrNavigationViewModel(
    private val ferrostarCore: FerrostarCore,
    private val locationProvider: NavigationLocationProvider,
    private val recentDestinations: RecentDestinations,
    /** Whether the navigation voice is still speaking, so arrival doesn't cut it off. */
    private val isSpeaking: () -> Boolean = { false },
) : DefaultNavigationViewModel(ferrostarCore, valhallaExtendedOSRMAnnotationPublisher()) {

  private val hasLocationPermission = MutableStateFlow(false)

  private val _location = MutableStateFlow<UserLocation?>(null)
  val location: StateFlow<UserLocation?> = _location.asStateFlow()

  private val _selectedDestination = MutableStateFlow<Destination?>(null)
  val selectedDestination: StateFlow<Destination?> = _selectedDestination.asStateFlow()

  private val _routingStatus = MutableStateFlow<RoutingStatus>(RoutingStatus.Idle)
  val routingStatus: StateFlow<RoutingStatus> = _routingStatus.asStateFlow()

  private val _simulated = MutableStateFlow(false)
  val simulated: StateFlow<Boolean> = _simulated.asStateFlow()

  private val _arrival = MutableStateFlow<Arrival?>(null)
  /** Set when a trip ends at its destination; cleared with [dismissArrival]. */
  val arrival: StateFlow<Arrival?> = _arrival.asStateFlow()

  private val speedEstimator = SpeedEstimator()
  private val _speed = MutableStateFlow<Double?>(null)
  /** Current speed in m/s while navigating; null when unknown or not navigating. */
  val speedMetresPerSecond: StateFlow<Double?> = _speed.asStateFlow()

  /** The destination of the trip in progress. */
  private var activeDestination: Destination? = null
  private val parkedNearby = ParkedNearbyDetector()
  private var finishing = false

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
          .collect { _location.value = it }
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

  fun setLocationPermission(permitted: Boolean) {
    hasLocationPermission.value = permitted
  }

  fun selectDestination(coordinate: GeographicCoordinate, label: String? = null) {
    selectDestination(Destination(coordinate, label))
  }

  fun selectDestination(destination: Destination) {
    _selectedDestination.value = destination
    _routingStatus.value = RoutingStatus.Idle
    _arrival.value = null
  }

  fun clearDestination() {
    _selectedDestination.value = null
    _routingStatus.value = RoutingStatus.Idle
  }

  fun setSimulated(simulated: Boolean) {
    _simulated.value = simulated
    if (!simulated) locationProvider.disableSimulation()
  }

  fun startSelectedDestination() {
    val destination = _selectedDestination.value ?: return
    startNavigation(destination)
  }

  fun startNavigation(destination: Destination) {
    val origin = _location.value
    if (origin == null) {
      _routingStatus.value = RoutingStatus.Failed("no location fix")
      return
    }
    _routingStatus.value = RoutingStatus.Loading

    viewModelScope.launch(Dispatchers.IO) {
      try {
        val routes =
            ferrostarCore.getRoutes(
                origin,
                listOf(Waypoint(coordinate = destination.coordinate, kind = WaypointKind.BREAK)),
            )
        val route = routes.firstOrNull() ?: error("no route returned")

        if (_simulated.value) locationProvider.enableSimulationOn(route)

        setDestination(destination.label)
        activeDestination = destination
        parkedNearby.reset()
        _arrival.value = null
        if (navigationUiState.value.isNavigating()) {
          ferrostarCore.replaceRoute(route)
        } else {
          ferrostarCore.startNavigation(route)
        }
        _selectedDestination.value = null
        _routingStatus.value = RoutingStatus.Idle
        // Remembering the destination is best-effort; it must not fail an active session.
        runCatching {
              recentDestinations.add(destination.toPlace(destination.coordinateText()))
            }
            .onFailure { Log.w(TAG, "Could not save recent destination", it) }
      } catch (e: Exception) {
        Log.e(TAG, "Routing failed", e)
        // Valhalla answers HTTP 400 ("NoRoute") when the destination can't be reached by car.
        val noRoute = e.message?.contains("status code 400") == true
        _routingStatus.value =
            if (noRoute) RoutingStatus.NoRoute
            else RoutingStatus.Failed(e.message ?: e.javaClass.simpleName)
      }
    }
  }

  override fun stopNavigation() {
    endSession()
  }

  private fun endSession() {
    locationProvider.disableSimulation()
    ferrostarCore.stopNavigation()
    setDestination(null)
    activeDestination = null
    parkedNearby.reset()
  }

  private fun Destination.coordinateText() =
      "%.5f, %.5f".format(Locale.ROOT, coordinate.lat, coordinate.lng)

  private companion object {
    const val TAG = "OtrNavigationViewModel"
    const val MAX_ARRIVAL_SPEECH_WAIT_MS = 8_000L
  }
}
