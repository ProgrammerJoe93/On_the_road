package com.joegineered.ontheroad.cameras

import kotlin.math.max

/** A camera the driver is approaching. */
data class CameraWarning(val camera: SpeedCamera, val distanceMetres: Double)

data class CameraAlertUpdate(
    /** The nearest camera ahead inside the warning window, if any. */
    val active: CameraWarning?,
    /** Set only on the update where [active] first enters the warning window; announce it. */
    val announce: CameraWarning?,
)

/**
 * Decides when to warn about cameras on the route. Pure logic, driven by the distance travelled
 * along the route, so it is easy to test.
 */
class CameraAlertEngine(
    private val minWarningDistanceMetres: Double = 500.0,
    private val warningLeadSeconds: Double = 15.0,
    /** Keep showing a warning until this far past the camera, to allow for GPS error. */
    private val passedMarginMetres: Double = 20.0,
) {
  private var cameras: List<MatchedCamera> = emptyList()

  /** Camera ids already announced. Kept across reroutes so a camera is announced only once. */
  private val announced = mutableSetOf<Long>()

  fun setCameras(cameras: List<MatchedCamera>) {
    this.cameras = cameras.sortedBy { it.alongRouteMetres }
  }

  /** Clears all state at the end of a navigation session. */
  fun reset() {
    cameras = emptyList()
    announced.clear()
  }

  fun warningDistanceMetres(speedMetresPerSecond: Double?): Double =
      max(minWarningDistanceMetres, (speedMetresPerSecond ?: 0.0) * warningLeadSeconds)

  fun update(alongRouteMetres: Double, speedMetresPerSecond: Double?): CameraAlertUpdate {
    val window = warningDistanceMetres(speedMetresPerSecond)
    val next =
        cameras.firstOrNull { it.alongRouteMetres - alongRouteMetres >= -passedMarginMetres }
            ?: return CameraAlertUpdate(null, null)

    val distance = next.alongRouteMetres - alongRouteMetres
    if (distance > window) return CameraAlertUpdate(null, null)

    val warning = CameraWarning(next.camera, max(distance, 0.0))
    val isNew = announced.add(next.camera.id)
    return CameraAlertUpdate(warning, if (isNew && distance > 0) warning else null)
  }
}
