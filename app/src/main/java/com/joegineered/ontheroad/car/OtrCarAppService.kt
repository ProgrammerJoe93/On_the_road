package com.joegineered.ontheroad.car

import android.content.Intent
import android.content.res.Configuration
import androidx.car.app.CarAppService
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.SessionInfo
import androidx.car.app.validation.HostValidator
import com.joegineered.ontheroad.AppGraph
import com.joegineered.ontheroad.nav.parseDestinationIntent

class OtrCarAppService : CarAppService() {

  // TODO before Play release: restrict to known Android Auto hosts with HostValidator.Builder.
  override fun createHostValidator(): HostValidator = HostValidator.ALLOW_ALL_HOSTS_VALIDATOR

  override fun onCreateSession(sessionInfo: SessionInfo): Session = OtrCarSession()
}

class OtrCarSession : Session() {
  private var navigationScreen: CarNavigationScreen? = null

  override fun onCarConfigurationChanged(newConfiguration: Configuration) {
    navigationScreen?.onCarConfigurationChanged()
  }

  override fun onCreateScreen(intent: Intent): Screen {
    AppGraph.init(carContext)
    AppGraph.refreshLocationPermission()
    AppGraph.speedCameras.start()
    AppGraph.startTraffic()
    // Handles geo: and google.navigation: intents, e.g. from the Android Auto assistant.
    val destination = parseDestinationIntent(intent)
    return CarNavigationScreen(carContext, initialDestination = destination).also {
      navigationScreen = it
    }
  }
}
