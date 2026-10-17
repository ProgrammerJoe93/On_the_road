package com.joegineered.ontheroad.stops

import com.joegineered.ontheroad.cameras.LatLng
import com.joegineered.ontheroad.cameras.TestGeometry.at
import com.joegineered.ontheroad.cameras.TestGeometry.northboundRoute
import com.joegineered.ontheroad.chargers.SocketType
import com.stadiamaps.ferrostar.core.NavigationState
import java.io.IOException
import java.time.Instant
import java.util.Date
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.ferrostar.GeographicCoordinate
import uniffi.ferrostar.RouteDeviation
import uniffi.ferrostar.TripProgress
import uniffi.ferrostar.TripState
import uniffi.ferrostar.TripSummary
import uniffi.ferrostar.UserLocation

@OptIn(ExperimentalCoroutinesApi::class)
class RoadsideStopControllerTest {
  private val route = northboundRoute(10_000)
  private val navigation = MutableStateFlow(NavigationState())
  private val queries = mutableListOf<String>()
  private var online = true
  private var plugTypes = SocketType.entries.toSet()

  /** Overpass with fuel at 3 km and 6 km, and a CCS charger at 5 km, beside the road. */
  private var answer: (String) -> String = { query ->
    when {
      "amenity=fuel" in query -> elements(node(1, at(6_000.0, east = 50.0), "Far"), node(2, at(3_000.0, east = 50.0), "Near"))
      "charging_station" in query ->
          elements(
              node(3, at(5_000.0, east = 20.0), "CCS", """"socket:type2_combo":"2""""),
              node(4, at(4_000.0, east = 20.0), "Type 2", """"socket:type2":"2""""),
          )
      else -> elements()
    }
  }

  private fun node(id: Long, at: LatLng, name: String, extraTags: String = "") =
      """{"type":"node","id":$id,"lat":${at.lat},"lon":${at.lng},"tags":{"name":"$name"${if (extraTags.isEmpty()) "" else ",$extraTags"}}}"""

  private fun elements(vararg nodes: String) = """{"elements":[${nodes.joinToString(",")}]}"""

  private fun TestScope.controller() =
      RoadsideStopController(
              navigationState = navigation,
              query = { q ->
                queries += q
                answer(q)
              },
              plugTypes = { plugTypes },
              isOnline = { online },
              scope = backgroundScope,
          )
          .also { it.start() }

  /** Navigating [route] (to its last point), with [driven] metres done. */
  private fun drive(driven: Double, geometry: List<LatLng> = route) {
    val here = UserLocation(GeographicCoordinate(geometry[0].lat, geometry[0].lng), 5.0, null, Instant.EPOCH, null)
    val length = (geometry.size - 1) * 100.0
    navigation.value =
        NavigationState(
            TripState.Navigating(
                null,
                here,
                here,
                emptyList(),
                emptyList(),
                TripProgress(0.0, length - driven, 0.0),
                TripSummary(driven, driven, Date(0), null),
                RouteDeviation.NoDeviation,
                null,
                null,
                null,
            ),
            geometry.map { GeographicCoordinate(it.lat, it.lng) },
            false,
        )
  }

  /** Runs the controller's pending work, which [kotlinx.coroutines.test.advanceUntilIdle] skips as it runs in the background scope. */
  private fun TestScope.settle() = runCurrent()

  private suspend fun RoadsideStopController.results() =
      state.first { it is StopSearch.Results || it is StopSearch.Failed }

  @Test
  fun `without a trip there is nothing to search along`() = runTest {
    val stops = controller()
    stops.search(StopCategory.FUEL)
    settle()
    assertEquals(StopSearch.Idle, stops.state.value)
    assertTrue(queries.isEmpty())
  }

  @Test
  fun `finds stops ahead, nearest first`() = runTest {
    drive(1_000.0)
    val stops = controller()
    stops.search(StopCategory.FUEL)
    val results = stops.results() as StopSearch.Results
    assertEquals(listOf("Near", "Far"), results.stops.map { it.stop.name })
    assertEquals(2_000.0, results.stops[0].aheadMetres, 30.0)
    assertEquals(50.0, results.stops[0].offRouteMetres, 5.0)
    // A 10 km route is one query.
    assertEquals(1, queries.size)
  }

  @Test
  fun `chargers follow the plug-type setting`() = runTest {
    drive(0.0)
    plugTypes = setOf(SocketType.CCS2)
    val stops = controller()
    stops.search(StopCategory.CHARGING)
    val results = stops.results() as StopSearch.Results
    assertEquals(listOf("CCS"), results.stops.map { it.stop.name })
  }

  @Test
  fun `distances update while driving, and passed stops drop off`() = runTest {
    drive(1_000.0)
    val stops = controller()
    stops.search(StopCategory.FUEL)
    stops.results()
    drive(4_000.0)
    val updated = stops.state.first { (it as? StopSearch.Results)?.stops?.size == 1 } as StopSearch.Results
    assertEquals("Far", updated.stops.single().stop.name)
    assertEquals(2_000.0, updated.stops.single().aheadMetres, 30.0)
  }

  @Test
  fun `a failed search says whether the phone is offline`() = runTest {
    drive(0.0)
    answer = { throw IOException("busy") }
    val stops = controller()
    stops.search(StopCategory.FOOD)
    assertEquals(StopSearch.Failed(StopCategory.FOOD, offline = false), stops.results())
    online = false
    stops.search(StopCategory.FOOD)
    assertEquals(
        StopSearch.Failed(StopCategory.FOOD, offline = true),
        stops.state.first { it == StopSearch.Failed(StopCategory.FOOD, offline = true) },
    )
  }

  @Test
  fun `a reroute to the same destination searches again`() = runTest {
    drive(0.0)
    val stops = controller()
    stops.search(StopCategory.FUEL)
    stops.results()
    // A new route that starts 50 m to the side but ends at the same place.
    val rerouted = listOf(at(0.0, east = 50.0)) + route.drop(1)
    drive(0.0, rerouted)
    settle()
    assertEquals(2, queries.size)
    assertTrue(stops.state.value is StopSearch.Results)
  }

  @Test
  fun `a trip somewhere else, or the end of the trip, clears the search`() = runTest {
    drive(0.0)
    val stops = controller()
    stops.search(StopCategory.FUEL)
    stops.results()
    drive(0.0, northboundRoute(5_000))
    settle()
    assertEquals(StopSearch.Idle, stops.state.value)

    stops.search(StopCategory.FUEL)
    stops.results()
    navigation.value = NavigationState()
    settle()
    assertEquals(StopSearch.Idle, stops.state.value)
  }
}
