package com.joegineered.ontheroad.stops

import com.joegineered.ontheroad.cameras.LatLng
import com.joegineered.ontheroad.cameras.MeasuredPolyline
import com.joegineered.ontheroad.chargers.ChargingStation
import com.joegineered.ontheroad.chargers.chargingStationFromTags
import java.util.Locale
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Kinds of place to stop at on the way. [maxOffRouteMetres] is how far from the route a place may
 * be: further for fuel and chargers, which are worth a short detour, than for food and rest areas,
 * which are common.
 */
enum class StopCategory(val osmFilter: String, val maxOffRouteMetres: Int) {
  FUEL("[amenity=fuel]", 500),
  CHARGING("[amenity=charging_station]", 500),
  FOOD("[amenity~\"^(restaurant|fast_food|cafe)$\"]", 300),
  REST("[highway~\"^(rest_area|services)$\"]", 300),
}

/** A place to stop at. [charger] has the plug details when it is an EV charger. */
data class RoadsideStop(
    /** OSM element, e.g. "node/123". */
    val id: String,
    val category: StopCategory,
    val position: LatLng,
    /** Its name, or who runs it (e.g. a fuel brand); null when OSM has neither. */
    val name: String?,
    val charger: ChargingStation? = null,
)

/** A stop ahead on the route: [aheadMetres] along the route, [offRouteMetres] to the side. */
data class StopAhead(val stop: RoadsideStop, val aheadMetres: Double, val offRouteMetres: Double)

object RoadsideStops {
  private val json = Json { ignoreUnknownKeys = true }

  /** Places of [category] within its [StopCategory.maxOffRouteMetres] of [polyline]. */
  fun alongQuery(category: StopCategory, polyline: List<LatLng>): String {
    val around =
        "around:${category.maxOffRouteMetres}," +
            polyline.joinToString(",") { String.format(Locale.ROOT, "%.5f,%.5f", it.lat, it.lng) }
    return "[out:json][timeout:60];nwr${category.osmFilter}($around);out center tags;"
  }

  /** Parses an [alongQuery] response. Private places (`access=private/no`) are left out. */
  fun parse(body: String, category: StopCategory): List<RoadsideStop> {
    val elements = json.parseToJsonElement(body).jsonObject["elements"] as? JsonArray ?: return emptyList()
    return elements
        .filterIsInstance<JsonObject>()
        .mapNotNull { element ->
          val type = element["type"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
          val id = element["id"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null
          // Nodes carry lat/lon; ways and relations a "center".
          val point = element["center"] as? JsonObject ?: element
          val lat = point["lat"]?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
          val lon = point["lon"]?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
          val tags =
              (element["tags"] as? JsonObject)
                  ?.mapValues { it.value.jsonPrimitive.contentOrNull.orEmpty() }
                  .orEmpty()
          if (tags["access"] in setOf("private", "no")) return@mapNotNull null
          val position = LatLng(lat, lon)
          val charger =
              if (category == StopCategory.CHARGING) {
                chargingStationFromTags("$type/$id", position, tags) ?: return@mapNotNull null
              } else {
                null
              }
          RoadsideStop(
              id = "$type/$id",
              category = category,
              position = position,
              name =
                  charger?.title
                      ?: (tags["name"] ?: tags["brand"] ?: tags["operator"])?.takeIf { it.isNotBlank() },
              charger = charger,
          )
        }
        .distinctBy { it.id }
  }

  /**
   * The [stops] ahead of [alongMetres] on [route], nearest first: the [limit] closest within each
   * stop's maximum distance from the route. A stop just passed (within [behindMetres]) still
   * counts, as the driver may be pulling in.
   */
  fun ahead(
      route: MeasuredPolyline,
      alongMetres: Double,
      stops: List<RoadsideStop>,
      limit: Int = 30,
      behindMetres: Double = 100.0,
  ): List<StopAhead> =
      stops
          .mapNotNull { stop ->
            val projection = route.project(stop.position) ?: return@mapNotNull null
            val ahead = projection.alongMetres - alongMetres
            if (ahead < -behindMetres || projection.offsetMetres > stop.category.maxOffRouteMetres) null
            else StopAhead(stop, ahead.coerceAtLeast(0.0), projection.offsetMetres)
          }
          .sortedBy { it.aheadMetres }
          .take(limit)

  /**
   * The parts of [chunks] (consecutive pieces of a route, [lengths] long) that overlap the
   * stretch from [fromMetres] to [toMetres] along it: only those are searched, so a long route
   * doesn't need one huge query, while each chunk's query stays the same as the car moves on and
   * is answered from the cache.
   */
  fun <T> chunksBetween(chunks: List<T>, lengths: List<Double>, fromMetres: Double, toMetres: Double): List<T> {
    var start = 0.0
    return chunks.filterIndexed { i, _ ->
      val end = start + lengths[i]
      (end >= fromMetres && start <= toMetres).also { start = end }
    }
  }
}
