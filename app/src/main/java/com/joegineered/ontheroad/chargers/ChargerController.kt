package com.joegineered.ontheroad.chargers

import android.util.Log
import com.joegineered.ontheroad.cameras.LatLng
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Chargers for the map in view, and the one the user picked. Shared by phone and car. */
class ChargerController(
    private val repository: ChargerRepository,
    private val scope: CoroutineScope,
) {
  private val _visible = MutableStateFlow<List<ChargingStation>>(emptyList())
  val visible: StateFlow<List<ChargingStation>> = _visible.asStateFlow()

  private val _selected = MutableStateFlow<ChargingStation?>(null)
  val selected: StateFlow<ChargingStation?> = _selected.asStateFlow()

  private var viewportJob: Job? = null
  private val known = mutableMapOf<String, ChargingStation>()

  /** Call when the map stops moving; loads chargers once zoomed in far enough. */
  fun onViewportChanged(south: Double, west: Double, north: Double, east: Double, zoom: Double) {
    viewportJob?.cancel()
    if (zoom < MIN_ZOOM) {
      _visible.value = emptyList()
      return
    }
    viewportJob =
        scope.launch {
          delay(400) // wait for the camera to settle
          try {
            repository.inBounds(south, west, north, east)?.let { publish(it) }
          } catch (e: CancellationException) {
            throw e
          } catch (e: Exception) {
            Log.w(TAG, "Could not load chargers", e)
          }
        }
  }

  suspend fun nearest(
      position: LatLng,
      fastOnly: Boolean,
      plugTypes: Set<SocketType>,
  ): List<Pair<ChargingStation, Double>> =
      repository.nearest(position, limit = 20, fastOnly = fastOnly, plugTypes = plugTypes).also { results ->
        synchronized(known) { results.forEach { (station, _) -> known[station.id] = station } }
      }

  fun select(id: String) {
    _selected.value = synchronized(known) { known[id] }
  }

  fun select(station: ChargingStation) {
    synchronized(known) { known[station.id] = station }
    _selected.value = station
  }

  fun clearSelection() {
    _selected.value = null
  }

  private fun publish(stations: List<ChargingStation>) {
    synchronized(known) { stations.forEach { known[it.id] = it } }
    _visible.value = stations
  }

  companion object {
    private const val TAG = "ChargerController"
    /** Below this zoom the map would need too many tiles; chargers are hidden. */
    const val MIN_ZOOM = 11.0
  }
}
