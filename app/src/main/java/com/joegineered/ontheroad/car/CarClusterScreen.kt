package com.joegineered.ontheroad.car

import androidx.car.app.CarContext
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.joegineered.ontheroad.AppGraph
import com.joegineered.ontheroad.config.AppConfig
import com.joegineered.ontheroad.settings.ThemeMode
import com.rallista.car.app.compose.ComposableScreen
import com.stadiamaps.ferrostar.car.app.template.NavigationTemplateBuilder
import com.stadiamaps.ferrostar.composeui.config.VisualNavigationViewConfig
import com.stadiamaps.ferrostar.core.NavigationUiState
import com.stadiamaps.ferrostar.core.extensions.progress
import com.stadiamaps.ferrostar.maplibreui.runtime.navigationCameraOptions
import com.stadiamaps.ferrostar.maplibreui.runtime.rememberNavigationMapState
import com.stadiamaps.ferrostar.ui.maplibre.car.app.CarAppNavigationView
import com.stadiamaps.ferrostar.ui.maplibre.car.app.runtime.SurfaceAreaTracker
import com.stadiamaps.ferrostar.ui.maplibre.car.app.runtime.screenSurfaceState
import com.stadiamaps.ferrostar.ui.maplibre.car.app.runtime.surfaceStableFractionalPadding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import uniffi.ferrostar.DrivingSide

/**
 * The map in the car's instrument cluster, for cars where Android Auto draws navigation there
 * (Car App API level 6+). The cluster has no input and should show only the map and route, so this
 * follows the navigation session that the main screen or the phone controls. Cars may also show
 * the turn card from the template.
 */
class CarClusterScreen(carContext: CarContext) : ComposableScreen(carContext) {

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
  private val viewModel = AppGraph.navigationViewModel
  private val surfaceAreaTracker = SurfaceAreaTracker { surfaceGestureCallback = it }

  private var uiState: NavigationUiState? by mutableStateOf(null)
  private var carDarkMode by mutableStateOf(carContext.isDarkMode)
  private var themeMode by mutableStateOf(ThemeMode.AUTO)

  init {
    viewModel.navigationUiState
        .onEach { state ->
          uiState = state
          invalidate()
        }
        .launchIn(scope)
    AppGraph.settings.themeMode.onEach { themeMode = it }.launchIn(scope)

    lifecycle.addObserver(
        object : DefaultLifecycleObserver {
          override fun onDestroy(owner: LifecycleOwner) = scope.cancel()
        }
    )
  }

  /** Called by the session when the car switches between day and night. */
  fun onCarConfigurationChanged() {
    carDarkMode = carContext.isDarkMode
  }

  private val night: Boolean
    get() =
        when (themeMode) {
          ThemeMode.AUTO -> carDarkMode
          ThemeMode.DAY -> false
          ThemeMode.NIGHT -> true
        }

  @Composable
  override fun content() {
    MaterialTheme(colorScheme = if (night) darkColorScheme() else lightColorScheme()) {
      val surfaceArea by screenSurfaceState(surfaceAreaTracker)
      val normalPadding = surfaceStableFractionalPadding(surfaceArea?.compositeArea)
      val trackingPadding = surfaceStableFractionalPadding(surfaceArea?.compositeArea, top = 0.5f)
      val cameraOptions =
          navigationCameraOptions()
              .copy(browsingPadding = normalPadding, navigationPadding = trackingPadding)
      val mapState = rememberNavigationMapState(navigationCameraOptions = cameraOptions)
      val navigating = uiState?.isNavigating() == true

      LaunchedEffect(navigating, mapState) { mapState.recenter(isNavigating = navigating) }

      CarAppNavigationView(
          modifier = Modifier.fillMaxSize(),
          styleUrl = AppConfig.mapStyleUrl(night),
          navigationMapState = mapState,
          navigationCameraOptions = cameraOptions,
          viewModel = viewModel,
          config = VisualNavigationViewConfig.Default(),
          surfaceAreaTracker = surfaceAreaTracker,
      )
    }
  }

  override fun onGetTemplate(): Template {
    val state = uiState
    // The template needs an action strip, but the cluster shows no actions.
    val template =
        NavigationTemplateBuilder(carContext)
            .setBackupDrivingSide(DrivingSide.RIGHT)
            .setOnStopNavigation { viewModel.stopNavigation() }
            .setTripState(state?.tripState?.takeIf { state.isNavigating() })
            .build() as NavigationTemplate
    return NavigationTemplate.Builder()
        .setTripInfo(template, state?.tripState?.progress())
        .apply { template.actionStrip?.let(::setActionStrip) }
        .build()
  }
}
