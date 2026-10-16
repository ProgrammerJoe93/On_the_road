package com.joegineered.ontheroad.nav

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ParkedNearbyDetectorTest {
  private val detector = ParkedNearbyDetector()

  @Test
  fun `arrives after stopping near the destination for 20 seconds`() {
    assertFalse(detector.update(60.0, 0.2, nowMillis = 0))
    assertFalse(detector.update(60.0, 0.0, nowMillis = 19_000))
    assertTrue(detector.update(60.0, 0.0, nowMillis = 20_000))
  }

  @Test
  fun `moving again restarts the timer`() {
    detector.update(60.0, 0.0, nowMillis = 0)
    detector.update(50.0, 5.0, nowMillis = 15_000) // pulled away
    assertFalse(detector.update(40.0, 0.0, nowMillis = 25_000))
    assertTrue(detector.update(40.0, 0.0, nowMillis = 45_000))
  }

  @Test
  fun `stopping far from the destination is not arrival`() {
    detector.update(500.0, 0.0, nowMillis = 0)
    assertFalse(detector.update(500.0, 0.0, nowMillis = 120_000))
  }

  @Test
  fun `missing speed never counts as stopped`() {
    detector.update(30.0, null, nowMillis = 0)
    assertFalse(detector.update(30.0, null, nowMillis = 60_000))
  }
}
