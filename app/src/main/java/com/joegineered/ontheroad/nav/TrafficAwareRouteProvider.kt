package com.joegineered.ontheroad.nav

import android.util.Log
import com.joegineered.ontheroad.cameras.LatLng
import com.joegineered.ontheroad.traffic.ClosureAvoidance
import com.joegineered.ontheroad.traffic.TrafficEvent
import com.stadiamaps.ferrostar.core.CustomRouteProvider
import com.stadiamaps.ferrostar.core.InvalidStatusCodeException
import com.stadiamaps.ferrostar.core.NoResponseBodyException
import com.stadiamaps.ferrostar.core.http.HttpClientProvider
import com.stadiamaps.ferrostar.core.withJsonOptions
import uniffi.ferrostar.Route
import uniffi.ferrostar.RouteAdapter
import uniffi.ferrostar.UserLocation
import uniffi.ferrostar.Waypoint
import uniffi.ferrostar.WellKnownRouteProvider

/**
 * Valhalla routing that avoids roads currently closed (from live traffic), using Valhalla's
 * `exclude_polygons`. Request building and response parsing are Ferrostar's own Valhalla adapter;
 * only the options change per request. [baseOptions] is read for each request, so settings such as
 * units and roads to avoid apply to the next route. If no route exists around the closures (e.g. the
 * destination is on a closed road), it routes without them rather than failing.
 */
class TrafficAwareRouteProvider(
    private val endpoint: String,
    private val profile: String,
    private val baseOptions: (alternates: Int) -> Map<String, Any>,
    private val httpClient: HttpClientProvider,
    private val closures: () -> List<TrafficEvent>,
) : CustomRouteProvider {

  /** The best route only; Ferrostar uses this to start trips and reroute. */
  override suspend fun getRoutes(userLocation: UserLocation, waypoints: List<Waypoint>): List<Route> =
      getRoutes(userLocation, waypoints, alternates = 0)

  /** The best route first, followed by up to [alternates] others, for choosing between them. */
  suspend fun getRoutes(userLocation: UserLocation, waypoints: List<Waypoint>, alternates: Int): List<Route> {
    val here = LatLng(userLocation.coordinates.lat, userLocation.coordinates.lng)
    val destination = waypoints.lastOrNull()?.coordinate?.let { LatLng(it.lat, it.lng) }
    val polygons = ClosureAvoidance.excludePolygons(closures(), from = here, to = destination)
    val baseOptions = baseOptions(alternates)
    if (polygons.isEmpty()) return route(baseOptions, userLocation, waypoints)
    Log.i(TAG, "Routing around ${polygons.size} closures")
    return try {
      route(baseOptions + ("exclude_polygons" to polygons), userLocation, waypoints)
    } catch (e: InvalidStatusCodeException) {
      Log.w(TAG, "No route around ${polygons.size} closures (HTTP ${e.statusCode}); ignoring closures")
      route(baseOptions, userLocation, waypoints)
    }
  }

  private suspend fun route(
      options: Map<String, Any>,
      userLocation: UserLocation,
      waypoints: List<Waypoint>,
  ): List<Route> {
    val adapter =
        RouteAdapter.fromWellKnownRouteProvider(
            WellKnownRouteProvider.Valhalla(endpoint, profile).withJsonOptions(options)
        )
    val response = httpClient.call(adapter.generateRequest(userLocation, waypoints))
    if (!response.isSuccessful) throw InvalidStatusCodeException(response.code)
    val body = response.bodyBytes() ?: throw NoResponseBodyException()
    return adapter.parseResponse(body)
  }

  private companion object {
    const val TAG = "TrafficAwareRoutes"
  }
}
