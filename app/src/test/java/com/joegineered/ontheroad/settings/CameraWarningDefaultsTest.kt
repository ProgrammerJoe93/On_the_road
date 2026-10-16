package com.joegineered.ontheroad.settings

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
}
