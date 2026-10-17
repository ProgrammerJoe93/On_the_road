package com.joegineered.ontheroad.nav

/** A finished trip, shown until the driver dismisses it. */
data class Arrival(
    val destination: Destination?,
    /** Distance driven, in metres. */
    val distanceMetres: Double,
    /** Time from start to arrival, in seconds. */
    val durationSeconds: Long,
    /** After a stop on the way, the destination to continue to. */
    val continueTo: Destination? = null,
)

/**
 * Detects arrival when the driver stops close to the destination without reaching the very end of
 * the route, e.g. after parking just short of it or on the other side of the street. Ferrostar
 * only completes a trip once the end of the route is reached.
 */
class ParkedNearbyDetector(
    private val maxDistanceMetres: Double = 100.0,
    private val maxSpeedMetresPerSecond: Double = 1.0,
    private val stoppedForMillis: Long = 20_000,
) {
  private var stoppedSince: Long? = null

  /** Returns true once the driver has been stopped near the destination long enough. */
  fun update(distanceRemainingMetres: Double, speedMetresPerSecond: Double?, nowMillis: Long): Boolean {
    val stoppedNearby =
        distanceRemainingMetres <= maxDistanceMetres &&
            // Without a speed reading we can't tell parked from crawling, so don't guess.
            speedMetresPerSecond != null &&
            speedMetresPerSecond <= maxSpeedMetresPerSecond
    if (!stoppedNearby) {
      stoppedSince = null
      return false
    }
    val since = stoppedSince ?: nowMillis.also { stoppedSince = it }
    return nowMillis - since >= stoppedForMillis
  }

  fun reset() {
    stoppedSince = null
  }
}
