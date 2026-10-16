package com.joegineered.ontheroad.cameras

import android.util.Log

/**
 * Fetches the speed cameras that apply to a route. Overpass responses are cached on disk so that
 * repeated trips, and reroutes over the same roads, don't query the public server again.
 */
class SpeedCameraRepository(
    private val overpass: OverpassCache,
    private val matcher: RouteCameraMatcher = RouteCameraMatcher(),
) {

  /**
   * @param onPreliminary receives cameras matched by distance and direction only, as soon as they
   *   are known. The nearby-road check that follows needs a second, often slow, Overpass query, so
   *   warnings can start before it finishes.
   */
  suspend fun camerasForRoute(
      route: List<LatLng>,
      maneuverPoints: List<LatLng>,
      onPreliminary: (List<MatchedCamera>) -> Unit = {},
  ): List<MatchedCamera> {
    if (route.size < 2) return emptyList()

    // The query radius (50 m) comfortably covers the simplification error (20 m).
    val simplified = simplify(route, toleranceMetres = 20.0)
    val cameras =
        simplified
            .chunkedOverlapping(MAX_POINTS_PER_QUERY)
            .flatMap { chunk ->
              OverpassClient.parseCameras(cached(OverpassClient.camerasAlongQuery(chunk)))
            }
            .distinctBy { it.id }

    // Only fetch nearby roads around cameras that could plausibly be on the route.
    val candidates = matcher.match(route, cameras, maneuverPoints)
    if (candidates.isEmpty()) return emptyList()
    onPreliminary(candidates)

    val roads =
        try {
          candidates
              .map { it.camera.position }
              .chunked(MAX_POINTS_PER_QUERY)
              .flatMap { OverpassClient.parseRoads(cached(OverpassClient.roadsNearQuery(it))) }
        } catch (e: Exception) {
          // Without nearby roads we can still warn; we just can't rule out neighbouring roads.
          Log.w(TAG, "Nearby road lookup failed; using distance-only matching", e)
          emptyList()
        }

    return matcher.match(route, candidates.map { it.camera }, maneuverPoints, roads)
  }

  private suspend fun cached(query: String): String = overpass.query(query)

  private companion object {
    const val TAG = "SpeedCameraRepository"
    const val MAX_POINTS_PER_QUERY = 150
  }
}

/** Splits a polyline into chunks that share their boundary point, so no segment is lost. */
internal fun <T> List<T>.chunkedOverlapping(size: Int): List<List<T>> {
  require(size >= 2)
  if (this.size <= size) return listOf(this)
  val chunks = mutableListOf<List<T>>()
  var start = 0
  while (start < this.size - 1) {
    val end = minOf(start + size, this.size)
    chunks += subList(start, end)
    start = end - 1
  }
  return chunks
}
