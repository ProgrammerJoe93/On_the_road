package com.joegineered.ontheroad.car

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.constraints.ConstraintManager
import androidx.car.app.model.Action
import androidx.car.app.model.CarIcon
import androidx.car.app.model.ItemList
import androidx.car.app.model.Row
import androidx.car.app.model.SearchTemplate
import androidx.car.app.model.Template
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.lifecycleScope
import com.joegineered.ontheroad.AppGraph
import com.joegineered.ontheroad.R
import com.joegineered.ontheroad.nav.Destination
import com.joegineered.ontheroad.search.Place
import com.joegineered.ontheroad.search.PlaceSearch
import com.joegineered.ontheroad.search.QuickPlace
import com.joegineered.ontheroad.search.SavedPlaceKind
import com.joegineered.ontheroad.search.SearchState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

/**
 * Destination search for Android Auto. Shows saved and recent places until the driver types, then
 * search results. The car decides when typing is allowed (typically only when parked); saved and
 * recent places can be picked at any time. [initialQuery] starts a search straight away, e.g. for a
 * place named to the car's assistant.
 */
class CarSearchScreen(carContext: CarContext, initialQuery: String = "") : Screen(carContext) {
  private val viewModel = AppGraph.navigationViewModel

  private val search =
      PlaceSearch(
          geocoder = AppGraph.geocoder,
          scope = lifecycleScope,
          near = { viewModel.location.value?.coordinates?.let { it.lat to it.lng } },
          isOnline = { AppGraph.network.isOnline },
          savedPlaces = { AppGraph.quickPlaces.first().map { it.place } },
      )

  private var query = initialQuery
  private var state: SearchState = SearchState.Idle
  private var quickPlaces: List<QuickPlace> = emptyList()

  private val maxItems: Int =
      carContext
          .getCarService(ConstraintManager::class.java)
          .getContentLimit(ConstraintManager.CONTENT_LIMIT_TYPE_LIST)

  init {
    search.state
        .onEach {
          state = it
          invalidate()
        }
        .launchIn(lifecycleScope)
    AppGraph.quickPlaces
        .onEach {
          quickPlaces = it
          invalidate()
        }
        .launchIn(lifecycleScope)
    if (query.isNotBlank()) search.onQueryChanged(query)
  }

  override fun onGetTemplate(): Template {
    val builder =
        SearchTemplate.Builder(
                object : SearchTemplate.SearchCallback {
                  override fun onSearchTextChanged(searchText: String) {
                    query = searchText
                    search.onQueryChanged(searchText)
                  }

                  override fun onSearchSubmitted(searchText: String) {
                    query = searchText
                    search.onQueryChanged(searchText)
                  }
                }
            )
            .setHeaderAction(Action.BACK)
            .setSearchHint(carContext.getString(R.string.search_hint))
            .setInitialSearchText(query)
            .setShowKeyboardByDefault(false)

    val showingRecents = query.trim().length < 3
    return when {
      showingRecents ->
          builder
              .setItemList(
                  placeList(quickPlaces.map { it.place }, ::iconFor, noItemsMessage = R.string.search_hint)
              )
              .build()
      state == SearchState.Loading -> builder.setLoading(true).build()
      state is SearchState.Results ->
          builder
              .setItemList(placeList((state as SearchState.Results).places, { R.drawable.ic_place }))
              .build()
      state is SearchState.Offline ->
          builder
              .setItemList(
                  placeList(
                      (state as SearchState.Offline).places,
                      ::iconFor,
                      noItemsMessage = R.string.search_offline_no_matches,
                  )
              )
              .build()
      state is SearchState.Failed ->
          builder
              .setItemList(
                  ItemList.Builder()
                      .setNoItemsMessage(
                          carContext.getString(
                              R.string.search_failed,
                              (state as SearchState.Failed).message,
                          )
                      )
                      .build()
              )
              .build()
      // Waiting for the debounce to fire.
      else -> builder.setLoading(true).build()
    }
  }

  /** Home, work, a star for a favourite, or history for a recent place. */
  private fun iconFor(place: Place): Int =
      when (quickPlaces.firstOrNull { it.place == place }?.kind) {
        SavedPlaceKind.HOME -> R.drawable.ic_home
        SavedPlaceKind.WORK -> R.drawable.ic_work
        SavedPlaceKind.FAVOURITE -> R.drawable.ic_star
        null -> R.drawable.ic_history
      }

  private fun placeList(
      places: List<Place>,
      iconRes: (Place) -> Int,
      noItemsMessage: Int = R.string.no_results,
  ): ItemList {
    fun icon(place: Place) = CarIcon.Builder(IconCompat.createWithResource(carContext, iconRes(place))).build()
    return ItemList.Builder()
        .setNoItemsMessage(carContext.getString(noItemsMessage))
        .apply {
          places.take(maxItems).forEach { place ->
            addItem(
                Row.Builder()
                    .setTitle(place.title)
                    .apply { place.subtitle?.let { addText(it) } }
                    .setImage(icon(place))
                    .setOnClickListener { navigateTo(place) }
                    .build()
            )
          }
        }
        .build()
  }

  /** Previews routes to [place]; during a trip, goes straight there instead. */
  private fun navigateTo(place: Place) {
    val destination = Destination.from(place)
    if (viewModel.navigationUiState.value.isNavigating()) viewModel.startNavigation(destination)
    else viewModel.selectDestination(destination)
    screenManager.pop()
  }
}
