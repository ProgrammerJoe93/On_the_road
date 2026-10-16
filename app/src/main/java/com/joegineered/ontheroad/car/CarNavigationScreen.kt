package com.joegineered.ontheroad.car

import androidx.car.app.AppManager
import androidx.car.app.CarContext
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.Alert
import androidx.car.app.model.CarIcon
import androidx.car.app.model.CarText
import androidx.car.app.model.Template
import androidx.car.app.navigation.NavigationManager
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import com.joegineered.ontheroad.AppGraph
import com.joegineered.ontheroad.R
import com.joegineered.ontheroad.cameras.CameraWarning
import com.joegineered.ontheroad.config.AppConfig
import com.joegineered.ontheroad.nav.Destination
import com.joegineered.ontheroad.ui.SpeedCameraLayer
import com.joegineered.ontheroad.ui.formatDistance
import com.joegineered.ontheroad.ui.signageStyle
import com.rallista.car.app.compose.ComposableScreen
import com.stadiamaps.ferrostar.car.app.intent.NavigationDestination
import com.stadiamaps.ferrostar.car.app.navigation.TurnByTurnNotificationManager
import com.stadiamaps.ferrostar.car.app.template.NavigationTemplateBuilder
import com.stadiamaps.ferrostar.composeui.config.VisualNavigationViewConfig
import com.stadiamaps.ferrostar.composeui.config.withSpeedLimitStyle
import com.stadiamaps.ferrostar.core.NavigationUiState
import com.stadiamaps.ferrostar.core.boundingBox
import com.stadiamaps.ferrostar.maplibreui.runtime.NavigationMapState
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
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import uniffi.ferrostar.DrivingSide
import uniffi.ferrostar.GeographicCoordinate

/**
 * The Android Auto navigation screen. Renders the MapLibre map on the car surface and shows
 * Ferrostar's turn-by-turn info in a [NavigationTemplate]. [TripPublisher] sends the trip (next
 * maneuver, distance to it, lanes, ETA) to the car, which is what cars with cluster or HUD support
 * display.
 */
