package com.joegineered.ontheroad.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.joegineered.ontheroad.AppGraph
import com.joegineered.ontheroad.R
import com.joegineered.ontheroad.search.Place
import com.joegineered.ontheroad.search.PlaceSearch
import com.joegineered.ontheroad.search.QuickPlace
import com.joegineered.ontheroad.search.SavedPlaceKind
import com.joegineered.ontheroad.search.SearchState
import kotlinx.coroutines.flow.first

/**
 * A search bar for addresses and places. While focused, it lists saved and recent places (empty
 * query) or search results; picking one calls [onPlaceSelected].
 */
@Composable
fun SearchPanel(
    onPlaceSelected: (Place) -> Unit,
    modifier: Modifier = Modifier,
    onActiveChanged: (Boolean) -> Unit = {},
    onChargersNearby: (() -> Unit)? = null,
    maxListHeight: Dp = 320.dp,
    /** Focuses the search bar from outside, e.g. to search for a stop to add. */
    focusRequester: FocusRequester? = null,
    hint: String = stringResource(R.string.search_hint),
) {
  val scope = rememberCoroutineScope()
  val viewModel = AppGraph.navigationViewModel
  val search = remember {
    PlaceSearch(
        geocoder = AppGraph.geocoder,
        scope = scope,
        near = { viewModel.location.value?.coordinates?.let { it.lat to it.lng } },
        isOnline = { AppGraph.network.isOnline },
        savedPlaces = { AppGraph.quickPlaces.first().map { it.place } },
    )
  }
  val state by search.state.collectAsState()
  val quickPlaces by AppGraph.quickPlaces.collectAsState(initial = emptyList())
  var query by remember { mutableStateOf("") }
  var focused by remember { mutableStateOf(false) }
  val focusManager = LocalFocusManager.current

  fun choose(place: Place) {
    // Before clearing focus, which ends the caller's search mode (such as adding a stop).
    onPlaceSelected(place)
    focusManager.clearFocus()
    query = ""
    search.clear()
  }

  Card(modifier = modifier.fillMaxWidth()) {
    Column {
      TextField(
          value = query,
          onValueChange = {
            query = it
            search.onQueryChanged(it)
          },
          modifier =
              Modifier.fillMaxWidth()
                  .then(focusRequester?.let { Modifier.focusRequester(it) } ?: Modifier)
                  .onFocusChanged {
                    focused = it.isFocused
                    onActiveChanged(it.isFocused)
                  },
          placeholder = { Text(hint) },
          leadingIcon = { Icon(painterResource(R.drawable.ic_search), contentDescription = null) },
          trailingIcon = {
            if (query.isNotEmpty() || focused) {
              IconButton(
                  onClick = {
                    query = ""
                    search.clear()
                    focusManager.clearFocus()
                  }
              ) {
                Icon(
                    painterResource(R.drawable.ic_close),
                    contentDescription = stringResource(R.string.clear_search),
                )
              }
            }
          },
          singleLine = true,
          keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
          keyboardActions =
              KeyboardActions(
                  onSearch = {
                    when (val current = state) {
                      is SearchState.Results -> current.places.firstOrNull()?.let(::choose)
                      is SearchState.Offline -> current.places.firstOrNull()?.let(::choose)
                      else -> Unit
                    }
                  }
              ),
          colors =
              TextFieldDefaults.colors(
                  focusedContainerColor = Color.Transparent,
                  unfocusedContainerColor = Color.Transparent,
                  focusedIndicatorColor = Color.Transparent,
                  unfocusedIndicatorColor = Color.Transparent,
              ),
      )

      if (!focused) return@Column

      if (state == SearchState.Loading) {
        LinearProgressIndicator(Modifier.fillMaxWidth())
      }

      val showingRecents = query.trim().length < 3
      if (showingRecents && onChargersNearby != null) {
        HorizontalDivider()
        Row(
            modifier =
                Modifier.fillMaxWidth()
                    .clickable {
                      focusManager.clearFocus()
                      onChargersNearby()
                    }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
          Icon(painterResource(R.drawable.ic_bolt), contentDescription = null, tint = Color(0xFF2E7D32))
          Text(stringResource(R.string.chargers_nearby), style = MaterialTheme.typography.bodyLarge)
        }
      }
      when {
        showingRecents && quickPlaces.isNotEmpty() -> QuickPlaceList(quickPlaces, ::choose, maxListHeight)
        showingRecents -> Unit
        state is SearchState.Results -> {
          val places = (state as SearchState.Results).places
          if (places.isEmpty()) Message(stringResource(R.string.no_results))
          else PlaceList(places, { R.drawable.ic_place }, ::choose, maxListHeight)
        }
        state is SearchState.Offline -> {
          val places = (state as SearchState.Offline).places
          if (places.isEmpty()) Message(stringResource(R.string.search_offline_no_matches))
          else {
            SectionLabel(stringResource(R.string.search_offline))
            PlaceList(
                places,
                { place -> quickPlaces.firstOrNull { it.place == place }.icon() },
                ::choose,
                maxListHeight,
                showDivider = false,
            )
          }
        }
        state is SearchState.Failed ->
            Message(
                stringResource(R.string.search_failed, (state as SearchState.Failed).message),
                isError = true,
            )
      }
    }
  }
}

