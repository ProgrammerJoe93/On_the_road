package com.joegineered.ontheroad.config

import com.joegineered.ontheroad.settings.UnitSystem
import com.joegineered.ontheroad.ui.formatTripDistance
import com.stadiamaps.ferrostar.ui.formatters.DistanceMeasurementSystem
import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

class AppConfigTest {
  private val defaultLocale = Locale.getDefault()

  @After
  fun restore() {
    AppConfig.unitSystem.value = UnitSystem.AUTO
    Locale.setDefault(defaultLocale)
  }

  @Test
  fun `instructions use the device language where Valhalla has it`() {
    assertEquals("fi-FI", AppConfig.instructionLanguage(Locale.forLanguageTag("fi-FI")))
    assertEquals("en-GB", AppConfig.instructionLanguage(Locale.forLanguageTag("en-GB")))
    // Same language, other country: the language's first variant.
    assertEquals("sv-SE", AppConfig.instructionLanguage(Locale.forLanguageTag("sv-FI")))
    assertEquals("en-US", AppConfig.instructionLanguage(Locale.forLanguageTag("ga-IE")))
  }

  @Test
  fun `day and night map styles`() {
    assertEquals(AppConfig.MAP_STYLE_URL, AppConfig.mapStyleUrl(night = false))
    assertEquals(AppConfig.NIGHT_MAP_STYLE_URL, AppConfig.mapStyleUrl(night = true))
  }

  @Test
  fun `distances follow the units setting`() {
    Locale.setDefault(Locale.ROOT)
    AppConfig.unitSystem.value = UnitSystem.METRIC
    assertEquals("850 m", formatTripDistance(850.0))
    assertEquals("12.3 km", formatTripDistance(12_345.0))
    assertEquals(DistanceMeasurementSystem.SI, AppConfig.distanceMeasurementSystem())

    AppConfig.unitSystem.value = UnitSystem.IMPERIAL
    assertEquals("7.7 mi", formatTripDistance(12_345.0))
    assertEquals(DistanceMeasurementSystem.IMPERIAL, AppConfig.distanceMeasurementSystem())
  }
}
