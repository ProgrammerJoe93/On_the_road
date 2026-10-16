package com.joegineered.ontheroad.cameras

/**
 * Decides which speed cameras apply to a route, and where along the route they are.
 *
 * Cameras are often mapped as a point beside the road rather than on it, so a camera is kept when
 * it is close to the route, closer to the route than to any other road, and (when its direction is
 * known) enforces the direction we travel in.
 */
class RouteCameraMatcher(
    private val maxOffsetMetres: Double = 25.0,
    /** Tighter tolerance near junctions, where other roads come close to the route. */
    private val junctionMaxOffsetMetres: Double = 15.0,
    private val junctionRadiusMetres: Double = 30.0,
    private val maxDirectionDifferenceDegrees: Double = 60.0,
    /** A nearby road point closer than this to the route is treated as the route itself. */
    private val sameRoadToleranceMetres: Double = 6.0,
    /** How much closer another road must be before the camera is assigned to it instead. */
    private val otherRoadMarginMetres: Double = 2.0,
) {

  /**
   * @param route the route geometry.
   * @param maneuverPoints positions of the route's maneuvers (junctions).
   * @param nearbyRoads geometries of OSM roads near the cameras, used to reject cameras that belong
   *   to a neighbouring or crossing road.
   * @return cameras on the route, ordered by distance along it.
   */
  fun match(
      route: List<LatLng>,
      cameras: List<SpeedCamera>,
      maneuverPoints: List<LatLng> = emptyList(),
      nearbyRoads: List<List<LatLng>> = emptyList(),
  ): List<MatchedCamera> {
    val routeLine = MeasuredPolyline(route)
    val roadLines = nearbyRoads.filter { it.size >= 2 }.map(::MeasuredPolyline)

    return cameras
        .mapNotNull { camera -> matchCamera(camera, routeLine, maneuverPoints, roadLines) }
        .distinctBy { it.camera.id }
        .sortedBy { it.alongRouteMetres }
  }

  private fun matchCamera(
      camera: SpeedCamera,
      routeLine: MeasuredPolyline,
      maneuverPoints: List<LatLng>,
      roadLines: List<MeasuredPolyline>,
  ): MatchedCamera? {
    val projection = routeLine.project(camera.position) ?: return null

    val nearJunction =
        maneuverPoints.any { distanceMetres(it, projection.point) <= junctionRadiusMetres }
    val tolerance = if (nearJunction) junctionMaxOffsetMetres else maxOffsetMetres
    if (projection.offsetMetres > tolerance) return null

    if (belongsToOtherRoad(camera.position, projection.offsetMetres, routeLine, roadLines)) {
      return null
    }

    if (!enforcesTravelDirection(camera, projection, routeLine)) return null

    return MatchedCamera(camera, projection.alongMetres, projection.offsetMetres)
  }

  private fun belongsToOtherRoad(
      position: LatLng,
      offsetFromRoute: Double,
      routeLine: MeasuredPolyline,
      roadLines: List<MeasuredPolyline>,
  ): Boolean =
      roadLines.any { road ->
        val nearest = road.project(position) ?: return@any false
        val isRouteItself =
            (routeLine.project(nearest.point)?.offsetMetres ?: Double.MAX_VALUE) <=
                sameRoadToleranceMetres
        !isRouteItself && nearest.offsetMetres + otherRoadMarginMetres < offsetFromRoute
      }

  private fun enforcesTravelDirection(
      camera: SpeedCamera,
      projection: PolylineProjection,
      routeLine: MeasuredPolyline,
  ): Boolean {
    camera.enforcementFrom?.let { from ->
      val fromProjection = routeLine.project(from)
      // When the section start is on our route, we must reach it before the camera. When it is
      // not (we joined the road mid-section), fall back to the direction tag below.
      if (fromProjection != null && fromProjection.offsetMetres <= maxOffsetMetres) {
        return fromProjection.alongMetres <= projection.alongMetres + sameRoadToleranceMetres
      }
    }
    if (camera.directions.isEmpty()) return true
    return camera.directions.any {
      bearingDifference(it, projection.segmentBearing) <= maxDirectionDifferenceDegrees
    }
  }
}
