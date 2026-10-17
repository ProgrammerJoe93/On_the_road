package com.joegineered.ontheroad.cameras

import com.joegineered.ontheroad.cameras.TestGeometry.at
import com.joegineered.ontheroad.cameras.TestGeometry.northboundRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GeoTest {
  @Test
  fun `distances are in metres in both directions`() {
    assertEquals(1_000.0, distanceMetres(at(0.0), at(1_000.0)), 1.0)
    assertEquals(1_000.0, distanceMetres(at(0.0), at(0.0, east = 1_000.0)), 1.0)
    assertEquals(500.0, distanceMetres(at(0.0), at(300.0, east = 400.0)), 1.0)
    assertEquals(0.0, distanceMetres(at(0.0), at(0.0)), 0.0)
  }

  @Test
  fun `bearings run clockwise from north`() {
    assertEquals(0.0, bearingDegrees(at(0.0), at(100.0)), 0.5)
    assertEquals(90.0, bearingDegrees(at(0.0), at(0.0, east = 100.0)), 0.5)
    assertEquals(180.0, bearingDegrees(at(0.0), at(-100.0)), 0.5)
    assertEquals(270.0, bearingDegrees(at(0.0), at(0.0, east = -100.0)), 0.5)
  }

  @Test
  fun `bearing differences wrap around north`() {
    assertEquals(20.0, bearingDifference(350.0, 10.0), 1e-9)
    assertEquals(20.0, bearingDifference(10.0, 350.0), 1e-9)
    assertEquals(180.0, bearingDifference(90.0, 270.0), 1e-9)
    assertEquals(0.0, bearingDifference(45.0, 405.0), 1e-9)
  }

  @Test
  fun `a polyline measures its length and projects points onto it`() {
    val line = MeasuredPolyline(northboundRoute(2_000))
    assertEquals(2_000.0, line.length, 2.0)

    val projection = line.project(at(1_250.0, east = 30.0))!!
    assertEquals(1_250.0, projection.alongMetres, 2.0)
    assertEquals(30.0, projection.offsetMetres, 1.0)
    assertEquals(0.0, projection.segmentBearing, 0.5)

    // Beyond the end, a point projects onto the last vertex.
    val past = line.project(at(2_100.0))!!
    assertEquals(2_000.0, past.alongMetres, 2.0)
    assertEquals(100.0, past.offsetMetres, 2.0)
  }

  @Test
  fun `a single point is not a line to project onto`() {
    assertNull(MeasuredPolyline(listOf(at(0.0))).project(at(10.0)))
    assertEquals(0.0, MeasuredPolyline(emptyList()).length, 0.0)
  }

  @Test
  fun `simplification drops points within the tolerance and keeps corners`() {
    // North 1 km, with a 5 m wobble, then east 1 km.
    val route =
        (0..1_000 step 100).map { at(it.toDouble(), east = if (it == 500) 5.0 else 0.0) } +
            (100..1_000 step 100).map { at(1_000.0, east = it.toDouble()) }
    val simplified = simplify(route, toleranceMetres = 20.0)
    assertEquals(listOf(at(0.0), at(1_000.0), at(1_000.0, east = 1_000.0)), simplified)
    // A small tolerance keeps the wobble.
    assertEquals(true, simplify(route, toleranceMetres = 1.0).contains(at(500.0, east = 5.0)))
  }

  @Test
  fun `simplification leaves two-point lines alone`() {
    val line = listOf(at(0.0), at(10.0))
    assertEquals(line, simplify(line, toleranceMetres = 100.0))
  }
}
