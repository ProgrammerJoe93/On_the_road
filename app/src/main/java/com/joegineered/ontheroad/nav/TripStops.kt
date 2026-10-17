package com.joegineered.ontheroad.nav

import com.joegineered.ontheroad.cameras.LatLng
import com.joegineered.ontheroad.cameras.MeasuredPolyline

/**
 * Where a new [stop] goes among the trip's remaining [stops]: in the order they come along
 * [route], so adding fuel halfway doesn't send the driver back past stops they were heading for.
 * A stop that isn't near the route (projects onto its end) goes last, before the destination.
 */
fun insertStop(route: MeasuredPolyline, stops: List<Destination>, stop: Destination): List<Destination> {
  fun along(d: Destination) = route.project(LatLng(d.coordinate.lat, d.coordinate.lng))?.alongMetres
  val newAlong = along(stop) ?: return stops + stop
  val index = stops.indexOfFirst { existing -> (along(existing) ?: Double.MAX_VALUE) > newAlong }
  return if (index < 0) stops + stop else stops.toMutableList().apply { add(index, stop) }
}

/**
 * The [stops] still ahead, given how many waypoints Ferrostar has left (the stops still to reach,
 * then the destination). Ferrostar passes waypoints in order, so these are the last ones.
 */
fun remainingStops(stops: List<Destination>, remainingWaypoints: Int): List<Destination> =
    stops.takeLast((remainingWaypoints - 1).coerceIn(0, stops.size))
