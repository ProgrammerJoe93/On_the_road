package com.joegineered.ontheroad.traffic

import com.joegineered.ontheroad.cameras.LatLng
import com.joegineered.ontheroad.cameras.MeasuredPolyline

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
) {
  private var events: List<RouteTrafficEvent> = emptyList()
  private val announced = mutableSetOf<String>()

  /** Events whose affected road runs along the route, ordered by where the route meets them. */
  fun match(route: List<LatLng>, candidates: List<TrafficEvent>): List<RouteTrafficEvent> {
    val line = MeasuredPolyline(route)
    return candidates
        .mapNotNull { event ->
          val onRoute =
              event.lines.flatten()
                  .mapNotNull { point -> line.project(point)?.takeIf { it.offsetMetres <= maxOffsetMetres } }
                  .map { it.alongMetres }
          if (onRoute.isEmpty()) return@mapNotNull null
          RouteTrafficEvent(event, onRoute.min(), onRoute.max())
        }
        .sortedBy { it.alongRouteMetres }
  }

  fun setEvents(events: List<RouteTrafficEvent>) {
    this.events = events
  }

  fun reset() {
    events = emptyList()
    announced.clear()
  }

  /** The roadwork stretch the driver is currently in, if any (with a little slack at each end). */
  fun roadworksAt(alongRouteMetres: Double): TrafficEvent? =
      events
          .firstOrNull {
            it.event.kind == TrafficEventKind.ROADWORKS &&
                alongRouteMetres in
                    (it.alongRouteMetres - INSIDE_MARGIN_METRES)..(it.endAlongRouteMetres + INSIDE_MARGIN_METRES)
          }
          ?.event

  fun update(alongRouteMetres: Double): TrafficAlertUpdate {
    val next =
        events.firstOrNull { it.alongRouteMetres >= alongRouteMetres }
            ?: return TrafficAlertUpdate(null, null)
    val distance = next.alongRouteMetres - alongRouteMetres
    val window = if (next.event.isClosure) closureWarningDistanceMetres else warningDistanceMetres
    if (distance > window) return TrafficAlertUpdate(null, null)
    val warning = TrafficWarning(next.event, distance)
    val isNew = announced.add(next.event.id)
    return TrafficAlertUpdate(warning, if (isNew) warning else null)
  }

  private companion object {
    const val INSIDE_MARGIN_METRES = 50.0
  }
}
