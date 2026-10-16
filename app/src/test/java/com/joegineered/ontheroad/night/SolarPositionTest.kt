package com.joegineered.ontheroad.night

import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SolarPositionTest {
  private fun millis(iso: String) = ZonedDateTime.parse(iso).toInstant().toEpochMilli()

  private fun helsinki(iso: String) = SolarPosition.elevationDegrees(60.17, 24.94, millis(iso))

  @Test
  fun `midsummer noon in Helsinki is high, and midnight stays near the horizon`() {
    // Max elevation at the June solstice: 90 - 60.17 + 23.44 = 53.3 degrees.
    assertEquals(53.3, helsinki("2026-06-21T13:25:00+03:00"), 0.5)
    // Helsinki has no true night in midsummer: the sun dips only ~6.4 degrees below.
    assertEquals(-6.4, helsinki("2026-06-22T01:25:00+03:00"), 0.5)
  }

  @Test
  fun `midwinter noon in Helsinki barely clears the horizon`() {
    // 90 - 60.17 - 23.44 = 6.4 degrees.
    assertEquals(6.4, helsinki("2026-12-21T12:25:00+02:00"), 0.5)
  }

  @Test
  fun `sunset in Helsinki on 16 October 2026 is around 18 08`() {
    assertTrue(helsinki("2026-10-16T17:55:00+03:00") > 0)
    assertTrue(helsinki("2026-10-16T18:20:00+03:00") < -0.833)
  }

  @Test
  fun `midnight sun in Utsjoki and polar night`() {
    // Utsjoki (69.9 N) at midnight on midsummer: the sun stays up.
    assertTrue(SolarPosition.elevationDegrees(69.9, 27.0, millis("2026-06-21T00:30:00+03:00")) > 0)
    // ...and at noon in midwinter it stays down.
    assertTrue(SolarPosition.elevationDegrees(69.9, 27.0, millis("2026-12-21T12:00:00+02:00")) < 0)
  }

  @Test
  fun `day night tracker uses hysteresis`() {
    val tracker = DayNightTracker()
    assertFalse(tracker.isNight(5.0))
    assertFalse(tracker.isNight(-0.7)) // between thresholds: stays day
    assertTrue(tracker.isNight(-1.2))
    assertTrue(tracker.isNight(-0.7)) // between thresholds: stays night
    assertFalse(tracker.isNight(-0.3))
  }
}
