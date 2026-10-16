package com.joegineered.ontheroad.offline

import android.util.Log
import androidx.compose.runtime.snapshotFlow
import com.joegineered.ontheroad.cameras.LatLng
import com.joegineered.ontheroad.chargers.ChargerRepository
import com.joegineered.ontheroad.chargers.Tile
import com.joegineered.ontheroad.config.AppConfig
import com.joegineered.ontheroad.nav.TripStart
import com.joegineered.ontheroad.net.NetworkStatus
import java.io.IOException
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.hours
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import org.maplibre.compose.offline.DownloadProgress
import org.maplibre.compose.offline.DownloadStatus
import org.maplibre.compose.offline.OfflineManager
import org.maplibre.compose.offline.OfflinePack
import org.maplibre.compose.offline.OfflinePackDefinition
import org.maplibre.spatialk.geojson.BoundingBox
import org.maplibre.spatialk.geojson.MultiPolygon
import org.maplibre.spatialk.geojson.Position

/**
 * Map data saved for use without a connection: areas picked on the map, and the corridor along
 * recent routes. Each is downloaded in both map styles (which share their tiles), together with
 * the chargers in it.
 *
 * OpenFreeMap publishes new map data about weekly under a new tile URL. Once the map has loaded
 * the new release online, older downloads no longer match it offline, so outdated areas are
 * downloaded again on Wi-Fi, or when the driver taps Update.
 */
