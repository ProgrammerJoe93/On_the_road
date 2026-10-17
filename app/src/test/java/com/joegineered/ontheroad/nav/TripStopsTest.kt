package com.joegineered.ontheroad.nav

import com.joegineered.ontheroad.cameras.LatLng
import com.joegineered.ontheroad.cameras.MeasuredPolyline
import org.junit.Assert.assertEquals
import org.junit.Test
import uniffi.ferrostar.GeographicCoordinate

class TripStopsTest {
  // A road due east along 60°N.
  private val route = MeasuredPolyline(listOf(LatLng(60.0, 24.0), LatLng(60.0, 25.0)))

  private fun stop(name: String, lng: Double, lat: Double = 60.0) =
      Destination(GeographicCoordinate(lat, lng), label = name)

  @Test
  fun `a new stop goes in its place along the route`() {
    val stops = listOf(stop("A", 24.2), stop("C", 24.8))
    assertEquals(listOf("A", "B", "C"), insertStop(route, stops, stop("B", 24.5)).map { it.label })
    assertEquals(listOf("first", "A", "C"), insertStop(route, stops, stop("first", 24.1)).map { it.label })
    assertEquals(listOf("A", "C", "last"), insertStop(route, stops, stop("last", 24.9)).map { it.label })
  }

  @Test
  fun `a stop to the side of the road goes where it is closest to the route`() {
    val stops = listOf(stop("A", 24.2), stop("C", 24.8))
    assertEquals(listOf("A", "B", "C"), insertStop(route, stops, stop("B", 24.5, lat = 60.003)).map { it.label })
  }

  @Test
  fun `the remaining stops are the last ones, as waypoints are passed in order`() {
    val stops = listOf(stop("A", 24.2), stop("B", 24.5), stop("C", 24.8))
    // Three stops and the destination left: all three ahead.
    assertEquals(stops, remainingStops(stops, remainingWaypoints = 4))
    // Ferrostar may still list the start at first.
    assertEquals(stops, remainingStops(stops, remainingWaypoints = 5))
    assertEquals(listOf("C"), remainingStops(stops, remainingWaypoints = 2).map { it.label })
    assertEquals(emptyList<Destination>(), remainingStops(stops, remainingWaypoints = 1))
    assertEquals(emptyList<Destination>(), remainingStops(stops, remainingWaypoints = 0))
  }
}
