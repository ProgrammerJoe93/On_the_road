package com.joegineered.ontheroad.chargers

import com.joegineered.ontheroad.cameras.LatLng
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.sinh
import kotlin.math.tan

/** A Web Mercator map tile; chargers are fetched and cached per tile. */
data class Tile(val x: Int, val y: Int, val zoom: Int) {
  val west: Double get() = x / n * 360.0 - 180.0
  val east: Double get() = (x + 1) / n * 360.0 - 180.0
  val north: Double get() = latitudeOf(y)
  val south: Double get() = latitudeOf(y + 1)

  private val n: Double get() = (1 shl zoom).toDouble()

  fun contains(position: LatLng): Boolean =
      position.lat in south..north && position.lng in west..east

  private fun latitudeOf(tileY: Int): Double =
      atan(sinh(PI * (1 - 2 * tileY / n))) * 180.0 / PI

  companion object {
    fun containing(lat: Double, lng: Double, zoom: Int): Tile {
      val n = (1 shl zoom).toDouble()
      val x = floor((lng + 180.0) / 360.0 * n).toInt()
      val latRad = lat * PI / 180.0
      val y = floor((1 - ln(tan(latRad) + 1 / cos(latRad)) / PI) / 2 * n).toInt()
      return Tile(x.coerceIn(0, n.toInt() - 1), y.coerceIn(0, n.toInt() - 1), zoom)
    }

    /** All tiles overlapping a bounding box. */
    fun covering(south: Double, west: Double, north: Double, east: Double, zoom: Int): List<Tile> {
      val topLeft = containing(north, west, zoom)
      val bottomRight = containing(south, east, zoom)
      return (topLeft.x..bottomRight.x).flatMap { x ->
        (topLeft.y..bottomRight.y).map { y -> Tile(x, y, zoom) }
      }
    }
  }
}
