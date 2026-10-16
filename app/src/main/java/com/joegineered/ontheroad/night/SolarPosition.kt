package com.joegineered.ontheroad.night

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Sun elevation above the horizon, using the low-precision formulas from the Astronomical Almanac
 * (accurate to about 0.1°, far better than day/night switching needs). Works at any latitude,
 * including midnight sun and polar night in Finland.
 */
object SolarPosition {
  /** Elevation of the sun's centre in degrees; negative when below the horizon. */
  fun elevationDegrees(latitude: Double, longitude: Double, epochMillis: Long): Double {
    val n = epochMillis / 86_400_000.0 + 2_440_587.5 - 2_451_545.0 // days since J2000.0

    val meanLongitude = normalize(280.460 + 0.9856474 * n)
    val meanAnomaly = rad(normalize(357.528 + 0.9856003 * n))
    val eclipticLongitude =
        rad(meanLongitude + 1.915 * sin(meanAnomaly) + 0.020 * sin(2 * meanAnomaly))
    val obliquity = rad(23.439 - 0.0000004 * n)

    val rightAscension =
        deg(atan2(cos(obliquity) * sin(eclipticLongitude), cos(eclipticLongitude)))
    val declination = asin(sin(obliquity) * sin(eclipticLongitude))

    val siderealTime = normalize((18.697374558 + 24.06570982441908 * n) * 15.0 + longitude)
    val hourAngle = rad(siderealTime - rightAscension)

    val lat = rad(latitude)
    return deg(
        asin(sin(lat) * sin(declination) + cos(lat) * cos(declination) * cos(hourAngle))
    )
  }

  private fun normalize(degrees: Double) = ((degrees % 360.0) + 360.0) % 360.0

  private fun rad(degrees: Double) = degrees * PI / 180.0

  private fun deg(radians: Double) = radians * 180.0 / PI
}

/**
 * Day/night with hysteresis, so the theme doesn't flicker while the sun sits on the horizon. Night
 * starts just after sunset (the sun's centre 0.833° below the horizon, the official definition)
 * and ends at sunrise.
 */
class DayNightTracker(
    private val nightBelowDegrees: Double = -1.0,
    private val dayAboveDegrees: Double = -0.5,
) {
  private var night: Boolean? = null

  fun isNight(sunElevationDegrees: Double): Boolean {
    val wasNight = night
    val nowNight =
        when {
          sunElevationDegrees < nightBelowDegrees -> true
          sunElevationDegrees > dayAboveDegrees -> false
          else -> wasNight ?: (sunElevationDegrees < -0.833)
        }
    night = nowNight
    return nowNight
  }
}
