package com.joegineered.ontheroad.car

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.ScreenManager
import androidx.car.app.model.Action
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Template
import com.joegineered.ontheroad.AppGraph
import com.joegineered.ontheroad.R
import com.joegineered.ontheroad.nav.Destination
import com.joegineered.ontheroad.nav.DestinationOutcome
import com.joegineered.ontheroad.nav.TripAction
import com.joegineered.ontheroad.nav.destinationOutcome

/**
 * Carries out [action] for [destination] in the car, from a voice request or a search result.
 * During a trip this may ask the driver first, on a screen pushed onto [screenManager].
 */
fun goToDestination(
    carContext: CarContext,
    screenManager: ScreenManager,
    destination: Destination,
    action: TripAction = TripAction.NAVIGATE,
) {
  val viewModel = AppGraph.navigationViewModel
  val outcome =
      destinationOutcome(
          action = action,
          navigating = viewModel.navigationUiState.value.isNavigating(),
          previewing = viewModel.selectedDestination.value != null,
          hasLocation = viewModel.location.value != null,
      )
  when (outcome) {
    DestinationOutcome.START -> viewModel.startNavigation(destination)
    DestinationOutcome.PREVIEW -> viewModel.selectDestination(destination)
    DestinationOutcome.ADD_TO_PREVIEW -> viewModel.addStop(destination)
    DestinationOutcome.ADD_TO_TRIP -> viewModel.stopOnTheWay(destination)
    DestinationOutcome.ASK -> screenManager.push(CarTripChoiceScreen(carContext, destination))
  }
}

/**
 * Asks whether a new destination replaces the trip in progress or becomes a stop on it. Back
 * keeps the trip as it is.
 */
class CarTripChoiceScreen(carContext: CarContext, private val destination: Destination) :
    Screen(carContext) {

  override fun onGetTemplate(): Template {
    val viewModel = AppGraph.navigationViewModel
    val replace =
        Action.Builder()
            .setTitle(carContext.getString(R.string.trip_choice_replace))
            .setOnClickListener {
              viewModel.startNavigation(destination)
              screenManager.pop()
            }
            .build()
    val addStop =
        Action.Builder()
            .setTitle(carContext.getString(R.string.trip_choice_add_stop))
            .setFlags(Action.FLAG_PRIMARY)
            .setOnClickListener {
              viewModel.stopOnTheWay(destination)
              screenManager.pop()
            }
            .build()
    return MessageTemplate.Builder(
            carContext.getString(R.string.trip_choice_message, destination.toPlace().title)
        )
        .setTitle(carContext.getString(R.string.trip_choice_title))
        .setHeaderAction(Action.BACK)
        .addAction(addStop)
        .addAction(replace)
        .build()
  }
}
