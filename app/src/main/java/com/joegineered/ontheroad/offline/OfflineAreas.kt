package com.joegineered.ontheroad.offline

import com.joegineered.ontheroad.cameras.LatLng
import com.joegineered.ontheroad.cameras.distanceMetres
import com.joegineered.ontheroad.cameras.simplify
import com.joegineered.ontheroad.chargers.Tile
import kotlin.math.cos
import kotlinx.serialization.Serializable

/** A latitude/longitude box. */
@Serializable
data class Bounds(val south: Double, val west: Double, val north: Double, val east: Double) {
  fun expandedBy(metres: Double): Bounds {
    val dLat = metres / METRES_PER_DEGREE_LAT
    val dLng = dLat / cos(Math.toRadians((south + north) / 2))
    return Bounds(south - dLat, west - dLng, north + dLat, east + dLng)
  }

  companion object {
    private const val METRES_PER_DEGREE_LAT = 111_195.0

    fun around(points: Collection<LatLng>): Bounds =
        Bounds(
            south = points.minOf { it.lat },
            west = points.minOf { it.lng },
            north = points.maxOf { it.lat },
            east = points.maxOf { it.lng },
        )
  }
}

enum class OfflineAreaKind {
  /** An area the driver picked on the map. */
  AREA,
  /** The corridor along a route, saved when navigation started. */
  ROUTE,
}

/** Stored as each offline pack's metadata. An area has one pack per map style (day and night). */
@Serializable
data class OfflineAreaInfo(
    val id: String,
    val name: String,
    val kind: OfflineAreaKind,
    val createdAtMillis: Long,
    /**
     * Download generation. An update downloads the next generation and deletes the old one only
     * once the new one is complete, so the area stays usable offline meanwhile.
     */
    val generation: Int = 1,
    /**
     * The vector tile URL template at download time. OpenFreeMap puts the data release in it, so a
     * different template means the map data has moved on and this download no longer matches.
     */
    val tiles: String? = null,
    /** The area picked on the map, for refreshing its chargers on update. */
    val bounds: Bounds? = null,
)

/** Download progress of one pack, independent of MapLibre's types. */
data class PackProgress(
    val completedResources: Long = 0,
    val requiredResources: Long = 0,
    val bytes: Long = 0,
    val complete: Boolean = false,
    val error: String? = null,
    /** False until MapLibre has reported the pack's status. */
    val known: Boolean = true,
)

data class PackSnapshot<P>(val pack: P, val info: OfflineAreaInfo, val progress: PackProgress)

sealed interface OfflineAreaState {
  /** [fraction] is null until the size of the download is known. */
  data class Downloading(val fraction: Float?) : OfflineAreaState

  /** A newer generation is downloading; the previous one is still usable. */
  data class Updating(val fraction: Float?) : OfflineAreaState

  data object Ready : OfflineAreaState

  data class Failed(val message: String) : OfflineAreaState
}

data class OfflineArea<P>(
    /** The newest generation's info. */
    val info: OfflineAreaInfo,
    val state: OfflineAreaState,
    /** Approximate storage used. Day and night packs share their tiles, so it's the larger pack. */
    val sizeBytes: Long,
    /** Downloaded with older map data than the server now has; only shown for complete areas. */
    val outdated: Boolean,
    /** The newest generation's packs. */
    val newest: List<PackSnapshot<P>>,
    /** Packs of every generation. */
    val packs: List<P>,
)