class CarNavigationScreen(
    carContext: CarContext,
    private val initialDestination: NavigationDestination? = null,
) : ComposableScreen(carContext) {

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
  private val viewModel = AppGraph.navigationViewModel

  private val tripPublisher =
      TripPublisher(
          navigationManager = carContext.getCarService(NavigationManager::class.java),
          viewModel = viewModel,
          context = carContext,
          notificationManager =
              TurnByTurnNotificationManager(
                  context = carContext,
                  smallIconRes = R.drawable.ic_navigation,
              ),
          onStopNavigation = { viewModel.stopNavigation() },
          onAutoDriveEnabled = { viewModel.setSimulated(true) },
          isCarForeground = { lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) },
      )

  private var uiState: NavigationUiState? by mutableStateOf(null)
  private var navigationMapState: NavigationMapState? = null
  private var browsingPadding: PaddingValues = PaddingValues()
  private val surfaceAreaTracker = SurfaceAreaTracker { surfaceGestureCallback = it }

  init {
    tripPublisher.start(scope)

    viewModel.navigationUiState
        .onEach { state ->
          uiState = state
          invalidate()
        }
        .launchIn(scope)

    AppGraph.speedCameras.announcements.onEach(::showCameraAlert).launchIn(scope)

    initialDestination?.location?.let { location ->
      viewModel.startNavigation(
          Destination(
              GeographicCoordinate(location.latitude, location.longitude),
              initialDestination.displayName,
          )
      )
    }

    lifecycle.addObserver(
        object : DefaultLifecycleObserver {
          override fun onDestroy(owner: LifecycleOwner) {
            tripPublisher.stop()
            scope.cancel()
          }
        }
    )
  }

  @Composable
  override fun content() {
    val surfaceArea by screenSurfaceState(surfaceAreaTracker)
    val normalPadding = surfaceStableFractionalPadding(surfaceArea?.compositeArea)
    val trackingPadding = surfaceStableFractionalPadding(surfaceArea?.compositeArea, top = 0.5f)
    val cameraOptions =
        navigationCameraOptions()
            .copy(browsingPadding = normalPadding, navigationPadding = trackingPadding)
    val mapState = rememberNavigationMapState(navigationCameraOptions = cameraOptions)
    val cameras by AppGraph.speedCameras.cameras.collectAsState()

    browsingPadding = normalPadding
    navigationMapState = mapState

    LaunchedEffect(uiState?.isNavigating(), mapState) {
      if (uiState?.isNavigating() == true) mapState.recenter(isNavigating = true)
    }
    LaunchedEffect(mapState) { snapshotFlow { mapState.cameraMode }.collectLatest { invalidate() } }

    CarAppNavigationView(
        modifier = Modifier.fillMaxSize(),
        styleUrl = AppConfig.MAP_STYLE_URL,
        navigationMapState = mapState,
        navigationCameraOptions = cameraOptions,
        viewModel = viewModel,
        config = VisualNavigationViewConfig.Default().withSpeedLimitStyle(signageStyle()),
        surfaceAreaTracker = surfaceAreaTracker,
    ) { _ ->
      SpeedCameraLayer(cameras)
    }
  }

  override fun onGetTemplate(): Template {
    val state = uiState
    if (state != null && state.isNavigating()) {
      return NavigationTemplateBuilder(carContext)
          .setBackupDrivingSide(DrivingSide.RIGHT)
          .setOnStopNavigation { viewModel.stopNavigation() }
          .setOnMute(state.isMuted) { viewModel.toggleMute() }
          .setOnZoom(
              onZoomInTapped = { navigationMapState?.zoomIn() },
              onZoomOutTapped = { navigationMapState?.zoomOut() },
          )
          .setOnCycleCamera(navigationMapState?.isTrackingUser == true) { cycleCamera() }
          .setTripState(state.tripState)
          .build()
    }

    // Not navigating: a pannable map with destination search.
    val searchAction =
        Action.Builder()
            .setIcon(
                CarIcon.Builder(IconCompat.createWithResource(carContext, R.drawable.ic_search))
                    .build()
            )
            .setTitle(carContext.getString(R.string.search))
            .setOnClickListener { screenManager.push(CarSearchScreen(carContext)) }
            .build()
    return NavigationTemplate.Builder()
        .setActionStrip(ActionStrip.Builder().addAction(searchAction).build())
        .setMapActionStrip(ActionStrip.Builder().addAction(Action.PAN).build())
        .build()
  }

  private fun cycleCamera() {
    val mapState = navigationMapState ?: return
    if (mapState.isTrackingUser) {
      val bounds = viewModel.navigationUiState.value.routeGeometry?.boundingBox() ?: return
      mapState.showRouteOverview(boundingBox = bounds, paddingValues = browsingPadding)
    } else {
      mapState.recenter(isNavigating = uiState?.isNavigating() == true)
    }
    invalidate()
  }

  /** Shows a camera warning using the car's alert UI (Car App API level 5+). */
  private fun showCameraAlert(warning: CameraWarning) {
    if (carContext.carAppApiLevel < 5) return
    val title =
        CarText.create(
            carContext.getString(R.string.speed_camera_in, formatDistance(warning.distanceMetres))
        )
    val icon =
        CarIcon.Builder(IconCompat.createWithResource(carContext, R.drawable.ic_speed_camera))
            .build()
    val alert =
        Alert.Builder(warning.camera.id.hashCode(), title, CAMERA_ALERT_DURATION_MS)
            .setIcon(icon)
            .apply {
              warning.camera.maxSpeedKmh?.let { setSubtitle(CarText.create("$it")) }
            }
            .build()
    carContext.getCarService(AppManager::class.java).showAlert(alert)
  }

  private companion object {
    const val CAMERA_ALERT_DURATION_MS = 8_000L
  }
}