class OfflineMaps(
    private val manager: OfflineManager,
    private val httpClient: OkHttpClient,
    private val network: StateFlow<NetworkStatus>,
    private val chargers: ChargerRepository,
    private val saveRouteMaps: Flow<Boolean>,
    private val tripStarts: Flow<TripStart>,
    // MapLibre's offline manager is used from the main thread.
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main),
) {
  private val currentTiles = MutableStateFlow<String?>(null)

  private val _areas = MutableStateFlow<List<OfflineArea<OfflinePack>>>(emptyList())
  /** Saved areas, newest first. */
  val areas: StateFlow<List<OfflineArea<OfflinePack>>> = _areas.asStateFlow()

  // Main thread only.
  private val resumed = mutableSetOf<OfflinePack>()
  private val deleting = mutableSetOf<OfflinePack>()
  private val updating = mutableSetOf<String>()
  private var started = false

  fun start() {
    if (started) return
    started = true
    manager.setTileCountLimit(MAX_TILES_PER_PACK)
    scope.launch {
      // Recently viewed map stays available offline too.
      runCatching { manager.setMaximumAmbientCacheSize(AMBIENT_CACHE_BYTES) }
          .onFailure { Log.w(TAG, "Could not resize the map cache", it) }
    }
    scope.launch {
      combine(snapshotFlow { snapshots() }, currentTiles) { snapshots, tiles -> summarize(snapshots, tiles) }
          .collect { _areas.value = it }
    }
    scope.launch { combine(network, _areas) { status, areas -> status to areas }.collect { (s, a) -> maintain(s, a) } }
    scope.launch { checkTilesWhileOnline() }
    scope.launch {
      tripStarts.collect { trip ->
        if (saveRouteMaps.first()) {
          try {
            saveRoute(trip)
          } catch (e: CancellationException) {
            throw e
          } catch (e: Exception) {
            Log.w(TAG, "Could not save the route map", e)
          }
        }
      }
    }
  }

  /** Downloads the map and chargers in [bounds]. Check [isTooLarge] first. */
  fun downloadArea(name: String, bounds: Bounds) {
    scope.launch {
      val info =
          OfflineAreaInfo(
              id = UUID.randomUUID().toString(),
              name = name,
              kind = OfflineAreaKind.AREA,
              createdAtMillis = System.currentTimeMillis(),
              tiles = tileTemplateOrNull(),
              bounds = bounds,
          )
      val box = BoundingBox(bounds.west, bounds.south, bounds.east, bounds.north)
      AppConfig.MAP_STYLE_URLS.forEach { style ->
        create(OfflinePackDefinition.TilePyramid(style, box, 0, AppConfig.MAP_MAX_TILE_ZOOM), info)
      }
      prefetchChargers(listOf(bounds))
    }
  }

  fun delete(area: OfflineArea<OfflinePack>) {
    area.packs.filter { deleting.add(it) }.forEach { pack -> scope.launch { deletePack(pack) } }
  }

  fun retry(area: OfflineArea<OfflinePack>) {
    area.newest.forEach { manager.resume(it.pack) }
  }

  /** Downloads the area again with the server's current map data. */
  fun update(area: OfflineArea<OfflinePack>) {
    if (!updating.add(area.updateKey)) return
    scope.launch {
      val next = area.info.copy(generation = area.info.generation + 1, tiles = tileTemplateOrNull())
      area.newest.forEach { create(it.pack.definition, next) }
      area.info.bounds?.let { prefetchChargers(listOf(it)) }
    }
  }

  private val OfflineArea<OfflinePack>.updateKey
    get() = "${info.id}/${info.generation}"

  private fun snapshots(): List<PackSnapshot<OfflinePack>> =
      manager.packs.mapNotNull { pack ->
        val info = decode(pack.metadata) ?: return@mapNotNull null
        PackSnapshot(pack, info, pack.downloadProgress.toPackProgress())
      }

  /** Cleans up replaced downloads, resumes interrupted ones and starts automatic updates. */
  private fun maintain(status: NetworkStatus, areas: List<OfflineArea<OfflinePack>>) {
    areas.flatMap { obsoletePacks(it) }.filter { deleting.add(it) }.forEach { pack ->
      scope.launch { deletePack(pack) }
    }
    if (!status.online) {
      // MapLibre waits for the connection; resume again once it's back, in case it gave up.
      resumed.clear()
      return
    }
    // Downloads stop with the app; carry on where they left off.
    areas
        .flatMap { it.newest }
        .filter { it.progress.known && !it.progress.complete && it.pack !in deleting }
        .map { it.pack }
        .filter { resumed.add(it) }
        .forEach { manager.resume(it) }
    if (status.unmetered) areas.filter { it.outdated }.forEach { update(it) }
  }

  private suspend fun saveRoute(trip: TripStart) {
    val boxes = routeCorridor(trip.route.map { LatLng(it.lat, it.lng) })
    if (boxes.isEmpty()) return
    // Make room first: only the most recent routes are kept.
    excessRoutes(_areas.value, keep = MAX_SAVED_ROUTES - 1).forEach(::delete)
    val info =
        OfflineAreaInfo(
            id = UUID.randomUUID().toString(),
            name = trip.destinationName,
            kind = OfflineAreaKind.ROUTE,
            createdAtMillis = System.currentTimeMillis(),
            tiles = tileTemplateOrNull(),
        )
    val shape =
        MultiPolygon(
            boxes.map { box ->
              listOf(
                  listOf(
                      Position(box.west, box.south),
                      Position(box.east, box.south),
                      Position(box.east, box.north),
                      Position(box.west, box.north),
                      Position(box.west, box.south),
                  )
              )
            }
        )
    AppConfig.MAP_STYLE_URLS.forEach { style ->
      create(OfflinePackDefinition.Shape(style, shape, 0, AppConfig.MAP_MAX_TILE_ZOOM), info)
    }
    prefetchChargers(boxes)
  }

  private suspend fun create(definition: OfflinePackDefinition, info: OfflineAreaInfo) {
    val pack = manager.create(definition, json.encodeToString(OfflineAreaInfo.serializer(), info).encodeToByteArray())
    resumed += pack
    manager.resume(pack)
  }

  private suspend fun deletePack(pack: OfflinePack) {
    try {
      manager.delete(pack)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      Log.w(TAG, "Could not delete an offline pack", e)
      deleting -= pack
    }
  }

  /** Chargers are saved with the map; best-effort, as the public Overpass servers can be busy. */
  private suspend fun prefetchChargers(boxes: List<Bounds>) {
    val tiles =
        boxes
            .flatMap { Tile.covering(it.south, it.west, it.north, it.east, ChargerRepository.TILE_ZOOM) }
            .toSet()
    if (tiles.size > MAX_CHARGER_TILES) {
      Log.i(TAG, "Area too large to save chargers (${tiles.size} tiles)")
      return
    }
    try {
      withContext(Dispatchers.Default) { chargers.prefetch(tiles) }
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      Log.w(TAG, "Could not save chargers for an offline area", e)
    }
  }

  private suspend fun checkTilesWhileOnline() {
    network.map { it.online }.distinctUntilChanged().collectLatest { online ->
      if (!online) return@collectLatest
      while (true) {
        tileTemplateOrNull(refresh = true)
        delay(TILES_CHECK_INTERVAL)
      }
    }
  }

  private suspend fun tileTemplateOrNull(refresh: Boolean = false): String? {
    if (!refresh) currentTiles.value?.let { return it }
    return try {
      fetchTileTemplate().also { currentTiles.value = it }
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      Log.w(TAG, "Could not check the map data version", e)
      currentTiles.value
    }
  }

  private suspend fun fetchTileTemplate(): String =
      withContext(Dispatchers.IO) {
        val request = Request.Builder().url(AppConfig.MAP_TILES_URL).build()
        httpClient.newCall(request).execute().use { response ->
          if (!response.isSuccessful) throw IOException("TileJSON HTTP ${response.code}")
          parseTileTemplate(response.body.string()) ?: throw IOException("TileJSON without tiles")
        }
      }

  companion object {
    private const val TAG = "OfflineMaps"
    /** Picked areas are limited to this many tiles, about 300 km across in southern Finland. */
    const val MAX_AREA_TILES = 100_000L
    private const val MAX_TILES_PER_PACK = 250_000L
    private const val AMBIENT_CACHE_BYTES = 200L * 1024 * 1024
    private const val MAX_SAVED_ROUTES = 3
    private const val MAX_CHARGER_TILES = 1_000
    private val TILES_CHECK_INTERVAL = 6.hours

    private val json = Json { ignoreUnknownKeys = true }

    fun isTooLarge(bounds: Bounds): Boolean =
        tileCount(bounds, 0, AppConfig.MAP_MAX_TILE_ZOOM) > MAX_AREA_TILES

    fun decode(metadata: ByteArray?): OfflineAreaInfo? =
        metadata?.let {
          runCatching { json.decodeFromString(OfflineAreaInfo.serializer(), it.decodeToString()) }.getOrNull()
        }

    /** The first tile URL template in a TileJSON document. */
    fun parseTileTemplate(body: String): String? =
        (json.parseToJsonElement(body).jsonObject["tiles"] as? JsonArray)
            ?.firstOrNull()
            ?.jsonPrimitive
            ?.contentOrNull

    private fun DownloadProgress.toPackProgress(): PackProgress =
        when (this) {
          is DownloadProgress.Healthy ->
              PackProgress(
                  completedResources = completedResourceCount,
                  requiredResources = requiredResourceCount,
                  bytes = completedResourceBytes,
                  complete = status == DownloadStatus.Complete,
              )
          is DownloadProgress.Error -> PackProgress(error = message.ifBlank { reason })
          is DownloadProgress.TileLimitExceeded -> PackProgress(error = "More than $limit tiles")
          else -> PackProgress(known = false)
        }
  }
}
