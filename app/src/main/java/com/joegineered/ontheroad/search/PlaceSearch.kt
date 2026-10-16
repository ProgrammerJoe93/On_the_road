package com.joegineered.ontheroad.search

import android.util.Log
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Search results for the text typed so far. */
sealed interface SearchState {
  data object Idle : SearchState

  data object Loading : SearchState

  data class Results(val places: List<Place>) : SearchState

  data class Failed(val message: String) : SearchState
}

/**
 * Search-as-you-type over [PhotonGeocoder], shared by the phone and car UIs. Typing is debounced
 * and each new query cancels the previous request.
 */
class PlaceSearch(
    private val geocoder: PhotonGeocoder,
    private val scope: CoroutineScope,
    private val near: () -> Pair<Double, Double>?,
    private val debounceMillis: Long = 350,
    private val minQueryLength: Int = 3,
) {
  private val _state = MutableStateFlow<SearchState>(SearchState.Idle)
  val state: StateFlow<SearchState> = _state.asStateFlow()

  private var job: Job? = null

  fun onQueryChanged(query: String) {
    job?.cancel()
    val trimmed = query.trim()
    if (trimmed.length < minQueryLength) {
      _state.value = SearchState.Idle
      return
    }
    job =
        scope.launch {
          delay(debounceMillis)
          _state.value = SearchState.Loading
          _state.value =
              try {
                SearchState.Results(geocoder.search(trimmed, near()))
              } catch (e: CancellationException) {
                throw e
              } catch (e: Exception) {
                Log.w(TAG, "Search failed", e)
                SearchState.Failed(e.message ?: e.javaClass.simpleName)
              }
        }
  }

  fun clear() {
    job?.cancel()
    _state.value = SearchState.Idle
  }

  private companion object {
    const val TAG = "PlaceSearch"
  }
}
