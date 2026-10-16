package com.joegineered.ontheroad.chargers

import com.joegineered.ontheroad.cameras.LatLng
import com.joegineered.ontheroad.cameras.OverpassCache
import com.joegineered.ontheroad.cameras.distanceMetres
import android.util.Log
import java.util.Locale
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
  suspend fun nearest(position: LatLng, limit: Int = 20, fastOnly: Boolean = false): List<Pair<ChargingStation, Double>> {
    val anchor = LatLng(round(position.lat, 0.02), round(position.lng, 0.04))
    return parse(overpass.query(nearbyQuery(anchor)))
        .filter { !fastOnly || it.isFast }
        .map { it to distanceMetres(position, it.position) }
        .sortedBy { it.second }
        .take(limit)
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

    fun nearbyQuery(around: LatLng): String =
        String.format(
            Locale.ROOT,
            "[out:json][timeout:60];nwr[amenity=charging_station](around:%d,%.4f,%.4f);out center tags;",
            NEARBY_RADIUS_METRES, around.lat, around.lng,
        )

    fun query(tile: Tile): String =
        String.format(
            Locale.ROOT,
            "[out:json][timeout:60];nwr[amenity=charging_station](%.6f,%.6f,%.6f,%.6f);out center tags;",
            tile.south, tile.west, tile.north, tile.east,
        )

    private val json = Json { ignoreUnknownKeys = true }

    /** Parses an Overpass response (`out center tags`) into charging stations. */
    fun parse(body: String): List<ChargingStation> {
      val elements = json.parseToJsonElement(body).jsonObject["elements"] as? JsonArray ?: return emptyList()
      return elements.mapNotNull { element ->
        val obj = element as? JsonObject ?: return@mapNotNull null
        val type = obj["type"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
        val id = obj["id"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null
        // Nodes carry lat/lon; ways and relations a "center".
        val point = (obj["center"] as? JsonObject) ?: obj
        val lat = point["lat"]?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
        val lon = point["lon"]?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
        val tags =
            (obj["tags"] as? JsonObject)?.mapValues { it.value.jsonPrimitive.contentOrNull.orEmpty() }.orEmpty()
        chargingStationFromTags("$type/$id", LatLng(lat, lon), tags)
      }
    }
  }
}
