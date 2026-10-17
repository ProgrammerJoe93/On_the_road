package com.joegineered.ontheroad.search

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.savedPlacesStore by preferencesDataStore(name = "saved_places")

@Serializable
enum class SavedPlaceKind {
  HOME,
  WORK,
  FAVOURITE,
}

/** A place the driver saved, to pick without searching. */
@Serializable
data class SavedPlace(val kind: SavedPlaceKind, val place: Place) {
  /**
   * The place as listed and navigated to: home and work are titled [homeTitle] and [workTitle],
   * with the address below.
   */
  fun display(homeTitle: String, workTitle: String): Place =
      when (kind) {
        SavedPlaceKind.FAVOURITE -> place
        else ->
            place.copy(
                title = if (kind == SavedPlaceKind.HOME) homeTitle else workTitle,
                subtitle = listOfNotNull(place.title, place.subtitle).joinToString(", "),
            )
      }
}

/**
 * Home, work and favourite places. There is at most one home and one work; each spot is saved
 * once, under one kind. Listed home first, then work, then favourites, newest first.
 */
class SavedPlaces(private val context: Context) {

  val places: Flow<List<SavedPlace>> =
      context.savedPlacesStore.data.map { prefs -> decode(prefs[KEY]) }

  suspend fun save(kind: SavedPlaceKind, place: Place) = edit { saving(it, kind, place) }

  suspend fun remove(place: Place) = edit { removing(it, place) }

  suspend fun rename(place: Place, title: String) = edit { renaming(it, place, title) }

  private suspend fun edit(change: (List<SavedPlace>) -> List<SavedPlace>) {
    context.savedPlacesStore.edit { prefs -> prefs[KEY] = json.encodeToString(change(decode(prefs[KEY]))) }
  }

  companion object {
    private val KEY = stringPreferencesKey("places")
    private val json = Json { ignoreUnknownKeys = true }

    private fun decode(value: String?): List<SavedPlace> =
        value?.let { runCatching { json.decodeFromString<List<SavedPlace>>(it) }.getOrNull() }
            .orEmpty()

    /**
     * Saves [place] as [kind]: a new home or work replaces the old one, and any earlier entry
     * for the same spot is dropped.
     */
    fun saving(existing: List<SavedPlace>, kind: SavedPlaceKind, place: Place): List<SavedPlace> {
      val others =
          existing.filterNot {
            it.place.isSameSpot(place) || (kind != SavedPlaceKind.FAVOURITE && it.kind == kind)
          }
      return (listOf(SavedPlace(kind, place)) + others).sortedBy { it.kind.ordinal }
    }

    fun removing(existing: List<SavedPlace>, place: Place): List<SavedPlace> =
        existing.filterNot { it.place.isSameSpot(place) }

    fun renaming(existing: List<SavedPlace>, place: Place, title: String): List<SavedPlace> {
      val trimmed = title.trim().takeIf { it.isNotEmpty() } ?: return existing
      return existing.map {
        if (it.place.isSameSpot(place)) it.copy(place = it.place.copy(title = trimmed)) else it
      }
    }

    /** The saved entry for the spot of [place], if any. */
    fun find(saved: List<SavedPlace>, place: Place): SavedPlace? =
        saved.firstOrNull { it.place.isSameSpot(place) }

    /** [recents] without the spots that are already saved, so a place isn't listed twice. */
    fun unsavedRecents(recents: List<Place>, saved: List<SavedPlace>): List<Place> =
        recents.filterNot { recent -> saved.any { it.place.isSameSpot(recent) } }
  }
}

/** A place listed before typing a search: a saved place ([kind]), or a recent one (null). */
data class QuickPlace(val place: Place, val kind: SavedPlaceKind?)

/** Saved places, then recent destinations that aren't saved. */
fun quickPlaces(
    saved: List<SavedPlace>,
    recents: List<Place>,
    homeTitle: String,
    workTitle: String,
): List<QuickPlace> =
    saved.map { QuickPlace(it.display(homeTitle, workTitle), it.kind) } +
        SavedPlaces.unsavedRecents(recents, saved).map { QuickPlace(it, null) }
