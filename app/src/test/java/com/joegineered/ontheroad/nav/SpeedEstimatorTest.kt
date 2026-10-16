package com.joegineered.ontheroad.nav

import com.joegineered.ontheroad.cameras.LatLng
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpeedEstimatorTest {
  // Roughly 1 m of latitude.
  private val metre = 1.0 / 111_195.0

  private fun north(metres: Double) = LatLng(60.0 + metres * metre, 24.0)

  @Test
  fun `uses the reported GPS speed when present`() {
    val estimator = SpeedEstimator()
    assertEquals(13.9, estimator.update(north(0.0), 0, 13.9)!!, 0.0)
  }

  @Test
  fun `estimates speed from positions when none is reported`() {
    val estimator = SpeedEstimator(smoothing = 1.0)
    assertNull(estimator.update(north(0.0), 0, null))
    assertEquals(20.0, estimator.update(north(20.0), 1_000, null)!!, 0.2)
  }

  @Test
  fun `smooths estimated speed`() {
    val estimator = SpeedEstimator(smoothing = 0.5)
    estimator.update(north(0.0), 0, null)
    estimator.update(north(10.0), 1_000, null) // 10 m/s
    assertEquals(15.0, estimator.update(north(30.0), 2_000, null)!!, 0.2) // halfway to 20
  }

  @Test
  fun `ignores long gaps and duplicate fixes`() {
    val estimator = SpeedEstimator(smoothing = 1.0)
    estimator.update(north(0.0), 0, null)
    assertEquals(10.0, estimator.update(north(10.0), 1_000, null)!!, 0.2)
    assertEquals(10.0, estimator.update(north(10.0), 1_050, null)!!, 0.2) // 50 ms later
    assertEquals(10.0, estimator.update(north(500.0), 30_000, null)!!, 0.2) // 29 s gap
  }
}
