package com.joegineered.ontheroad.car

import org.junit.Assert.assertEquals
import org.junit.Test

class TripPublisherTest {
  @Test
  fun `time to maneuver scales the step duration by the distance left`() {
    // 2 km step taking 120 s; 500 m to go.
    assertEquals(30L, TripPublisher.secondsToNextManeuver(500.0, 2000.0, 120.0))
  }

  @Test
  fun `time to maneuver is clamped and safe for empty steps`() {
    assertEquals(120L, TripPublisher.secondsToNextManeuver(2500.0, 2000.0, 120.0))
    assertEquals(0L, TripPublisher.secondsToNextManeuver(-5.0, 2000.0, 120.0))
    assertEquals(0L, TripPublisher.secondsToNextManeuver(10.0, 0.0, 0.0))
  }
}
