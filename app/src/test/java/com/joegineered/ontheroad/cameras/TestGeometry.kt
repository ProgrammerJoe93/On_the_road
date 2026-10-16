package com.joegineered.ontheroad.cameras

import kotlin.math.PI
import kotlin.math.cos

/** Helpers for building test geometry around Helsinki (60.16 N), in metres. */
internal object TestGeometry {
  private const val METRES_PER_DEG_LAT = 111_195.0
  val origin = LatLng(60.1600, 24.9400)

  /** A point [north] metres north and [east] metres east of [origin]. */
  fun at(north: Double, east: Double = 0.0): LatLng =
      LatLng(
          origin.lat + north / METRES_PER_DEG_LAT,
          origin.lng + east / (METRES_PER_DEG_LAT * cos(origin.lat * PI / 180.0)),
      )

  /** A straight route heading north for [length] metres, with a point every 100 m. */
  fun northboundRoute(length: Int = 2000): List<LatLng> = (0..length step 100).map { at(it.toDouble()) }

  fun camera(id: Long, position: LatLng, vararg directions: Double, from: LatLng? = null) =
      SpeedCamera(id = id, position = position, directions = directions.toList(), enforcementFrom = from)
}
