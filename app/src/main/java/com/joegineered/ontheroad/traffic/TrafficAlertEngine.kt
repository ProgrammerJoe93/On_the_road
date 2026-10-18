package com.joegineered.ontheroad.traffic

import com.joegineered.ontheroad.cameras.LatLng
import com.joegineered.ontheroad.cameras.MeasuredPolyline
import com.joegineered.ontheroad.cameras.PolylineIndex

/**
 * A traffic event on the route: where the route reaches it ([alongRouteMetres]) and leaves it
 * ([endAlongRouteMetres]), measured from the route's start.
 */
data class RouteTrafficEvent(
    val event: TrafficEvent,
    val alongRouteMetres: Double,
    val endAlongRouteMetres: Double = alongRouteMetres,
)

data class TrafficWarning(val event: TrafficEvent, val distanceMetres: Double)

data class TrafficAlertUpdate(val active: TrafficWarning?, val announce: TrafficWarning?)

/**
 * Finds traffic events on a route and decides when to warn about them: once, a couple of
 * kilometres ahead (closures earlier, so there is time to react).
 */
class TrafficAlertEngine(
    private val maxOffsetMetres: Double = 30.0,
    private val warningDistanceMetres: Double = 2_000.0,
    private val closureWarningDistanceMetres: Double = 5_000.0,
    /** Road weather sections have coarse, rounded coordinates, so they get more slack. */
    private val roadWeatherOffsetMetres: Double = 100.0,
    /** Road weather stations stand beside the road, often some way off its centre line. */
    private val weatherStationOffsetMetres: Double = 75.0,
) {
  private var events: List<RouteTrafficEvent> = emptyList()
  /**
   * Road weather stretches that continue one already warned about, and station hazards that repeat
   * one, so need no new warning.
   */
  private var continuations: Set<String> = emptySet()
  private val announced = mutableSetOf<String>()

  /** Events whose affected road runs along the route, ordered by where the route meets them. */
  fun match(route: List<LatLng>, candidates: List<TrafficEvent>): List<RouteTrafficEvent> {
    val line = MeasuredPolyline(route)
    val index = PolylineIndex(route)
    return candidates
        .mapNotNull { event ->
          val maxOffset =
              when {
                event.isRoadWeather -> roadWeatherOffsetMetres
                event.isWeatherStation -> weatherStationOffsetMetres
                else -> maxOffsetMetres
              }
          val onRoute =
              event.lines.flatten()
                  .filter { index.isNear(it, maxOffset) } // cheap check before projecting
                  .mapNotNull { point -> line.project(point)?.takeIf { it.offsetMetres <= maxOffset } }
                  .map { it.alongMetres }
          if (onRoute.isEmpty()) return@mapNotNull null
          val matched = RouteTrafficEvent(event, onRoute.min(), onRoute.max())
          // A route that only crosses a road with poor conditions doesn't drive along it.
          if (event.isRoadWeather && matched.endAlongRouteMetres - matched.alongRouteMetres < MIN_ROAD_WEATHER_METRES) {
            return@mapNotNull null
          }
          matched
        }
        .sortedBy { it.alongRouteMetres }
  }

  fun setEvents(events: List<RouteTrafficEvent>) {
    this.events = events
    // Road weather comes in back-to-back sections; warn when conditions start or get worse.
    val continuing = mutableSetOf<String>()
    var previous: RouteTrafficEvent? = null
    for (event in events.filter { it.event.isRoadWeather }) {
      val last = previous
      if (
          last != null &&
              event.alongRouteMetres <= last.endAlongRouteMetres + ROAD_WEATHER_GAP_METRES &&
              event.event.severity <= last.event.severity
      ) {
        continuing += event.event.id
      }
      if (last == null || event.endAlongRouteMetres > last.endAlongRouteMetres) previous = event
    }
    // In a cold spell many stations on a route measure a slippery road. Warn about the first, and
    // again only after a stretch without; a slippery forecast stretch has been warned about already.
    val slipperyForecasts = events.filter { it.event.isRoadWeather && it.event.slippery }
    events.filter { it.event.isWeatherStation }.groupBy { it.event.kind }.values.forEach { hazards ->
      var lastAlong: Double? = null
      for (hazard in hazards) {
        val along = hazard.alongRouteMetres
        if (
            lastAlong?.let { along - it <= STATION_REPEAT_METRES } == true ||
                (hazard.event.kind == TrafficEventKind.SLIPPERY_ROAD && slipperyForecasts.any { it.contains(along) })
        ) {
          continuing += hazard.event.id
        }
        lastAlong = along
      }
    }
    continuations = continuing
  }

  fun reset() {
    events = emptyList()
    continuations = emptySet()
    announced.clear()
  }

  /** The roadwork stretch the driver is currently in, if any (with a little slack at each end). */
  fun roadworksAt(alongRouteMetres: Double): TrafficEvent? =
      events.firstOrNull { it.event.kind == TrafficEventKind.ROADWORKS && it.contains(alongRouteMetres) }?.event

  /** The worst road weather where the driver is now, if conditions are poor. */
  fun roadWeatherAt(alongRouteMetres: Double): TrafficEvent? =
      events
          .filter { it.event.isRoadWeather && it.contains(alongRouteMetres) }
          .maxByOrNull { it.event.severity }
          ?.event

  fun update(alongRouteMetres: Double): TrafficAlertUpdate {
    val next =
        events.firstOrNull { it.alongRouteMetres >= alongRouteMetres && it.event.id !in continuations }
            ?: return TrafficAlertUpdate(null, null)
    val distance = next.alongRouteMetres - alongRouteMetres
    val window = if (next.event.isClosure) closureWarningDistanceMetres else warningDistanceMetres
    if (distance > window) return TrafficAlertUpdate(null, null)
    val warning = TrafficWarning(next.event, distance)
    val isNew = announced.add(next.event.id)
    return TrafficAlertUpdate(warning, if (isNew) warning else null)
  }

  private fun RouteTrafficEvent.contains(alongRouteMetres: Double) =
      alongRouteMetres in (this.alongRouteMetres - INSIDE_MARGIN_METRES)..(endAlongRouteMetres + INSIDE_MARGIN_METRES)

  private val TrafficEvent.severity: Int
    get() = if (kind == TrafficEventKind.VERY_POOR_CONDITIONS) 2 else 1

  private companion object {
    const val INSIDE_MARGIN_METRES = 50.0
    const val MIN_ROAD_WEATHER_METRES = 300.0
    const val ROAD_WEATHER_GAP_METRES = 1_000.0
    const val STATION_REPEAT_METRES = 15_000.0
  }
}
