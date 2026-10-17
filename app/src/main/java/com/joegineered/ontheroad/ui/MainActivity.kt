package com.joegineered.ontheroad.ui

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.joegineered.ontheroad.AppGraph
import com.joegineered.ontheroad.nav.parseDestinationRequest
import com.joegineered.ontheroad.nav.viewed

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    applySystemBars(AppGraph.nightMode.isNight.value)
    window.isNavigationBarContrastEnforced = false

    if (savedInstanceState == null) handleDestinationIntent(intent)
    setContent {
      val night by AppGraph.nightMode.isNight.collectAsState()
      LaunchedEffect(night) { applySystemBars(night) }
      MaterialTheme(colorScheme = if (night) darkColorScheme() else lightColorScheme()) {
        Surface {
          MainScreen()
          DestinationPrompt()
        }
      }
    }
  }

  /** Light status bar icons over the dark map at night, dark icons by day. */
  private fun applySystemBars(night: Boolean) {
    val style =
        if (night) SystemBarStyle.dark(Color.TRANSPARENT)
        else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
    enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    handleDestinationIntent(intent)
  }

  /**
   * Handles a geo: or google.navigation: link. Assistant's "navigate to …" (ACTION_NAVIGATE) does
   * what was asked; a link opened or shared from another app (ACTION_VIEW) shows the routes, or
   * the matching places, to pick from first.
   */
  private fun handleDestinationIntent(intent: Intent) {
    val request = parseDestinationRequest(intent) ?: return
    AppGraph.navigationViewModel.handleDestinationRequest(
        if (intent.action == Intent.ACTION_VIEW) request.viewed() else request
    )
  }
}