@Composable
private fun PlaceList(
    places: List<Place>,
    iconRes: (Place) -> Int,
    onClick: (Place) -> Unit,
    maxHeight: Dp,
    showDivider: Boolean = true,
) {
  if (showDivider) HorizontalDivider()
  LazyColumn(Modifier.heightIn(max = maxHeight)) {
    items(places, key = { it.key() }) { place -> PlaceRow(place, iconRes(place), onClick) }
  }
}

/** Saved places, then recent ones, each under its heading, in one scrolling list. */
@Composable
private fun QuickPlaceList(places: List<QuickPlace>, onClick: (Place) -> Unit, maxHeight: Dp) {
  val (saved, recent) = places.partition { it.kind != null }
  val savedLabel = stringResource(R.string.saved_places_section)
  val recentLabel = stringResource(R.string.recent_destinations)
  LazyColumn(Modifier.heightIn(max = maxHeight)) {
    listOf(savedLabel to saved, recentLabel to recent).forEach { (label, section) ->
      if (section.isEmpty()) return@forEach
      item(key = label) { SectionLabel(label) }
      items(section, key = { "${it.kind}:${it.place.key()}" }) { PlaceRow(it.place, it.icon(), onClick) }
    }
  }
}

private fun Place.key() = "$lat,$lng,$title"

/** The icon for a listed place: home, work, star for a favourite, or history for a recent one. */
private fun QuickPlace?.icon(): Int =
    when (this?.kind) {
      SavedPlaceKind.HOME -> R.drawable.ic_home
      SavedPlaceKind.WORK -> R.drawable.ic_work
      SavedPlaceKind.FAVOURITE -> R.drawable.ic_star
      null -> R.drawable.ic_history
    }

@Composable
private fun PlaceRow(place: Place, iconRes: Int, onClick: (Place) -> Unit) {
  Row(
      modifier =
          Modifier.fillMaxWidth()
              .clickable { onClick(place) }
              .padding(horizontal = 16.dp, vertical = 10.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(16.dp),
  ) {
    Icon(
        painterResource(iconRes),
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Column {
      Text(
          place.title,
          style = MaterialTheme.typography.bodyLarge,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
      )
      place.subtitle?.let {
        Text(
            it,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
      }
    }
  }
}

@Composable
private fun SectionLabel(text: String) {
  Column {
    HorizontalDivider()
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, top = 8.dp),
    )
  }
}

@Composable
private fun Message(text: String, isError: Boolean = false) {
  HorizontalDivider()
  Text(
      text,
      color = if (isError) MaterialTheme.colorScheme.error else Color.Unspecified,
      modifier = Modifier.padding(16.dp),
  )
}