/** Groups packs into areas, newest first. [currentTiles] is the server's tile template, if known. */
fun <P> summarize(snapshots: List<PackSnapshot<P>>, currentTiles: String?): List<OfflineArea<P>> =
    snapshots
        .groupBy { it.info.id }
        .values
        .map { group ->
          val newestGeneration = group.maxOf { it.info.generation }
          val newest = group.filter { it.info.generation == newestGeneration }
          val info = newest.first().info
          val complete = newest.all { it.progress.complete }
          val olderComplete =
              group.filter { it.info.generation < newestGeneration }.let { older ->
                older.isNotEmpty() && older.all { it.progress.complete }
              }
          val fraction = fraction(newest.map { it.progress })
          val error = newest.firstNotNullOfOrNull { it.progress.error }
          val state =
              when {
                complete -> OfflineAreaState.Ready
                olderComplete -> OfflineAreaState.Updating(fraction)
                error != null -> OfflineAreaState.Failed(error)
                else -> OfflineAreaState.Downloading(fraction)
              }
          OfflineArea(
              info = info,
              state = state,
              sizeBytes = newest.maxOf { it.progress.bytes },
              outdated =
                  complete && currentTiles != null && info.tiles != null && info.tiles != currentTiles,
              newest = newest,
              packs = group.map { it.pack },
          )
        }
        .sortedByDescending { it.info.createdAtMillis }

/** Packs of older generations whose replacement has finished downloading. */
fun <P> obsoletePacks(area: OfflineArea<P>): List<P> {
  if (area.newest.any { !it.progress.complete }) return emptyList()
  val newestPacks = area.newest.map { it.pack }.toSet()
  return area.packs.filter { it !in newestPacks }
}

/** Route areas beyond the [keep] most recent, to delete. */
fun <P> excessRoutes(areas: List<OfflineArea<P>>, keep: Int): List<OfflineArea<P>> =
    areas
        .filter { it.info.kind == OfflineAreaKind.ROUTE }
        .sortedByDescending { it.info.createdAtMillis }
        .drop(keep)

private fun fraction(progress: List<PackProgress>): Float? {
  val required = progress.sumOf { it.requiredResources }
  if (required <= 0 || progress.any { it.requiredResources <= 0 }) return null
  return (progress.sumOf { it.completedResources }.toFloat() / required).coerceIn(0f, 1f)
}

/** Number of map tiles covering [bounds] at zoom levels [minZoom] to [maxZoom]. */
fun tileCount(bounds: Bounds, minZoom: Int, maxZoom: Int): Long =
    (minZoom..maxZoom).sumOf { zoom ->
      val topLeft = Tile.containing(bounds.north, bounds.west, zoom)
      val bottomRight = Tile.containing(bounds.south, bounds.east, zoom)
      (bottomRight.x - topLeft.x + 1).toLong() * (bottomRight.y - topLeft.y + 1)
    }

/**
 * Boxes along [route], each covering up to [stepMetres] of it plus [marginMetres] on every side.
 * Together they make the corridor saved for a route, wide enough for small detours.
 */
fun routeCorridor(
    route: List<LatLng>,
    stepMetres: Double = 5_000.0,
    marginMetres: Double = 1_500.0,
): List<Bounds> {
  if (route.isEmpty()) return emptyList()
  // Long straight segments are split finely, so a box spans little more than one step.
  val points = densify(simplify(route, toleranceMetres = 50.0), stepMetres / 4)
  val boxes = mutableListOf<Bounds>()
  var chunk = mutableListOf(points.first())
  var chunkLength = 0.0
  for (i in 1 until points.size) {
    chunkLength += distanceMetres(points[i - 1], points[i])
    chunk += points[i]
    if (chunkLength >= stepMetres) {
      boxes += Bounds.around(chunk).expandedBy(marginMetres)
      chunk = mutableListOf(points[i])
      chunkLength = 0.0
    }
  }
  if (chunk.size > 1 || boxes.isEmpty()) boxes += Bounds.around(chunk).expandedBy(marginMetres)
  return boxes
}

/** Adds points so that no segment is longer than [maxSegmentMetres]. */
private fun densify(points: List<LatLng>, maxSegmentMetres: Double): List<LatLng> {
  if (points.size < 2) return points
  val result = mutableListOf(points.first())
  for (i in 1 until points.size) {
    val a = points[i - 1]
    val b = points[i]
    val pieces = kotlin.math.ceil(distanceMetres(a, b) / maxSegmentMetres).toInt().coerceAtLeast(1)
    for (k in 1..pieces) {
      val t = k.toDouble() / pieces
      result += LatLng(a.lat + t * (b.lat - a.lat), a.lng + t * (b.lng - a.lng))
    }
  }
  return result
}
