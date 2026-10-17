package com.joegineered.ontheroad.car

import android.content.Intent
import android.content.res.Configuration
import androidx.car.app.CarAppService
import androidx.car.app.Screen
import androidx.car.app.ScreenManager
import androidx.car.app.Session
import androidx.car.app.SessionInfo
import androidx.car.app.validation.HostValidator
import com.joegineered.ontheroad.AppGraph
import com.joegineered.ontheroad.nav.Destination
import com.joegineered.ontheroad.nav.DestinationRequest
import com.joegineered.ontheroad.nav.parseDestinationIntent
import com.joegineered.ontheroad.nav.toRequest

class OtrCarAppService : CarAppService() {

  // TODO before Play release: restrict to known Android Auto hosts with HostValidator.Builder.
  override fun createHostValidator(): HostValidator = HostValidator.ALLOW_ALL_HOSTS_VALIDATOR

  override fun onCreateSession(sessionInfo: SessionInfo): Session = OtrCarSession()
}

/**
 * The Android Auto session. Destinations come as `geo:` or `google.navigation:` intents, for
 * example from the car's assistant ("navigate to Kamppi"): in [onCreateScreen] when the app
 * starts, and in [onNewIntent] while it runs.
 */
class OtrCarSession : Session() {
  private var navigationScreen: CarNavigationScreen? = null

  override fun onCarConfigurationChanged(newConfiguration: Configuration) {
    navigationScreen?.onCarConfigurationChanged()
  }

  override fun onCreateScreen(intent: Intent): Screen {
    AppGraph.init(carContext)
    AppGraph.refreshLocationPermission()
    AppGraph.speedCameras.start()
    AppGraph.roadsideStops.start()
    AppGraph.startTraffic()
    AppGraph.offlineMaps.start()
    val root = CarNavigationScreen(carContext).also { navigationScreen = it }
    return when (val request = parseDestinationIntent(intent)?.toRequest()) {
      is DestinationRequest.Go -> root.also { go(request.destination) }
      // The screen returned here goes on top of the ones pushed before it.
      is DestinationRequest.Search -> {
        screenManager().push(root)
        CarSearchScreen(carContext, initialQuery = request.query)
      }
      null -> root
    }
  }

  override fun onNewIntent(intent: Intent) {
    val request = parseDestinationIntent(intent)?.toRequest() ?: return
    val screens = screenManager()
    screens.popToRoot()
    when (request) {
      is DestinationRequest.Go -> go(request.destination)
      is DestinationRequest.Search ->
          screens.push(CarSearchScreen(carContext, initialQuery = request.query))
    }
  }

  /**
   * Drives to [destination], replacing the current trip if there is one. Right after the app
   * starts there may be no location fix yet, so it shows the route preview instead, which is
   * planned on the first fix.
   */
  private fun go(destination: Destination) {
    val viewModel = AppGraph.navigationViewModel
    if (viewModel.navigationUiState.value.isNavigating() || viewModel.location.value != null) {
      viewModel.startNavigation(destination)
    } else {
      viewModel.selectDestination(destination)
    }
  }

  private fun screenManager() = carContext.getCarService(ScreenManager::class.java)
}
