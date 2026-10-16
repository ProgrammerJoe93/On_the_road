package com.joegineered.ontheroad.chargers

import com.joegineered.ontheroad.cameras.LatLng
import com.joegineered.ontheroad.cameras.OverpassCache
import com.joegineered.ontheroad.cameras.distanceMetres
import com.joegineered.ontheroad.offline.Bounds
import android.util.Log
import java.util.Locale
import kotlin.math.cos
import kotlin.coroutines.cancellation.CancellationException
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * EV chargers from OpenStreetMap, fetched per map tile (zoom 10, about 20 km across in Finland)
 * through the shared Overpass disk cache, and kept in memory for the session.
 */
class ChargerRepository(private val overpass: OverpassCache) {
  private val tiles = ConcurrentHashMap<Tile, List<ChargingStation>>()
  private val tileLocks = ConcurrentHashMap<Tile, Mutex>()

  /** Chargers in the bounding box, or null when it spans too many tiles (zoomed too far out). */
  suspend fun inBounds(south: Double, west: Double, north: Double, east: Double): List<ChargingStation>? {
    val covering = Tile.covering(south, west, north, east, TILE_ZOOM)
    if (covering.size > MAX_TILES) return null
    return load(covering).filter {
      it.position.lat in south..north && it.position.lng in west..east
    }
  }

  /**
   * The nearest chargers within [NEARBY_RADIUS_METRES] of [position], with one Overpass query. The
   * position is rounded to about 2 km so the cached answer is reused while moving around.
   */
  suspend fun nearest(
      position: LatLng,
      limit: Int = 20,
      fastOnly: Boolean = false,
      plugTypes: Set<SocketType> = SocketType.entries.toSet(),
  ): List<Pair<ChargingStation, Double>> {
    val anchor = LatLng(round(position.lat, 0.02), round(position.lng, 0.04))
    val stations =
        try {
          parse(overpass.query(nearbyQuery(anchor)))
        } catch (e: CancellationException) {
          throw e
        } catch (e: Exception) {
          // Offline or the server is busy: use the map's charger tiles, which are also saved with
          // offline areas.
          Log.w(TAG, "Nearby charger query failed; trying cached tiles", e)
          val bounds = nearbyBounds(position)
          load(Tile.covering(bounds.south, bounds.west, bounds.north, bounds.east, TILE_ZOOM))
        }
    return stations
        .filter { (!fastOnly || it.isFast) && it.matches(plugTypes) }
        .map { it to distanceMetres(position, it.position) }
        .filter { it.second <= NEARBY_RADIUS_METRES }
        .sortedBy { it.second }
        .take(limit)
  }

  /**
   * Downloads the chargers in [tiles] with a few large queries and caches them per tile, so the
   * map and charger lists work offline in a downloaded area.
   */
  suspend fun prefetch(tiles: Collection<Tile>) {
    tiles.distinct().chunked(PREFETCH_TILES_PER_QUERY).forEach { batch ->
      val elements = elements(overpass.query(unionQuery(batch)))
      batch.forEach { tile ->
        val inTile = elements.filter { element -> position(element)?.let { tile.contains(it) } == true }
        overpass.store(query(tile), JsonObject(mapOf("elements" to JsonArray(inTile))).toString())
        this.tiles.remove(tile)
      }
    }
  }

  /** Loads tiles, keeping whatever succeeded when some fail (e.g. a busy Overpass server). */
  private suspend fun load(tiles: List<Tile>): List<ChargingStation> = coroutineScope {
    val results = tiles.map { tile -> async { runCatching { tile(tile) } } }.map { it.await() }
    results.forEach { result ->
      result.exceptionOrNull()?.let { if (it is CancellationException) throw it else Log.w(TAG, "Charger tile failed", it) }
    }
    val loaded = results.mapNotNull { it.getOrNull() }
    if (loaded.isEmpty() && results.isNotEmpty()) throw results.first().exceptionOrNull()!!
    loaded.flatten().distinctBy { it.id }
  }

  private fun round(value: Double, step: Double) = Math.round(value / step) * step

