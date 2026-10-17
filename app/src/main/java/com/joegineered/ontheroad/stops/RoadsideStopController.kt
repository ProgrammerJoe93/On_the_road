package com.joegineered.ontheroad.stops

import android.util.Log
import com.joegineered.ontheroad.cameras.LatLng
import com.joegineered.ontheroad.cameras.MeasuredPolyline
import com.joegineered.ontheroad.cameras.chunkedOverlapping
import com.joegineered.ontheroad.cameras.distanceMetres
import com.joegineered.ontheroad.cameras.simplify
import com.joegineered.ontheroad.chargers.SocketType
import com.stadiamaps.ferrostar.core.NavigationState
import com.stadiamaps.ferrostar.core.isNavigating
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import uniffi.ferrostar.TripState

/** A search for places to stop at along the route being driven. */
sealed interface StopSearch {
  data object Idle : StopSearch

  data class Loading(val category: StopCategory) : StopSearch

  data class Results(val category: StopCategory, val stops: List<StopAhead>) : StopSearch

  /** The search failed; [offline] when there was no connection and nothing cached. */
  data class Failed(val category: StopCategory, val offline: Boolean) : StopSearch
}

/**
 * Finds fuel, chargers, food and rest areas ahead on the route, from OpenStreetMap via the shared
 * Overpass cache. Shared by the phone and car. Results follow the car: distances update as it
 * drives, the search repeats after a reroute, and it clears when the trip ends.
 */
class RoadsideStopController(
    private val navigationState: StateFlow<NavigationState>,
    private val query: suspend (String) -> String,
    private val plugTypes: suspend () -> Set<SocketType>,
    private val isOnline: () -> Boolean,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
  private val _state = MutableStateFlow<StopSearch>(StopSearch.Idle)
  val state: StateFlow<StopSearch> = _state.asStateFlow()

  /** The route as searched: simplified, so ranking stays quick on long routes. */
  private class SearchedRoute(val simplified: MeasuredPolyline, val fullLength: Double)

  @Volatile private var route: SearchedRoute? = null
  @Volatile private var found: List<RoadsideStop> = emptyList()
  private var job: Job? = null
  @Volatile private var lastRankedAt = Double.NEGATIVE_INFINITY
  /** Where the searched route ends, to tell a reroute from a new trip. */
  @Volatile private var searchedEnd: LatLng? = null
  private var started = false

  @Synchronized
  fun start() {
    if (started) return
    started = true
    scope.launch {
      navigationState
          .map { if (it.isNavigating()) it.routeGeometry else emptyList() }
          .distinctUntilChanged()
          .collect { geometry ->
            val end = geometry.lastOrNull()?.let { LatLng(it.lat, it.lng) }
            val sameDestination = end != null && searchedEnd?.let { distanceMetres(it, end) < 100.0 } == true
            val category = currentCategory()
            // A reroute: search the new route for the same kind of place. A new trip (such as
            // driving to a stop just found) starts without a search.
            if (category != null && sameDestination) search(category) else clear()
          }
    }
    scope.launch { navigationState.collect { rerankAsDriving() } }
  }

  fun search(category: StopCategory) {
    job?.cancel()
    val state = navigationState.value
    val geometry = if (state.isNavigating()) state.routeGeometry else emptyList()
    if (geometry.size < 2) {
      _state.value = StopSearch.Idle
      return
    }
    _state.value = StopSearch.Loading(category)
    searchedEnd = geometry.last().let { LatLng(it.lat, it.lng) }
    job =
        scope.launch {
          try {
            val points = geometry.map { LatLng(it.lat, it.lng) }
            // The query radius (300 m or more) comfortably covers the simplification error.
            val simplified = simplify(points, toleranceMetres = 50.0)
            val searched = SearchedRoute(MeasuredPolyline(simplified), MeasuredPolyline(points).length)
            val along = alongOn(searched) ?: 0.0
            val chunks = simplified.chunkedOverlapping(MAX_POINTS_PER_QUERY)
            val wanted =
                RoadsideStops.chunksBetween(
                    chunks,
                    chunks.map { MeasuredPolyline(it).length },
                    fromMetres = along,
                    toMetres = along + SEARCH_AHEAD_METRES,
                )
            val results = wanted.map { chunk -> runCatching { query(RoadsideStops.alongQuery(category, chunk)) } }
            results.forEach { result ->
              result.exceptionOrNull()?.let { if (it is CancellationException) throw it }
            }
            val bodies = results.mapNotNull { it.getOrNull() }
            if (bodies.isEmpty()) {
              Log.w(TAG, "Stop search failed", results.firstOrNull()?.exceptionOrNull())
              _state.value = StopSearch.Failed(category, offline = !isOnline())
              return@launch
            }
            val plugs = plugTypes()
            found =
                bodies
                    .flatMap { RoadsideStops.parse(it, category) }
                    .distinctBy { it.id }
                    .filter { it.charger?.matches(plugs) ?: true }
            route = searched
            lastRankedAt = along
            _state.value = StopSearch.Results(category, RoadsideStops.ahead(searched.simplified, along, found))
            Log.i(TAG, "${found.size} ${category.name.lowercase()} stops along the route")
          } catch (e: CancellationException) {
            throw e
          } catch (e: Exception) {
            Log.w(TAG, "Stop search failed", e)
            _state.value = StopSearch.Failed(category, offline = !isOnline())
          }
        }
  }

  fun clear() {
    job?.cancel()
    route = null
    searchedEnd = null
    found = emptyList()
    _state.value = StopSearch.Idle
  }

  private fun currentCategory(): StopCategory? =
      when (val state = _state.value) {
        StopSearch.Idle -> null
        is StopSearch.Loading -> state.category
        is StopSearch.Results -> state.category
        is StopSearch.Failed -> state.category
      }

  /** Updates distances (and drops passed stops) every few hundred metres driven. */
  private fun rerankAsDriving() {
    val results = _state.value as? StopSearch.Results ?: return
    val searched = route ?: return
    val along = alongOn(searched) ?: return
    if (along - lastRankedAt < RERANK_EVERY_METRES) return
    lastRankedAt = along
    _state.value = results.copy(stops = RoadsideStops.ahead(searched.simplified, along, found))
  }

  /**
   * How far along the simplified route the car is, from Ferrostar's distance remaining on the
   * full route, scaled to the simplified line's length.
   */
  private fun alongOn(searched: SearchedRoute): Double? {
    val trip = navigationState.value.tripState as? TripState.Navigating ?: return null
    if (searched.fullLength <= 0.0) return 0.0
    val alongFull = searched.fullLength - trip.progress.distanceRemaining
    return alongFull * searched.simplified.length / searched.fullLength
  }

  private companion object {
    const val TAG = "RoadsideStops"
    const val MAX_POINTS_PER_QUERY = 150
    const val SEARCH_AHEAD_METRES = 150_000.0
    const val RERANK_EVERY_METRES = 250.0
  }
}
