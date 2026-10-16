package com.joegineered.ontheroad.nav

import com.joegineered.ontheroad.cameras.LatLng
import com.joegineered.ontheroad.cameras.distanceMetres

/**
 * The current speed: the GPS-reported speed when available, otherwise estimated from the distance
 * and time between consecutive positions (smoothed), as some providers, including Ferrostar's
 * route simulator, don't report speed.
 */
class SpeedEstimator(private val smoothing: Double = 0.5) {
  private var lastPosition: LatLng? = null
  private var lastTimeMillis: Long? = null
  private var estimate: Double? = null

  fun update(position: LatLng, timeMillis: Long, reportedSpeed: Double?): Double? {
    val previousPosition = lastPosition
    val previousTime = lastTimeMillis
    lastPosition = position
    lastTimeMillis = timeMillis

    if (reportedSpeed != null && reportedSpeed >= 0) {
      estimate = reportedSpeed
      return reportedSpeed
    }
    if (previousPosition == null || previousTime == null) return estimate

    val seconds = (timeMillis - previousTime) / 1000.0
    // Ignore duplicate fixes and long gaps, which say nothing about the current speed.
    if (seconds < MIN_INTERVAL_SECONDS || seconds > MAX_INTERVAL_SECONDS) return estimate

    val measured = distanceMetres(previousPosition, position) / seconds
    estimate = estimate?.let { it + smoothing * (measured - it) } ?: measured
    return estimate
  }

  fun reset() {
    lastPosition = null
    lastTimeMillis = null
    estimate = null
  }

  private companion object {
    const val MIN_INTERVAL_SECONDS = 0.2
    const val MAX_INTERVAL_SECONDS = 10.0
  }
}
