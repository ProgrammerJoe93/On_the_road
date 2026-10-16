package com.joegineered.ontheroad.cameras

import com.joegineered.ontheroad.cameras.TestGeometry.at
import com.joegineered.ontheroad.cameras.TestGeometry.camera
import com.joegineered.ontheroad.cameras.TestGeometry.northboundRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteCameraMatcherTest {
  private val matcher = RouteCameraMatcher()
  private val route = northboundRoute()

  @Test
  fun `camera on the route is matched with its distance along the route`() {
    val result = matcher.match(route, listOf(camera(1, at(500.0))))

    assertEquals(1, result.size)
    assertEquals(500.0, result.single().alongRouteMetres, 1.0)
  }

  @Test
  fun `camera beside the road within tolerance is matched`() {
    val result = matcher.match(route, listOf(camera(1, at(800.0, east = 12.0))))

    assertEquals(listOf(1L), result.map { it.camera.id })
    assertEquals(12.0, result.single().offsetMetres, 0.5)
  }

  @Test
  fun `camera too far from the route is ignored`() {
    val result = matcher.match(route, listOf(camera(1, at(800.0, east = 40.0))))

    assertTrue(result.isEmpty())
  }

  @Test
  fun `tolerance is tighter near junctions`() {
    val cameras = listOf(camera(1, at(1000.0, east = 20.0)))

    assertEquals(1, matcher.match(route, cameras).size)
    assertTrue(matcher.match(route, cameras, maneuverPoints = listOf(at(1000.0))).isEmpty())
  }

  @Test
  fun `camera enforcing the opposite direction is ignored`() {
    val result =
        matcher.match(
            route,
            listOf(
                camera(1, at(500.0), 0.0), // northbound, like us
                camera(2, at(900.0), 180.0), // southbound
                camera(3, at(1300.0), 10.0, 190.0), // both directions
            ),
        )

    assertEquals(listOf(1L, 3L), result.map { it.camera.id })
  }

  @Test
  fun `camera without a direction is assumed to apply`() {
    assertEquals(1, matcher.match(route, listOf(camera(1, at(500.0)))).size)
  }

  @Test
  fun `enforcement section must start before the camera in our direction`() {
    val result =
        matcher.match(
            route,
            listOf(
                camera(1, at(600.0), from = at(400.0)), // section runs northbound: applies
                camera(2, at(1200.0), from = at(1400.0)), // section runs southbound: ignored
            ),
        )

    assertEquals(listOf(1L), result.map { it.camera.id })
  }

  @Test
  fun `camera closer to a parallel street is assigned to that street`() {
    // A side street 25 m east of our route; the camera sits on its west kerb, 20 m from us.
    val parallelStreet = listOf(at(0.0, east = 25.0), at(2000.0, east = 25.0))
    val result =
        matcher.match(
            route,
            listOf(camera(1, at(700.0, east = 20.0))),
            nearbyRoads = listOf(parallelStreet),
        )

    assertTrue(result.isEmpty())
  }

  @Test
  fun `nearby road that is the route itself does not reject the camera`() {
    val routeAsOsmWay = listOf(at(0.0, east = 1.0), at(2000.0, east = 1.0))
    val result =
        matcher.match(
            route,
            listOf(camera(1, at(700.0, east = 10.0))),
            nearbyRoads = listOf(routeAsOsmWay),
        )

    assertEquals(1, result.size)
  }

  @Test
  fun `results are ordered along the route and de-duplicated`() {
    val result =
        matcher.match(
            route,
            listOf(camera(2, at(1500.0)), camera(1, at(300.0)), camera(2, at(1500.0))),
        )

    assertEquals(listOf(1L, 2L), result.map { it.camera.id })
  }
}
