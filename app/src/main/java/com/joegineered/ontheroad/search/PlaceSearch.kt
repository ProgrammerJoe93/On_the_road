package com.joegineered.ontheroad.search

import android.util.Log
import java.text.Normalizer
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

  /** No connection: [places] are saved places (recent destinations) that match the query. */
  data class Offline(val places: List<Place>) : SearchState
}

/**
 * Search-as-you-type over [PhotonGeocoder], shared by the phone and car UIs. Typing is debounced
 * and each new query cancels the previous request. Offline, it searches [savedPlaces] instead.
 */
class PlaceSearch(
    private val geocoder: PhotonGeocoder,
    private val scope: CoroutineScope,
    private val near: () -> Pair<Double, Double>?,
    private val isOnline: () -> Boolean = { true },
    private val savedPlaces: suspend () -> List<Place> = { emptyList() },
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
          if (!isOnline()) {
            _state.value = SearchState.Offline(matching(savedPlaces(), trimmed))
            return@launch
          }
          _state.value = SearchState.Loading
          _state.value =
              try {
                SearchState.Results(geocoder.search(trimmed, near()))
              } catch (e: CancellationException) {
                throw e
              } catch (e: Exception) {
                Log.w(TAG, "Search failed", e)
                // The connection may have dropped while searching.
                if (!isOnline()) SearchState.Offline(matching(savedPlaces(), trimmed))
                else SearchState.Failed(e.message ?: e.javaClass.simpleName)
              }
        }
  }

  fun clear() {
    job?.cancel()
    _state.value = SearchState.Idle
  }

  companion object {
    private const val TAG = "PlaceSearch"

    /**
     * Places whose title or subtitle contains every word of [query], ignoring case and accents
     * (so "jyvaskyla" finds "Jyväskylä").
     */
    fun matching(places: List<Place>, query: String): List<Place> {
      val words = fold(query).split(' ').filter { it.isNotBlank() }
      return places.filter { place ->
        val text = fold(listOfNotNull(place.title, place.subtitle).joinToString(" "))
        words.all { it in text }
      }
    }

    private fun fold(text: String): String =
        Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
  }
}
