package com.joegineered.ontheroad.car

import android.content.Context
import android.util.Log
import androidx.car.app.model.CarColor
import androidx.car.app.model.DateTimeWithZone
import androidx.car.app.navigation.NavigationManager
import androidx.car.app.navigation.NavigationManagerCallback
import androidx.car.app.navigation.model.Destination
import androidx.car.app.navigation.model.TravelEstimate
import androidx.car.app.navigation.model.Trip
import com.stadiamaps.ferrostar.car.app.navigation.TurnByTurnNotificationManager
import com.stadiamaps.ferrostar.car.app.template.models.toCarDistance
import com.stadiamaps.ferrostar.car.app.template.models.toCarStep
import com.stadiamaps.ferrostar.car.app.template.models.toCarTravelEstimate
import com.stadiamaps.ferrostar.core.NavigationUiState
import com.stadiamaps.ferrostar.core.NavigationViewModel
import com.stadiamaps.ferrostar.core.extensions.currentRoadName
import com.stadiamaps.ferrostar.core.extensions.currentStep
import com.stadiamaps.ferrostar.core.extensions.progress
import com.stadiamaps.ferrostar.core.extensions.visualInstruction
import java.util.TimeZone
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.onEach
import uniffi.ferrostar.DrivingSide
import uniffi.ferrostar.RouteStep
import uniffi.ferrostar.TripProgress

/**
 * Sends the navigation session to Android Auto: started/ended, the trip (next maneuver, distance
 * to it, lanes, ETA) and turn notifications while the car screen is in the background.
 *
 * Android Auto forwards the trip to vehicles that show navigation in the instrument cluster or
 * head-up display. Those show the *step's* distance next to the maneuver arrow, so the step must
 * carry the distance to the next maneuver. Ferrostar 0.58's NavigationManagerBridge gives the step
 * the distance to the destination instead, which is why this replaces it.
 */
class TripPublisher(
    private val navigationManager: NavigationManager,
    private val context: Context,
    private val viewModel: NavigationViewModel,
    private val notificationManager: TurnByTurnNotificationManager?,
    private val onStopNavigation: () -> Unit,
    private val onAutoDriveEnabled: () -> Unit,
    private val isCarForeground: () -> Boolean,
    private val backupDrivingSide: DrivingSide = DrivingSide.RIGHT,
) {
  private var jobs: List<Job> = emptyList()
  private var wasNavigating = false

  fun start(scope: CoroutineScope) {
    navigationManager.setNavigationManagerCallback(
        object : NavigationManagerCallback {
          override fun onStopNavigation() = this@TripPublisher.onStopNavigation()

          override fun onAutoDriveEnabled() = this@TripPublisher.onAutoDriveEnabled()
        }
    )

    val tripJob = viewModel.navigationUiState.onEach(::publish).launchIn(scope)

    // One notification per new spoken instruction, only while the car screen isn't visible.
    val notificationJob =
        viewModel.navigationUiState
            .mapNotNull { it.spokenInstruction }
            .distinctUntilChanged { old, new -> old.text == new.text }
            .onEach { if (!isCarForeground()) notificationManager?.update(it) }
            .launchIn(scope)

    jobs = listOf(tripJob, notificationJob)
  }

  fun stop() {
    jobs.forEach { it.cancel() }
    jobs = emptyList()
    notificationManager?.clear()
    if (wasNavigating) {
      navigationManager.navigationEnded()
      wasNavigating = false
    }
    navigationManager.clearNavigationManagerCallback()
  }

  private fun publish(state: NavigationUiState) {
    val navigating = state.isNavigating()
    if (navigating && !wasNavigating) {
      navigationManager.navigationStarted()
      wasNavigating = true
    }
    if (navigating) {
      buildTrip(state)?.let { trip ->
        try {
          navigationManager.updateTrip(trip)
        } catch (e: Exception) {
          Log.w(TAG, "Failed to update trip", e)
        }
      }
    }
    if (!navigating && wasNavigating) {
      notificationManager?.clear()
      navigationManager.navigationEnded()
      wasNavigating = false
    }
  }

  private fun buildTrip(state: NavigationUiState): Trip? {
    val tripState = state.tripState ?: return null
    val instruction = tripState.visualInstruction() ?: return null
    val progress = tripState.progress() ?: return null
    val currentStep = tripState.currentStep() ?: return null

    val step =
        instruction.toCarStep(
            context,
            currentStep.drivingSide ?: backupDrivingSide,
            currentStep.roundaboutExitNumber?.toInt(),
        )
    val tripEstimate = progress.toCarTravelEstimate()

    return Trip.Builder()
        .addStep(step, stepTravelEstimate(progress, currentStep))
        .apply {
          state.destination?.let {
            addDestination(Destination.Builder().setName(it).build(), tripEstimate)
          }
          tripState.currentRoadName()?.let { setCurrentRoad(it) }
        }
        .build()
  }

  /** Distance and arrival time to the next maneuver, which clusters and HUDs display. */
  private fun stepTravelEstimate(progress: TripProgress, step: RouteStep): TravelEstimate {
    val seconds = secondsToNextManeuver(progress.distanceToNextManeuver, step.distance, step.duration)
    val arrival =
        DateTimeWithZone.create(
            System.currentTimeMillis() + seconds * 1000,
            TimeZone.getDefault(),
        )
    return TravelEstimate.Builder(progress.distanceToNextManeuver.toCarDistance(), arrival)
        .setRemainingTimeSeconds(seconds)
        .setRemainingTimeColor(CarColor.GREEN)
        .build()
  }

  companion object {
    private const val TAG = "TripPublisher"

    /**
     * Time to the next maneuver, assuming the rest of the current step is driven at that step's
     * average speed.
     */
    fun secondsToNextManeuver(
        distanceToManeuver: Double,
        stepDistance: Double,
        stepDuration: Double,
    ): Long {
      if (stepDistance <= 0.0) return 0
      val fractionLeft = (distanceToManeuver / stepDistance).coerceIn(0.0, 1.0)
      return (stepDuration * fractionLeft).toLong()
    }
  }
}