  private suspend fun tile(tile: Tile): List<ChargingStation> {
    tiles[tile]?.let { return it }
    // One request per tile, even when several screens ask at once.
    return tileLocks.getOrPut(tile) { Mutex() }.withLock {
      tiles[tile] ?: parse(overpass.query(query(tile))).also { tiles[tile] = it }
    }
  }

  companion object {
    private const val TAG = "ChargerRepository"
    const val TILE_ZOOM = 10
    private const val MAX_TILES = 6
    const val NEARBY_RADIUS_METRES = 15_000
    /** Tiles per prefetch query; 100 tiles is about 200 km by 200 km in southern Finland. */
    private const val PREFETCH_TILES_PER_QUERY = 100

    /**
     * A bounding box around [around] that contains the [NEARBY_RADIUS_METRES] circle. Overpass
     * evaluates a bounding box far more cheaply than an `around` filter, which matters on the
     * rate-limited public servers; results are trimmed to the circle afterwards.
     */
    fun nearbyQuery(around: LatLng): String {
      val box = nearbyBounds(around)
      return String.format(
          Locale.ROOT,
          "[out:json][timeout:60];nwr[amenity=charging_station](%.4f,%.4f,%.4f,%.4f);out center tags;",
          box.south, box.west, box.north, box.east,
      )
    }

    private fun nearbyBounds(around: LatLng): Bounds {
      val dLat = NEARBY_RADIUS_METRES / 111_195.0
      val dLng = dLat / cos(Math.toRadians(around.lat))
      return Bounds(around.lat - dLat, around.lng - dLng, around.lat + dLat, around.lng + dLng)
    }

    /**
     * One query for many tiles. Neighbouring tiles in a row are merged into one box, so a
     * rectangular area costs one box per row.
     */
    fun unionQuery(tiles: Collection<Tile>): String {
      val boxes =
          tiles.groupBy { it.zoom to it.y }.flatMap { (key, row) ->
            val (zoom, y) = key
            runs(row.map { it.x }.distinct().sorted()).map { (first, last) ->
              val west = Tile(first, y, zoom)
              val east = Tile(last, y, zoom)
              String.format(
                  Locale.ROOT,
                  "nwr[amenity=charging_station](%.6f,%.6f,%.6f,%.6f);",
                  west.south, west.west, west.north, east.east,
              )
            }
          }
      return "[out:json][timeout:120];(${boxes.joinToString("")});out center tags;"
    }

    /** Consecutive runs in sorted [values], as first and last value. */
    private fun runs(values: List<Int>): List<Pair<Int, Int>> {
      val runs = mutableListOf<Pair<Int, Int>>()
      for (value in values) {
        val last = runs.lastOrNull()
        if (last != null && last.second == value - 1) runs[runs.lastIndex] = last.first to value
        else runs += value to value
      }
      return runs
    }

    fun query(tile: Tile): String =
        String.format(
            Locale.ROOT,
            "[out:json][timeout:60];nwr[amenity=charging_station](%.6f,%.6f,%.6f,%.6f);out center tags;",
            tile.south, tile.west, tile.north, tile.east,
        )

    private val json = Json { ignoreUnknownKeys = true }

    /** Parses an Overpass response (`out center tags`) into charging stations. */
    fun parse(body: String): List<ChargingStation> =
        elements(body).mapNotNull { obj ->
          val type = obj["type"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
          val id = obj["id"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null
          val position = position(obj) ?: return@mapNotNull null
          val tags =
              (obj["tags"] as? JsonObject)?.mapValues { it.value.jsonPrimitive.contentOrNull.orEmpty() }.orEmpty()
          chargingStationFromTags("$type/$id", position, tags)
        }

    private fun elements(body: String): List<JsonObject> =
        (json.parseToJsonElement(body).jsonObject["elements"] as? JsonArray)?.filterIsInstance<JsonObject>().orEmpty()

    /** Nodes carry lat/lon; ways and relations a "center". */
    private fun position(element: JsonObject): LatLng? {
      val point = (element["center"] as? JsonObject) ?: element
      val lat = point["lat"]?.jsonPrimitive?.doubleOrNull ?: return null
      val lon = point["lon"]?.jsonPrimitive?.doubleOrNull ?: return null
      return LatLng(lat, lon)
    }
  }
}
