package com.joegineered.ontheroad.ui

import com.stadiamaps.ferrostar.core.measurement.MeasurementSpeed
import com.stadiamaps.ferrostar.core.measurement.MeasurementSpeedUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeedReadingTest {
  private val limit50 = MeasurementSpeed(50.0, MeasurementSpeedUnit.KilometersPerHour)

  private fun kmh(value: Double) = value / 3.6

  @Test
  fun `converts GPS speed to km h`() {
    assertEquals(72, speedReading(20.0, null, metric = true).speed)
  }

  @Test
  fun `at the limit is not over, one above is`() {
    assertFalse(speedReading(kmh(50.0), limit50, metric = true).overLimit)
    assertFalse(speedReading(kmh(50.4), limit50, metric = true).overLimit) // shows 50
    assertTrue(speedReading(kmh(51.0), limit50, metric = true).overLimit)
  }

  @Test
  fun `no speed limit means never over`() {
    assertFalse(speedReading(kmh(130.0), null, metric = true).overLimit)
  }

  @Test
  fun `missing or invalid GPS speed shows nothing`() {
    assertNull(speedReading(null, limit50, metric = true).speed)
    assertNull(speedReading(-1.0, limit50, metric = true).speed)
    assertFalse(speedReading(null, limit50, metric = true).overLimit)
  }

  @Test
  fun `imperial compares in mph`() {
    val limit30mph = MeasurementSpeed(30.0, MeasurementSpeedUnit.MilesPerHour)
    val reading = speedReading(31.0 / 2.23694, limit30mph, metric = false)
    assertEquals(31, reading.speed)
    assertTrue(reading.overLimit)
  }
}
