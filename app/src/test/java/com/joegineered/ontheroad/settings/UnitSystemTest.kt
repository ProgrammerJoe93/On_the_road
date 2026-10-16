package com.joegineered.ontheroad.settings

import com.joegineered.ontheroad.cameras.displayedSpeedLimit
import com.joegineered.ontheroad.cameras.parseMaxSpeedKmh
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UnitSystemTest {
  @Test
  fun `automatic units follow the region`() {
    assertTrue(UnitSystem.AUTO.isMetric("FI"))
    assertTrue(UnitSystem.AUTO.isMetric("GB"))
    assertFalse(UnitSystem.AUTO.isMetric("US"))
    assertFalse(UnitSystem.AUTO.isMetric("us"))
  }

  @Test
  fun `explicit units ignore the region`() {
    assertTrue(UnitSystem.METRIC.isMetric("US"))
    assertFalse(UnitSystem.IMPERIAL.isMetric("FI"))
  }

  @Test
  fun `camera limits show in the display units`() {
    assertEquals(80, displayedSpeedLimit(80, metric = true))
    assertEquals(50, displayedSpeedLimit(80, metric = false))
    // A limit tagged in mph shows as the same number again.
    for (mph in listOf(20, 25, 30, 45, 55, 65, 70)) {
      assertEquals(mph, displayedSpeedLimit(parseMaxSpeedKmh("$mph mph")!!, metric = false))
    }
  }
}
