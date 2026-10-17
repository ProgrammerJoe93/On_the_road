package com.joegineered.ontheroad.cameras

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PolylineIndexTest {
  // A long straight segment (about 11 km east), as motorways often are, then a turn north.
  private val route = listOf(LatLng(60.0, 24.0), LatLng(60.0, 24.2), LatLng(60.1, 24.2))
  private val index = PolylineIndex(route)

  @Test
  fun `points beside the middle of a long segment are near`() {
    assertTrue(index.isNear(LatLng(60.0002, 24.1), maxMetres = 30.0))
    assertTrue(index.isNear(LatLng(60.05, 24.2003), maxMetres = 30.0))
  }

  @Test
  fun `points further away are not`() {
    assertFalse(index.isNear(LatLng(60.001, 24.1), maxMetres = 30.0))
    assertFalse(index.isNear(LatLng(60.05, 24.1), maxMetres = 30.0))
  }

  @Test
  fun `points next to a cell boundary still find the segment`() {
    // The route runs along latitude 60.0, a cell edge; a point just south is in the next cell.
    assertTrue(index.isNear(LatLng(59.9999, 24.05), maxMetres = 30.0))
  }
}
