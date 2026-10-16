package com.joegineered.ontheroad.nav

import android.util.Log
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
import kotlinx.coroutines.Dispatchers
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
        _routingStatus.value = RoutingStatus.Failed(e.message ?: e.javaClass.simpleName)
      }
    }
  }

  override fun stopNavigation() {
    locationProvider.disableSimulation()
    ferrostarCore.stopNavigation()
    setDestination(null)
  }

  private fun Destination.coordinateText() =
      "%.5f, %.5f".format(Locale.ROOT, coordinate.lat, coordinate.lng)

  private companion object {
    const val TAG = "OtrNavigationViewModel"
  }
}
