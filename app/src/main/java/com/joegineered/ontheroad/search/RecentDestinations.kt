package com.joegineered.ontheroad.search

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.recentsStore by preferencesDataStore(name = "recent_destinations")

/** The most recent navigation destinations, newest first. */
class RecentDestinations(private val context: Context, private val maxEntries: Int = 10) {

  val places: Flow<List<Place>> =
      context.recentsStore.data.map { prefs -> decode(prefs[KEY]) }

  suspend fun add(place: Place) {
    context.recentsStore.edit { prefs ->
      prefs[KEY] = json.encodeToString(addTo(decode(prefs[KEY]), place, maxEntries))
    }
  }

  companion object {
    private val KEY = stringPreferencesKey("places")
    private val json = Json { ignoreUnknownKeys = true }

    private fun decode(value: String?): List<Place> =
        value?.let { runCatching { json.decodeFromString<List<Place>>(it) }.getOrNull() }
            .orEmpty()

    /** Puts [place] first, dropping any earlier entry for the same spot (within ~20 m). */
    fun addTo(existing: List<Place>, place: Place, maxEntries: Int): List<Place> =
        (listOf(place) + existing.filterNot { it.isSameSpot(place) }).take(maxEntries)
  }
}
