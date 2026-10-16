package com.joegineered.ontheroad.car

import android.content.Intent
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
  override fun onCreateScreen(intent: Intent): Screen {
    AppGraph.init(carContext)
    AppGraph.speedCameras.start()
    // Handles geo: and google.navigation: intents, e.g. from the Android Auto assistant.
    val destination = parseDestinationIntent(intent)
    return CarNavigationScreen(carContext, initialDestination = destination)
  }
}
