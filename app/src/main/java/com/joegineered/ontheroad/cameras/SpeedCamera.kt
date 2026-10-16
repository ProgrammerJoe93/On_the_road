package com.joegineered.ontheroad.cameras

import kotlin.math.roundToInt

/** A speed camera from OpenStreetMap. */
data class SpeedCamera(
    /** OSM node id. */
    val id: Long,
    val position: LatLng,
    /**
     * Bearings (degrees) of the traffic direction being enforced, from the `direction` tag. Empty
     * when unknown or given relative to the way (`forward`/`backward`), which we cannot resolve.
     */
    val directions: List<Double> = emptyList(),
    /** Enforced speed limit in km/h, if tagged. */
    val maxSpeedKmh: Int? = null,
    /**
     * Start of the enforced section, from a `type=enforcement` relation's `from` member. Traffic
     * travels from here towards the camera.
     */
    val enforcementFrom: LatLng? = null,
)

/** A camera placed on the current route. */
data class MatchedCamera(
    val camera: SpeedCamera,
    /** Distance from the start of the route to the camera, in metres. */
    val alongRouteMetres: Double,
    /** Distance from the camera to the route line, in metres. */
    val offsetMetres: Double,
)

/** Parses the OSM `direction` tag into bearings; see https://wiki.openstreetmap.org/wiki/Key:direction */
fun parseDirectionTag(value: String?): List<Double> {
  if (value.isNullOrBlank()) return emptyList()
  return value.split(';').mapNotNull { part ->
    val token = part.trim().uppercase()
    CARDINALS[token] ?: token.toDoubleOrNull()?.let { ((it % 360.0) + 360.0) % 360.0 }
  }
}

/** Parses the OSM `maxspeed` tag into km/h. */
fun parseMaxSpeedKmh(value: String?): Int? {
  if (value.isNullOrBlank()) return null
  val trimmed = value.trim()
  return if (trimmed.endsWith("mph")) {
    trimmed.removeSuffix("mph").trim().toDoubleOrNull()?.let { (it * 1.609344).toInt() }
  } else {
    trimmed.toIntOrNull()
  }
}

/** A km/h limit in the display units; mph limits parsed by [parseMaxSpeedKmh] round-trip. */
fun displayedSpeedLimit(kmh: Int, metric: Boolean): Int =
    if (metric) kmh else (kmh / 1.609344).roundToInt()

private val CARDINALS =
    mapOf(
        "N" to 0.0, "NNE" to 22.5, "NE" to 45.0, "ENE" to 67.5,
        "E" to 90.0, "ESE" to 112.5, "SE" to 135.0, "SSE" to 157.5,
        "S" to 180.0, "SSW" to 202.5, "SW" to 225.0, "WSW" to 247.5,
        "W" to 270.0, "WNW" to 292.5, "NW" to 315.0, "NNW" to 337.5,
    )
