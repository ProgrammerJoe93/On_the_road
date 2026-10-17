package com.joegineered.ontheroad.nav

import com.joegineered.ontheroad.cameras.LatLng
import com.joegineered.ontheroad.cameras.MeasuredPolyline

/** A position fix, as the dead reckoner needs it. */
data class PositionFix(
    val position: LatLng,
    val timeMillis: Long,
    /** Metres per second, if the provider reports it. */
    val speed: Double? = null,
)

/** A position estimated along the route while there's no fix. */
data class EstimatedPosition(
    val position: LatLng,
    /** Degrees clockwise from north, along the route. */
    val bearing: Double,
    val speed: Double,
    val accuracyMetres: Double,
)

/**
 * Keeps the position moving along the route while GPS is lost, such as in a tunnel, so the
 * instructions don't stall: from the last fix, at the last speed. Estimates only start after
 * [gapMillis] without a fix and stop after [maxMillis], when the guess is no longer worth
 * showing. They need the driver to have been moving (at least [minSpeed]) and on the route, and
 * stop short of the destination, so a guess never ends the trip.
 *
 * The estimates report [ESTIMATE_ACCURACY_METRES]: Ferrostar ignores fixes worse than 15 m for
 * route deviation, so a wrong guess can't trigger a reroute, but advances steps on fixes up to
 * 32 m, so the instructions keep up.
 */
class DeadReckoner(
    private val gapMillis: Long = 3_000,
    private val maxMillis: Long = 120_000,
    private val minSpeed: Double = 4.0,
    private val maxRouteOffsetMetres: Double = 50.0,
) {
  private val speedEstimator = SpeedEstimator()
  private var lastFix: PositionFix? = null
  private var lastSpeed: Double? = null

  fun onFix(fix: PositionFix) {
    lastSpeed = speedEstimator.update(fix.position, fix.timeMillis, fix.speed)
    lastFix = fix
  }

  /** The estimated position at [nowMillis] along [route], or null if there's nothing to estimate. */
  fun estimate(nowMillis: Long, route: MeasuredPolyline?): EstimatedPosition? {
    val fix = lastFix ?: return null
    val speed = lastSpeed ?: return null
    route ?: return null
    val elapsed = nowMillis - fix.timeMillis
    if (elapsed < gapMillis || elapsed > maxMillis || speed < minSpeed) return null
    val start = route.project(fix.position) ?: return null
    if (start.offsetMetres > maxRouteOffsetMetres) return null
    val along = start.alongMetres + speed * elapsed / 1000.0
    if (along > route.length - ARRIVAL_MARGIN_METRES) return null
    val point = route.pointAt(along) ?: return null
    return EstimatedPosition(point.point, point.segmentBearing, speed, ESTIMATE_ACCURACY_METRES)
  }

  companion object {
    const val ESTIMATE_ACCURACY_METRES = 25.0

    /** More than Ferrostar's arrival range (100 m), with some margin. */
    private const val ARRIVAL_MARGIN_METRES = 150.0
  }
}
