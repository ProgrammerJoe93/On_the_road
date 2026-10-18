package com.joegineered.ontheroad.traffic

import com.joegineered.ontheroad.cameras.LatLng
import java.time.Instant

enum class TrafficEventKind {
  ACCIDENT,
  ROAD_CLOSED,
  CONGESTION,
  ROADWORKS,
  /** Fintraffic's road weather: poor driving conditions ("huono ajokeli"). */
  POOR_CONDITIONS,
  /** Very poor driving conditions ("erittäin huono ajokeli"). */
  VERY_POOR_CONDITIONS,
  /** Measured at a road weather station: ice, snow, slush, frost or freezing rain on the road. */
  SLIPPERY_ROAD,
  /** Measured at a road weather station: fog or heavy snowfall. */
  POOR_VISIBILITY,
  OTHER,
}

/**
 * A traffic announcement, roadwork, stretch of poor road weather or road weather station reading
 * from Fintraffic's Digitraffic service. Texts are Finnish, as published; [kind] and [restrictions] are in English for the UI
 * and voice.
 */
data class TrafficEvent(
    val id: String,
    val kind: TrafficEventKind,
    /** Finnish title, e.g. "Tie 13, Perho. Ensitiedote liikenneonnettomuudesta." */
    val title: String,
    /** Finnish location description. */
    val description: String?,
    /** Affected road as lines, or a single point. */
    val lines: List<List<LatLng>>,
    /** English summaries such as "Single lane closed" or "Speed limit 50 km/h". */
    val restrictions: List<String>,
    /** When it started; for road weather stations, when it was measured. */
    val start: Instant?,
    val end: Instant?,
    /** Road weather only: the road is icy, snowy, slushy or frosty. */
    val slippery: Boolean = false,
) {
  val isClosure: Boolean
    get() = kind == TrafficEventKind.ROAD_CLOSED

  val isRoadWeather: Boolean
    get() = kind == TrafficEventKind.POOR_CONDITIONS || kind == TrafficEventKind.VERY_POOR_CONDITIONS

  /** A hazard measured at a road weather station, rather than forecast or reported. */
  val isWeatherStation: Boolean
    get() = kind == TrafficEventKind.SLIPPERY_ROAD || kind == TrafficEventKind.POOR_VISIBILITY

  /** A point to place the icon: the middle of the affected stretch. */
  val anchor: LatLng?
    get() {
      val points = lines.flatten()
      return points.getOrNull(points.size / 2)
    }

  fun isActive(now: Instant): Boolean =
      (start == null || !start.isAfter(now)) && (end == null || end.isAfter(now))
}

/** A traffic measuring station where traffic currently flows well below its free-flow speed. */
data class SlowTraffic(
    val stationId: Int,
    val position: LatLng,
    /** Average speed as a percentage of free-flow speed, per road direction (1 and 2). */
    val direction1Percent: Double?,
    val direction2Percent: Double?,
    val direction1Kmh: Double?,
    val direction2Kmh: Double?,
) {
  val worstPercent: Double?
    get() = listOfNotNull(direction1Percent, direction2Percent).minOrNull()
}
