package com.joegineered.ontheroad.traffic

import com.joegineered.ontheroad.cameras.LatLng
import com.joegineered.ontheroad.cameras.MeasuredPolyline
import kotlin.math.cos

/**
 * Valhalla `exclude_polygons` that keep routes off closed roads: a small square on each closed
 * stretch (its start, middle and end), so any road crossing it is avoided.
 *
 * Valhalla limits exclusion polygons to 100 vertices in total (5 per square), so only closures
 * near the trip are sent: those close to the straight line from [from] to [to], nearest first.
 */
object ClosureAvoidance {
  private const val HALF_SIDE_METRES = 15.0
  private const val MAX_VERTICES = 100
  private const val VERTICES_PER_SQUARE = 5
  /** Closures farther than this from the direct line (or 30% of the trip, if longer) are ignored. */
  private const val MIN_CORRIDOR_METRES = 20_000.0

  /** Polygons as `[[lon, lat], ...]` rings, ready for Valhalla's JSON. */
  fun excludePolygons(
      closures: List<TrafficEvent>,
      from: LatLng? = null,
      to: LatLng? = null,
  ): List<List<List<Double>>> {
    val points = closures.filter { it.isClosure }.flatMap { event -> event.lines.flatMap(::keyPoints) }
    val nearby =
        if (from == null || to == null) points
        else {
          val trip = MeasuredPolyline(listOf(from, to))
          val corridor = maxOf(MIN_CORRIDOR_METRES, trip.length * 0.3)
          points
              .map { it to (trip.project(it)?.offsetMetres ?: Double.MAX_VALUE) }
              .filter { it.second <= corridor }
              .sortedBy { it.second }
              .map { it.first }
        }
    return nearby.take(MAX_VERTICES / VERTICES_PER_SQUARE).map(::square)
  }

  /** Start, middle and end of a line; a point event is its own key point. */
  private fun keyPoints(line: List<LatLng>): List<LatLng> {
    if (line.size < 2) return line
    val measured = MeasuredPolyline(line)
    val middle = pointAt(measured, measured.length / 2) ?: line[line.size / 2]
    return listOf(line.first(), middle, line.last()).distinct()
  }

  private fun pointAt(line: MeasuredPolyline, along: Double): LatLng? {
    val i = line.cumulative.indexOfFirst { it >= along }.takeIf { it > 0 } ?: return null
    val a = line.points[i - 1]
    val b = line.points[i]
    val segment = line.cumulative[i] - line.cumulative[i - 1]
    val t = if (segment == 0.0) 0.0 else (along - line.cumulative[i - 1]) / segment
    return LatLng(a.lat + t * (b.lat - a.lat), a.lng + t * (b.lng - a.lng))
  }

  private fun square(centre: LatLng): List<List<Double>> {
    val dLat = HALF_SIDE_METRES / 111_195.0
    val dLng = dLat / cos(Math.toRadians(centre.lat))
    val ring =
        listOf(
            listOf(centre.lng - dLng, centre.lat - dLat),
            listOf(centre.lng + dLng, centre.lat - dLat),
            listOf(centre.lng + dLng, centre.lat + dLat),
            listOf(centre.lng - dLng, centre.lat + dLat),
        )
    return ring + listOf(ring.first())
  }
}
