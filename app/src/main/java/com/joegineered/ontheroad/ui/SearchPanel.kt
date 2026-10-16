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
import com.joegineered.ontheroad.search.SearchState

/**
 * A search bar for addresses and places. While focused, it lists recent destinations (empty
 * query) or search results; picking one calls [onPlaceSelected].
 */
@Composable
fun SearchPanel(
    onPlaceSelected: (Place) -> Unit,
    modifier: Modifier = Modifier,
    onActiveChanged: (Boolean) -> Unit = {},
    onChargersNearby: (() -> Unit)? = null,
    maxListHeight: Dp = 320.dp,
) {
  val scope = rememberCoroutineScope()
  val viewModel = AppGraph.navigationViewModel
  val search = remember {
    PlaceSearch(
        geocoder = AppGraph.geocoder,
        scope = scope,
        near = { viewModel.location.value?.coordinates?.let { it.lat to it.lng } },
    )
  }
  val state by search.state.collectAsState()
  val recents by AppGraph.recentDestinations.places.collectAsState(initial = emptyList())
  var query by remember { mutableStateOf("") }
  var focused by remember { mutableStateOf(false) }
  val focusManager = LocalFocusManager.current

  fun choose(place: Place) {
    focusManager.clearFocus()
    query = ""
    search.clear()
    onPlaceSelected(place)
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
              Modifier.fillMaxWidth().onFocusChanged {
                focused = it.isFocused
                onActiveChanged(it.isFocused)
              },
          placeholder = { Text(stringResource(R.string.search_hint)) },
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
                  onSearch = { (state as? SearchState.Results)?.places?.firstOrNull()?.let(::choose) }
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
        showingRecents && recents.isNotEmpty() -> {
          SectionLabel(stringResource(R.string.recent_destinations))
          PlaceList(recents, R.drawable.ic_history, ::choose, maxListHeight, showDivider = false)
        }
        showingRecents -> Unit
        state is SearchState.Results -> {
          val places = (state as SearchState.Results).places
          if (places.isEmpty()) Message(stringResource(R.string.no_results))
          else PlaceList(places, R.drawable.ic_place, ::choose, maxListHeight)
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
    iconRes: Int,
    onClick: (Place) -> Unit,
    maxHeight: Dp,
    showDivider: Boolean = true,
) {
  if (showDivider) HorizontalDivider()
  LazyColumn(Modifier.heightIn(max = maxHeight)) {
    items(places, key = { "${it.lat},${it.lng},${it.title}" }) { place ->
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
  }
}

@Composable
private fun SectionLabel(text: String) {
  HorizontalDivider()
  Text(
      text,
      style = MaterialTheme.typography.labelMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.padding(start = 16.dp, top = 8.dp),
  )
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
