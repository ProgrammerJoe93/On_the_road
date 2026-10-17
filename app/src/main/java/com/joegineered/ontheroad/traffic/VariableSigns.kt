package com.joegineered.ontheroad.traffic

import com.joegineered.ontheroad.cameras.LatLng
import com.joegineered.ontheroad.cameras.MeasuredPolyline
import com.joegineered.ontheroad.cameras.PolylineIndex
import com.joegineered.ontheroad.cameras.PolylineProjection
import com.joegineered.ontheroad.cameras.bearingDegrees

enum class VariableSignType {
  SPEED_LIMIT,
  WARNING,
  INFORMATION,
}

/** Which way along the road (by road address) a sign faces traffic. */
enum class RoadDirection {
  INCREASING,
  DECREASING,
}

/**
 * Fintraffic's road address: road number, road part and metres from the start of the part. It
 * orders points along a road, which tells which way a route drives along it.
 */
data class RoadAddress(val road: Int, val part: Int, val distance: Int) : Comparable<RoadAddress> {
  override fun compareTo(other: RoadAddress): Int =
      compareValuesBy(this, other, { it.road }, { it.part }, { it.distance })
}

/**
 * A variable (electronic) road sign from Digitraffic, as it shows now: a variable speed limit, a
 * warning sign, or a message panel.
 */
data class VariableSign(
    val id: String,
    val type: VariableSignType,
    val position: LatLng,
    val address: RoadAddress?,
    val direction: RoadDirection?,
    /** Speed limit signs only: the limit shown, or null when the sign is blank. */
    val speedLimitKmh: Int? = null,
    /** The warning sign shown, by its Finnish traffic sign code (e.g. "142", roadworks). */
    val warningCode: String? = null,
    /** Message text, as screens of rows (signs with more than one screen alternate them). */
    val screens: List<List<String>> = emptyList(),
)

data class RouteVariableSign(val sign: VariableSign, val alongRouteMetres: Double)

/** What the variable signs passed and ahead on the route say, for the road signs on screen. */
data class VariableSignState(
    /** The variable speed limit in force, if the last speed limit sign passed shows one. */
    val speedLimitKmh: Int? = null,
    /** Warning sign codes passed recently (e.g. "155", elk). */
    val warningCodes: List<String> = emptyList(),
    /** The message sign just ahead, to show its text. */
    val message: VariableSign? = null,
)

/** Signs further than this from the route aren't on it. */
private const val MAX_OFFSET_METRES = 40.0

/** A sign this close to the route is on it even when its direction can't be told. */
private const val SAME_CARRIAGEWAY_METRES = 12.0

/** Signs at road addresses this far apart along the route show which way it drives the road. */
private const val MIN_TREND_METRES = 100.0

/**
 * The [signs] on [route] for its direction of travel, ordered by where the route passes them.
 *
 * Signs are matched by road address: two signs of the same road passed in increasing address order
 * mean the route drives that road in the increasing direction, so signs for the other direction
 * are left out. Where that can't be told, a sign counts if it's on the right of the route (on
 * motorways each direction's signs are on its own carriageway), or practically on it. Speed limit
 * signs need the direction to be known, so that a gantry over a road the route only crosses
 * doesn't set the limit.
 */
fun matchVariableSigns(route: List<LatLng>, signs: List<VariableSign>): List<RouteVariableSign> {
  if (route.size < 2 || signs.isEmpty()) return emptyList()
  val line = MeasuredPolyline(route)
  val index = PolylineIndex(route)
  val candidates =
      signs.mapNotNull { sign ->
        if (!index.isNear(sign.position, MAX_OFFSET_METRES)) return@mapNotNull null
        val projection = line.project(sign.position)?.takeIf { it.offsetMetres <= MAX_OFFSET_METRES }
        projection?.let { sign to it }
      }
  val directions =
      candidates
          .filter { it.first.address != null }
          .groupBy { it.first.address!!.road }
          .mapValues { (_, onRoad) -> travelDirection(onRoad) }
  return candidates
      .filter { (sign, projection) ->
        val travel = sign.address?.let { directions[it.road] }
        when {
          travel != null -> sign.direction == null || sign.direction == travel
          sign.type == VariableSignType.SPEED_LIMIT -> false
          else -> projection.offsetMetres <= SAME_CARRIAGEWAY_METRES || isRightOf(projection, sign.position)
        }
      }
      .map { (sign, projection) -> RouteVariableSign(sign, projection.alongMetres) }
      .sortedBy { it.alongRouteMetres }
}

