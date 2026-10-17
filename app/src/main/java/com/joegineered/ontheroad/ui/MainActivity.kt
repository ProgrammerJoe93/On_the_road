package com.joegineered.ontheroad.ui

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.widget.Toast
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
import androidx.lifecycle.lifecycleScope
import com.joegineered.ontheroad.AppGraph
import com.joegineered.ontheroad.R
import com.joegineered.ontheroad.nav.DestinationRequest
import com.joegineered.ontheroad.nav.parseDestinationRequest
import com.joegineered.ontheroad.nav.parseSharedText
import com.joegineered.ontheroad.nav.viewed
import kotlinx.coroutines.launch

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
    if (intent.action == Intent.ACTION_SEND) return handleSharedText(intent)
    val request = parseDestinationRequest(intent) ?: return
    AppGraph.navigationViewModel.handleDestinationRequest(
        if (intent.action == Intent.ACTION_VIEW) request.viewed() else request
    )
  }

  /**
   * Shows a place shared as text, such as a Google Maps link, ready to start. A short link is
   * followed online first; offline, or if that fails, the rest of the text is searched for.
   */
  private fun handleSharedText(intent: Intent) {
    val text =
        listOfNotNull(intent.getStringExtra(Intent.EXTRA_SUBJECT), intent.getStringExtra(Intent.EXTRA_TEXT))
            .distinct()
            .joinToString("\n")
    val shared = parseSharedText(text)
    val viewModel = AppGraph.navigationViewModel
    if (shared?.shortLink == null || shared.request is DestinationRequest.Go || !AppGraph.network.isOnline) {
      shared?.request?.let(viewModel::handleDestinationRequest) ?: showNoPlaceFound()
      return
    }
    lifecycleScope.launch {
      val request = AppGraph.shortLinks.resolve(shared.shortLink) ?: shared.request
      request?.let(viewModel::handleDestinationRequest) ?: showNoPlaceFound()
    }
  }

  private fun showNoPlaceFound() {
    Toast.makeText(this, R.string.shared_no_place, Toast.LENGTH_LONG).show()
  }
}
