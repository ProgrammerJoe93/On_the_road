package com.joegineered.ontheroad.settings

import com.joegineered.ontheroad.chargers.SocketType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraWarningDefaultsTest {
  @Test
  fun `warnings default off where restricted`() {
    assertFalse(SettingsRepository.cameraWarningsAllowedByDefault("DE"))
    assertFalse(SettingsRepository.cameraWarningsAllowedByDefault("ch"))
    assertTrue(SettingsRepository.cameraWarningsAllowedByDefault("FI"))
    assertTrue(SettingsRepository.cameraWarningsAllowedByDefault("GB"))
  }

  @Test
  fun `stored plug types migrate the old CCS name and treat empty as all`() {
    assertEquals(
        setOf(SocketType.CCS2, SocketType.TYPE2),
        SettingsRepository.decodePlugTypes(setOf("CCS", "TYPE2")),
    )
    assertEquals(SocketType.entries.toSet(), SettingsRepository.decodePlugTypes(null))
    assertEquals(SocketType.entries.toSet(), SettingsRepository.decodePlugTypes(setOf("REMOVED_TYPE")))
  }
}