/** Which way the route drives a road, from the road addresses of its signs along the route. */
private fun travelDirection(onRoad: List<Pair<VariableSign, PolylineProjection>>): RoadDirection? {
  val ordered = onRoad.sortedBy { it.second.alongMetres }
  var votes = 0
  for ((a, b) in ordered.zipWithNext()) {
    if (b.second.alongMetres - a.second.alongMetres < MIN_TREND_METRES) continue
    votes += a.first.address!!.compareTo(b.first.address!!).let { if (it < 0) 1 else if (it > 0) -1 else 0 }
  }
  return when {
    votes > 0 -> RoadDirection.INCREASING
    votes < 0 -> RoadDirection.DECREASING
    else -> null
  }
}

private fun isRightOf(projection: PolylineProjection, point: LatLng): Boolean {
  val relative = (bearingDegrees(projection.point, point) - projection.segmentBearing + 360.0) % 360.0
  return relative in 0.0..180.0
}

/**
 * Follows the variable signs on the route as the driver passes them.
 *
 * A variable speed limit applies from its sign until the next speed limit sign, but the end of a
 * variable limit stretch is marked by an ordinary sign that isn't in the data. So the limit is
 * dropped when the road's mapped limit changes after the sign (a new road or a fixed sign), or
 * after [maxLimitMetres] without another variable sign.
 */
class VariableSignTracker(
    private val maxLimitMetres: Double = 5_000.0,
    private val warningMetres: Double = 1_500.0,
    private val messageAheadMetres: Double = 1_000.0,
    private val messageBehindMetres: Double = 100.0,
) {
  private var signs: List<RouteVariableSign> = emptyList()
  /** The mapped speed limit soon after each variable speed limit sign, by sign id. */
  private val mappedLimitAfter = mutableMapOf<String, Int?>()
  private val ended = mutableSetOf<String>()

  fun setSigns(signs: List<RouteVariableSign>) {
    this.signs = signs
  }

  fun reset() {
    signs = emptyList()
    mappedLimitAfter.clear()
    ended.clear()
  }

  /** The signs' state at [alongRouteMetres], given the mapped limit there ([mappedLimitKmh]). */
  fun update(alongRouteMetres: Double, mappedLimitKmh: Int?): VariableSignState {
    val warnings =
        signs
            .filter {
              it.sign.type == VariableSignType.WARNING &&
                  it.alongRouteMetres <= alongRouteMetres &&
                  alongRouteMetres - it.alongRouteMetres <= warningMetres
            }
            .mapNotNull { it.sign.warningCode }
            .distinct()
    val message =
        signs.firstOrNull {
          it.sign.screens.isNotEmpty() &&
              it.alongRouteMetres - alongRouteMetres in -messageBehindMetres..messageAheadMetres
        }
    return VariableSignState(speedLimitAt(alongRouteMetres, mappedLimitKmh), warnings, message?.sign)
  }

  private fun speedLimitAt(alongRouteMetres: Double, mappedLimitKmh: Int?): Int? {
    val passed = signs.filter { it.sign.type == VariableSignType.SPEED_LIMIT && it.alongRouteMetres <= alongRouteMetres }
    val last = passed.lastOrNull() ?: return null
    val past = alongRouteMetres - last.alongRouteMetres
    if (past > maxLimitMetres) return null
    // A gantry has a sign per lane; they normally agree, and the lowest is the safe one if not.
    val gantry = passed.filter { last.alongRouteMetres - it.alongRouteMetres <= GANTRY_METRES }
    val limit = gantry.mapNotNull { it.sign.speedLimitKmh }.minOrNull() ?: return null
    val id = last.sign.id
    if (id in ended) return null
    // Mapped limits change near the sign that starts a stretch, as map positions are approximate.
    if (past >= SETTLE_METRES) {
      if (id !in mappedLimitAfter) mappedLimitAfter[id] = mappedLimitKmh
      if (mappedLimitAfter[id] != mappedLimitKmh) {
        ended += id
        return null
      }
    }
    return limit
  }

  private companion object {
    const val GANTRY_METRES = 50.0
    const val SETTLE_METRES = 200.0
  }
}
