package com.joegineered.ontheroad.nav

import com.joegineered.ontheroad.cameras.LatLng
import com.joegineered.ontheroad.cameras.MeasuredPolyline
import com.joegineered.ontheroad.cameras.distanceMetres
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class DeadReckonerTest {
  // A straight road heading east along 60°N, about 11 km long.
  private val start = LatLng(60.0, 24.0)
  private val route = MeasuredPolyline(listOf(start, LatLng(60.0, 24.1), LatLng(60.0, 24.2)))

  private fun reckoner(speed: Double? = 20.0, at: LatLng = start) =
      DeadReckoner().apply { onFix(PositionFix(at, timeMillis = 0, speed = speed)) }

  @Test
  fun `in a tunnel, the position moves along the route at the last speed`() {
    val estimate = reckoner().estimate(nowMillis = 5_000, route)!!
    assertEquals(100.0, distanceMetres(start, estimate.position), 1.0)
    assertEquals(90.0, estimate.bearing, 1.0)
    assertEquals(20.0, estimate.speed, 0.0)
    assertEquals(DeadReckoner.ESTIMATE_ACCURACY_METRES, estimate.accuracyMetres, 0.0)
  }

  @Test
  fun `it follows bends in the route`() {
    val bend = MeasuredPolyline(listOf(start, LatLng(60.0, 24.001), LatLng(60.1, 24.001)))
    // About 56 m east, then north: after 200 m the road heads north.
    val estimate = reckoner().estimate(nowMillis = 10_000, bend)!!
    assertEquals(0.0, estimate.bearing, 1.0)
    assertEquals(24.001, estimate.position.lng, 1e-6)
  }

  @Test
  fun `nothing is estimated for a short gap, or once the gap is too long`() {
    assertNull(reckoner().estimate(nowMillis = 2_000, route))
    assertNotNull(reckoner().estimate(nowMillis = 120_000, route))
    assertNull(reckoner().estimate(nowMillis = 121_000, route))
  }

  @Test
  fun `nothing is estimated when stopped, off the route, or without one`() {
    assertNull(reckoner(speed = 1.0).estimate(nowMillis = 5_000, route))
    assertNull(reckoner(at = LatLng(60.002, 24.0)).estimate(nowMillis = 5_000, route))
    assertNull(reckoner().estimate(nowMillis = 5_000, route = null))
  }

  @Test
  fun `a guess never reaches the destination`() {
    val nearEnd = route.pointAt(route.length - 300)!!.point
    assertNotNull(reckoner(at = nearEnd).estimate(nowMillis = 5_000, route))
    // 20 m/s for 10 s would end 100 m before the destination, inside its arrival range.
    assertNull(reckoner(at = nearEnd).estimate(nowMillis = 10_000, route))
  }

  @Test
  fun `a new fix ends the estimate`() {
    val reckoner = reckoner()
    assertNotNull(reckoner.estimate(nowMillis = 5_000, route))
    reckoner.onFix(PositionFix(route.pointAt(150.0)!!.point, timeMillis = 6_000, speed = 20.0))
    assertNull(reckoner.estimate(nowMillis = 7_000, route))
  }

  @Test
  fun `without a reported speed, it is measured from the fixes`() {
    val reckoner = DeadReckoner()
    reckoner.onFix(PositionFix(start, timeMillis = 0))
    reckoner.onFix(PositionFix(route.pointAt(20.0)!!.point, timeMillis = 1_000))
    val estimate = reckoner.estimate(nowMillis = 6_000, route)!!
    assertEquals(20.0, estimate.speed, 0.5)
    assertEquals(120.0, distanceMetres(start, estimate.position), 2.0)
  }
}
