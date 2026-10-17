package com.joegineered.ontheroad.ui

import kotlin.math.PI
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.tan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FitBoundsTest {
  @Test
  fun `the whole world fits a 512 dp square at zoom 0`() {
    val fit = fitBounds(-85.0511, -180.0, 85.0511, 180.0, widthDp = 512.0, heightDp = 512.0)
    assertEquals(0.0, fit.zoom, 0.01)
    assertEquals(0.0, fit.lat, 0.01)
    assertEquals(0.0, fit.lng, 0.01)
  }

  @Test
  fun `the tighter side sets the zoom and the centre is the Mercator midpoint`() {
    fun mercatorY(lat: Double) = ln(tan(PI / 4 + Math.toRadians(lat) / 2)) / (2 * PI)
    // Helsinki to Tampere: taller than wide in Mercator, so the height limits the zoom.
    val fit = fitBounds(59.98, 23.10, 61.50, 25.64, widthDp = 300.0, heightDp = 250.0)
    val worldDp = 512 * 2.0.pow(fit.zoom)
    assertEquals(250.0, worldDp * (mercatorY(61.50) - mercatorY(59.98)), 0.01)
    assertTrue(worldDp * (25.64 - 23.10) / 360 < 300.0)
    assertEquals(24.37, fit.lng, 0.01)
    // North of the plain average (60.74), as Mercator stretches higher latitudes.
    assertTrue(fit.lat > 60.74 && fit.lat < 60.8)
  }

  @Test
  fun `a single point uses the maximum zoom`() {
    assertEquals(16.0, fitBounds(60.0, 24.0, 60.0, 24.0, 300.0, 300.0).zoom, 0.0)
  }
}
