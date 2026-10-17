package com.joegineered.ontheroad.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.joegineered.ontheroad.AppGraph
import com.joegineered.ontheroad.R
import com.joegineered.ontheroad.nav.Destination
import com.joegineered.ontheroad.nav.DestinationRequest
import com.joegineered.ontheroad.search.Place
import com.joegineered.ontheroad.search.PlaceSearch
import com.joegineered.ontheroad.search.SearchState
import kotlinx.coroutines.flow.first

/**
 * Asks the driver about a destination from another app, such as Assistant's "navigate to …": the
 * places matching a spoken name, then (during a trip) whether to replace the trip or add a stop.
 * Shown over both the map and the navigation view.
 */
@Composable
fun DestinationPrompt() {
  val viewModel = AppGraph.navigationViewModel
  when (val prompt = viewModel.destinationPrompt.collectAsState().value) {
    is DestinationRequest.Search -> SearchResultsDialog(prompt)
    is DestinationRequest.Go -> TripChoiceDialog(prompt.destination)
    null -> Unit
  }
}

@Composable
private fun SearchResultsDialog(request: DestinationRequest.Search) {
  val viewModel = AppGraph.navigationViewModel
  val scope = rememberCoroutineScope()
  val search =
      remember(request) {
        PlaceSearch(
            geocoder = AppGraph.geocoder,
            scope = scope,
            near = { viewModel.location.value?.coordinates?.let { it.lat to it.lng } },
            isOnline = { AppGraph.network.isOnline },
            savedPlaces = { AppGraph.quickPlaces.first().map { it.place } },
            debounceMillis = 0,
        )
      }
  LaunchedEffect(search) { search.onQueryChanged(request.query) }
  val state by search.state.collectAsState()

  AlertDialog(
      onDismissRequest = viewModel::dismissDestinationPrompt,
      title = { Text(request.query) },
      text = {
        Column {
          val choose = { place: Place ->
            viewModel.handleDestinationRequest(
                DestinationRequest.Go(Destination.from(place), request.action)
            )
          }
          when (val current = state) {
            is SearchState.Results ->
                if (current.places.isEmpty()) PromptMessage(stringResource(R.string.no_results))
                else PlaceList(current.places, { R.drawable.ic_place }, choose, 320.dp, showDivider = false)
            is SearchState.Offline ->
                if (current.places.isEmpty()) {
                  PromptMessage(stringResource(R.string.search_offline_no_matches))
                } else {
                  PlaceList(current.places, { R.drawable.ic_history }, choose, 320.dp, showDivider = false)
                }
            is SearchState.Failed ->
                PromptMessage(stringResource(R.string.search_failed, current.message), isError = true)
            SearchState.Idle,
            SearchState.Loading -> LinearProgressIndicator(Modifier.fillMaxWidth())
          }
        }
      },
      confirmButton = {},
      dismissButton = {
        TextButton(onClick = viewModel::dismissDestinationPrompt) {
          Text(stringResource(R.string.cancel))
        }
      },
  )
}

/** Replace the trip in progress with [destination], or add it as a stop; Cancel keeps the trip. */
@Composable
private fun TripChoiceDialog(destination: Destination) {
  val viewModel = AppGraph.navigationViewModel
  AlertDialog(
      onDismissRequest = viewModel::dismissDestinationPrompt,
      title = { Text(stringResource(R.string.trip_choice_title)) },
      text = {
        Text(stringResource(R.string.trip_choice_message, destination.toPlace().title))
      },
      confirmButton = {
        Row {
          TextButton(
              onClick = {
                viewModel.dismissDestinationPrompt()
                viewModel.startNavigation(destination)
              }
          ) {
            Text(stringResource(R.string.trip_choice_replace))
          }
          TextButton(
              onClick = {
                viewModel.dismissDestinationPrompt()
                viewModel.stopOnTheWay(destination)
              }
          ) {
            Text(stringResource(R.string.trip_choice_add_stop))
          }
        }
      },
      dismissButton = {
        TextButton(onClick = viewModel::dismissDestinationPrompt) {
          Text(stringResource(R.string.cancel))
        }
      },
  )
}

@Composable
private fun PromptMessage(text: String, isError: Boolean = false) {
  Text(text, color = if (isError) MaterialTheme.colorScheme.error else Color.Unspecified)
}
