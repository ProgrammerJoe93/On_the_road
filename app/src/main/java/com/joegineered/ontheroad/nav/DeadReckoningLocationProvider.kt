package com.joegineered.ontheroad.nav

import android.location.Location
import android.os.SystemClock
import com.joegineered.ontheroad.cameras.LatLng
import com.joegineered.ontheroad.cameras.MeasuredPolyline
import com.stadiamaps.ferrostar.core.location.NavigationLocationProviding
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import uniffi.ferrostar.GeographicCoordinate

/**
 * Live locations, with positions estimated along the route by [DeadReckoner] while they stop
 * coming, as in a tunnel. [route] is the geometry of the trip in progress, or null when not
 * navigating (then nothing is estimated).
 */
class DeadReckoningLocationProvider(
    private val live: NavigationLocationProviding,
    private val route: () -> List<GeographicCoordinate>?,
) : NavigationLocationProviding {

  private val _estimating = MutableStateFlow(false)
  /** True while the locations are estimates, not fixes. */
  val estimating: StateFlow<Boolean> = _estimating.asStateFlow()

  private var measuredRoute: Pair<List<GeographicCoordinate>, MeasuredPolyline>? = null

  override suspend fun lastLocation(): Location? = live.lastLocation()

  override fun locationUpdates(intervalMillis: Long): Flow<Location> = channelFlow {
    val reckoner = DeadReckoner()
    launch {
      live.locationUpdates(intervalMillis).collect { location ->
        reckoner.onFix(
            PositionFix(
                LatLng(location.latitude, location.longitude),
                System.currentTimeMillis(),
                location.speed.toDouble().takeIf { location.hasSpeed() },
            )
        )
        _estimating.value = false
        send(location)
      }
    }
    while (true) {
      delay(intervalMillis)
      val estimate = reckoner.estimate(System.currentTimeMillis(), measured())
      _estimating.value = estimate != null
      if (estimate != null) send(estimate.toLocation())
    }
  }

  /** The route, measured once per route rather than on every tick. */
  private fun measured(): MeasuredPolyline? {
    val points = route()?.takeIf { it.size >= 2 } ?: return null
    measuredRoute?.let { (cached, line) -> if (cached === points || cached == points) return line }
    return MeasuredPolyline(points.map { LatLng(it.lat, it.lng) }).also { measuredRoute = points to it }
  }

  private fun EstimatedPosition.toLocation() =
      Location(PROVIDER).apply {
        latitude = position.lat
        longitude = position.lng
        bearing = this@toLocation.bearing.toFloat()
        speed = this@toLocation.speed.toFloat()
        accuracy = accuracyMetres.toFloat()
        time = System.currentTimeMillis()
        elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
      }

  private companion object {
    const val PROVIDER = "dead-reckoning"
  }
}
