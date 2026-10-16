package com.joegineered.ontheroad.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import com.joegineered.ontheroad.AppGraph
import com.joegineered.ontheroad.nav.parseDestinationIntent
import uniffi.ferrostar.GeographicCoordinate

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    window.isNavigationBarContrastEnforced = false

    if (savedInstanceState == null) handleDestinationIntent(intent)
    setContent { MaterialTheme { Surface { MainScreen() } } }
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    handleDestinationIntent(intent)
  }

  /** Shows a destination shared as a geo: or google.navigation: link, ready to start. */
  private fun handleDestinationIntent(intent: Intent) {
    val destination = parseDestinationIntent(intent) ?: return
    val lat = destination.latitude ?: return
    val lng = destination.longitude ?: return
    AppGraph.navigationViewModel.selectDestination(GeographicCoordinate(lat, lng), destination.query)
  }
}
