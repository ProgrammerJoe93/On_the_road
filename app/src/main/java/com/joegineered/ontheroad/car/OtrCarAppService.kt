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
import com.joegineered.ontheroad.nav.DestinationRequest
import com.joegineered.ontheroad.nav.parseDestinationRequest

class OtrCarAppService : CarAppService() {

  // TODO before Play release: restrict to known Android Auto hosts with HostValidator.Builder.
  override fun createHostValidator(): HostValidator = HostValidator.ALLOW_ALL_HOSTS_VALIDATOR

  override fun onCreateSession(sessionInfo: SessionInfo): Session =
      if (sessionInfo.displayType == SessionInfo.DISPLAY_TYPE_CLUSTER) OtrClusterSession()
      else OtrCarSession()
}

/**
 * The instrument cluster session, which cars that draw Android Auto navigation in the cluster
 * start next to [OtrCarSession]. It only shows the map; [OtrCarSession] runs everything else.
 */
class OtrClusterSession : Session() {
  private var clusterScreen: CarClusterScreen? = null

  override fun onCarConfigurationChanged(newConfiguration: Configuration) {
    clusterScreen?.onCarConfigurationChanged()
  }

  override fun onCreateScreen(intent: Intent): Screen {
    AppGraph.init(carContext)
    return CarClusterScreen(carContext).also { clusterScreen = it }
  }
}

/**
 * The Android Auto session. Destinations come as `geo:` or `google.navigation:` intents, for
 * example from the car's assistant ("navigate to Kamppi", "add a stop at …"): in [onCreateScreen]
 * when the app starts, and in [onNewIntent] while it runs.
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
    val request = parseDestinationRequest(intent) ?: return root
    // The screen returned here goes on top of the ones pushed before it, so the map goes first.
    val screens = screenManager()
    screens.push(root)
    return when (request) {
      // A trip started on the phone may be in progress, so this can ask about it.
      is DestinationRequest.Go -> {
        goToDestination(carContext, screens, request.destination, request.action)
        screens.top
      }
      is DestinationRequest.Search ->
          CarSearchScreen(carContext, initialQuery = request.query, action = request.action)
    }
  }

  override fun onNewIntent(intent: Intent) {
    val request = parseDestinationRequest(intent) ?: return
    val screens = screenManager()
    screens.popToRoot()
    when (request) {
      is DestinationRequest.Go ->
          goToDestination(carContext, screens, request.destination, request.action)
      is DestinationRequest.Search ->
          screens.push(
              CarSearchScreen(carContext, initialQuery = request.query, action = request.action)
          )
    }
  }

  private fun screenManager() = carContext.getCarService(ScreenManager::class.java)
}
