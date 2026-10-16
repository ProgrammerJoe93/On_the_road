package com.joegineered.ontheroad.cameras

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sqrt

data class LatLng(val lat: Double, val lng: Double)

private const val EARTH_RADIUS_M = 6_371_008.8

private fun Double.toRadians() = this * PI / 180.0

private fun Double.toDegrees() = this * 180.0 / PI

/**
 * Projects points onto a local flat plane (metres) centred on [origin]. Accurate to well under a
 * metre over the few hundred metres these calculations span.
 */
internal class LocalProjection(private val origin: LatLng) {
  private val metresPerDegLat = EARTH_RADIUS_M * PI / 180.0
  private val metresPerDegLng = metresPerDegLat * cos(origin.lat.toRadians())

  fun x(p: LatLng) = (p.lng - origin.lng) * metresPerDegLng

  fun y(p: LatLng) = (p.lat - origin.lat) * metresPerDegLat
}

/** Equirectangular distance in metres; fine for the short distances used here. */
fun distanceMetres(a: LatLng, b: LatLng): Double {
  val projection = LocalProjection(a)
  val dx = projection.x(b)
  val dy = projection.y(b)
  return sqrt(dx * dx + dy * dy)
}

/** Initial bearing from [a] to [b] in degrees clockwise from north, in [0, 360). */
fun bearingDegrees(a: LatLng, b: LatLng): Double {
  val projection = LocalProjection(a)
  val bearing = atan2(projection.x(b), projection.y(b)).toDegrees()
  return (bearing + 360.0) % 360.0
}

/** Smallest absolute difference between two bearings, in [0, 180]. */
fun bearingDifference(a: Double, b: Double): Double {
  val diff = abs(a - b) % 360.0
  return if (diff > 180.0) 360.0 - diff else diff
}

/** Result of projecting a point onto a polyline. */
data class PolylineProjection(
    /** Distance from the point to the polyline, in metres. */
    val offsetMetres: Double,
    /** Distance along the polyline to the projected point, in metres. */
    val alongMetres: Double,
    /** Bearing of the polyline segment at the projected point. */
    val segmentBearing: Double,
    /** The projected point on the polyline. */
    val point: LatLng,
)

/** A polyline with precomputed cumulative distances, for repeated projections. */
class MeasuredPolyline(val points: List<LatLng>) {
  /** cumulative[i] is the distance along the line to points[i]. */
  val cumulative: DoubleArray =
      DoubleArray(points.size).also { acc ->
        for (i in 1 until points.size) {
          acc[i] = acc[i - 1] + distanceMetres(points[i - 1], points[i])
        }
      }

  val length: Double
    get() = cumulative.lastOrNull() ?: 0.0

  fun project(p: LatLng): PolylineProjection? {
    if (points.size < 2) return null
    val projection = LocalProjection(p)
    var best: PolylineProjection? = null
    for (i in 0 until points.size - 1) {
      val a = points[i]
      val b = points[i + 1]
      val ax = projection.x(a)
      val ay = projection.y(a)
      val bx = projection.x(b)
      val by = projection.y(b)
      val dx = bx - ax
      val dy = by - ay
      val lengthSquared = dx * dx + dy * dy
      // The point itself is the projection origin (0, 0).
      val t = if (lengthSquared == 0.0) 0.0 else ((-ax * dx - ay * dy) / lengthSquared).coerceIn(0.0, 1.0)
      val px = ax + t * dx
      val py = ay + t * dy
      val offset = sqrt(px * px + py * py)
      if (best == null || offset < best.offsetMetres) {
        val segmentLength = cumulative[i + 1] - cumulative[i]
        best =
            PolylineProjection(
                offsetMetres = offset,
                alongMetres = cumulative[i] + t * segmentLength,
                segmentBearing = bearingDegrees(a, b),
                point = LatLng(a.lat + t * (b.lat - a.lat), a.lng + t * (b.lng - a.lng)),
            )
      }
    }
    return best
  }
}

/** Douglas–Peucker simplification with a tolerance in metres. */
fun simplify(points: List<LatLng>, toleranceMetres: Double): List<LatLng> {
  if (points.size < 3) return points
  val keep = BooleanArray(points.size)
  keep[0] = true
  keep[points.size - 1] = true
  val stack = ArrayDeque<Pair<Int, Int>>()
  stack.addLast(0 to points.size - 1)
  while (stack.isNotEmpty()) {
    val (start, end) = stack.removeLast()
    if (end - start < 2) continue
    val segment = MeasuredPolyline(listOf(points[start], points[end]))
    var maxOffset = -1.0
    var maxIndex = -1
    for (i in start + 1 until end) {
      val offset = segment.project(points[i])?.offsetMetres ?: 0.0
      if (offset > maxOffset) {
        maxOffset = offset
        maxIndex = i
      }
    }
    if (maxOffset > toleranceMetres) {
      keep[maxIndex] = true
      stack.addLast(start to maxIndex)
      stack.addLast(maxIndex to end)
    }
  }
  return points.filterIndexed { index, _ -> keep[index] }
}
