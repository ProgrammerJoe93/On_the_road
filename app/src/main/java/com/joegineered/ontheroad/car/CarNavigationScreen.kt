package com.joegineered.ontheroad.car

import androidx.car.app.AppManager
import androidx.car.app.CarContext
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.Alert
import androidx.car.app.model.CarColor
import androidx.car.app.model.CarIcon
import androidx.car.app.model.CarText
import androidx.activity.OnBackPressedCallback
import androidx.car.app.model.ItemList
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.RoutePreviewNavigationTemplate
import androidx.car.app.navigation.NavigationManager
import androidx.car.app.navigation.model.MessageInfo
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import com.joegineered.ontheroad.AppGraph
import com.joegineered.ontheroad.R
import com.joegineered.ontheroad.cameras.CameraWarning
import com.joegineered.ontheroad.cameras.displayedSpeedLimit
import com.joegineered.ontheroad.nav.RoutingStatus
import com.joegineered.ontheroad.ui.RoutePreviewLayer
import com.joegineered.ontheroad.ui.formatDuration
import com.joegineered.ontheroad.ui.formatTripDistance
import com.joegineered.ontheroad.config.AppConfig
import com.joegineered.ontheroad.chargers.SocketType
import com.joegineered.ontheroad.nav.Arrival
import com.joegineered.ontheroad.nav.Destination
import com.joegineered.ontheroad.settings.ThemeMode
import com.joegineered.ontheroad.ui.arrivalSummary
import com.joegineered.ontheroad.traffic.TrafficWarning
import com.joegineered.ontheroad.traffic.labelRes
import com.joegineered.ontheroad.ui.ChargerLayer
import com.joegineered.ontheroad.ui.OfflineIndicator
import com.joegineered.ontheroad.ui.TrafficLayer
import com.joegineered.ontheroad.ui.RoadSigns
import com.joegineered.ontheroad.ui.SpeedCameraLayer
import com.joegineered.ontheroad.ui.StopPinsLayer
import com.joegineered.ontheroad.ui.StopsLayer
import com.joegineered.ontheroad.ui.Speedometer
import com.joegineered.ontheroad.ui.speedReading
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterNotNull
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

  /** Back (the header's back action) closes the route preview; enabled while it shows. */
  private val cancelPreview =
      object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
          viewModel.clearDestination()
        }
      }

  /** The car's day/night signal (headlights or the car clock); updated on configuration change. */
  private var carDarkMode by mutableStateOf(carContext.isDarkMode)
  private var themeMode by mutableStateOf(ThemeMode.AUTO)
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
    AppGraph.traffic.announcements.onEach(::showTrafficAlert).launchIn(scope)
    viewModel.arrival.onEach { invalidate() }.launchIn(scope)
    viewModel.selectedDestination.onEach { invalidate() }.launchIn(scope)
    viewModel.preview.onEach { invalidate() }.launchIn(scope)
    viewModel.routingStatus.onEach { invalidate() }.launchIn(scope)
    carContext.onBackPressedDispatcher.addCallback(this, cancelPreview)
    AppGraph.settings.themeMode.onEach { themeMode = it }.launchIn(scope)

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
      CarMap()
    }
  }

  @Composable
  private fun CarMap() {
    val surfaceArea by screenSurfaceState(surfaceAreaTracker)
    val normalPadding = surfaceStableFractionalPadding(surfaceArea?.compositeArea)
    val trackingPadding = surfaceStableFractionalPadding(surfaceArea?.compositeArea, top = 0.5f)
    val cameraOptions =
        navigationCameraOptions()
            .copy(browsingPadding = normalPadding, navigationPadding = trackingPadding)
    val mapState = rememberNavigationMapState(navigationCameraOptions = cameraOptions)
    val cameras by AppGraph.speedCameras.cameras.collectAsState()
    val showChargers by AppGraph.settings.showChargers.collectAsState(initial = true)
    val chargers by AppGraph.chargers.visible.collectAsState()
    val plugTypes by AppGraph.settings.chargerPlugTypes.collectAsState(initial = SocketType.entries.toSet())
    val liveTraffic by AppGraph.settings.liveTrafficEnabled.collectAsState(initial = true)
    val trafficEvents by AppGraph.traffic.events.collectAsState()
    val slowTraffic by AppGraph.traffic.slowTraffic.collectAsState()
    val preview by viewModel.preview.collectAsState()
    val stops by AppGraph.roadsideStops.state.collectAsState()
    val remainingStops by viewModel.remainingStops.collectAsState()

    browsingPadding = normalPadding
    navigationMapState = mapState

    LaunchedEffect(uiState?.isNavigating(), mapState) {
      if (uiState?.isNavigating() == true) mapState.recenter(isNavigating = true)
    }
    LaunchedEffect(mapState) { snapshotFlow { mapState.cameraMode }.collectLatest { invalidate() } }
    LaunchedEffect(preview?.options, mapState) {
      val bounds = preview?.options?.flatMap { it.route.geometry }?.boundingBox() ?: return@LaunchedEffect
      mapState.showRouteOverview(boundingBox = bounds, paddingValues = normalPadding)
    }
    LaunchedEffect(mapState, showChargers) {
      if (!showChargers) return@LaunchedEffect
      snapshotFlow {
            val position = mapState.cameraState.position
            mapState.cameraState.projection?.queryVisibleBoundingBox()?.let { position.zoom to it }
          }
          .filterNotNull()
          .collectLatest { (zoom, bounds) ->
            delay(300)
            AppGraph.chargers.onViewportChanged(bounds.south, bounds.west, bounds.north, bounds.east, zoom)
          }
    }

    Box(Modifier.fillMaxSize()) {
      CarAppNavigationView(
          modifier = Modifier.fillMaxSize(),
          styleUrl = AppConfig.mapStyleUrl(night),
          navigationMapState = mapState,
          navigationCameraOptions = cameraOptions,
          viewModel = viewModel,
          // The speed limit sign is drawn below by RoadSigns, with the roadworks sign beside it.
          config = VisualNavigationViewConfig.Default(),
          surfaceAreaTracker = surfaceAreaTracker,
      ) { _ ->
        if (liveTraffic) TrafficLayer(trafficEvents, slowTraffic)
        if (showChargers) ChargerLayer(chargers.filter { it.matches(plugTypes) })
        SpeedCameraLayer(cameras)
        if (uiState?.isNavigating() == true) {
          StopsLayer(stops)
          StopPinsLayer(remainingStops.map { it.coordinate })
        } else {
          RoutePreviewLayer(preview)
          StopPinsLayer(preview?.stops.orEmpty().map { it.coordinate })
        }
      }

      OfflineIndicator(Modifier.align(Alignment.TopEnd).padding(normalPadding).padding(8.dp))

      // Road signs top left and current speed bottom left, inside the area the car's navigation
      // UI leaves visible.
      if (uiState?.isNavigating() == true) {
        val speed by viewModel.speedMetresPerSecond.collectAsState()
        val metric = AppConfig.unitSystem.collectAsState().value.isMetric()
        val roadworks by AppGraph.traffic.currentRoadworks.collectAsState()
        Box(Modifier.fillMaxSize().padding(normalPadding).padding(8.dp)) {
          RoadSigns(
              speedLimit = uiState?.currentAnnotation?.speedLimit,
              roadworks = roadworks != null,
              modifier = Modifier.align(Alignment.TopStart),
          )
          Speedometer(
              reading =
                  speedReading(
                      speed,
                      uiState?.currentAnnotation?.speedLimit,
                      metric,
                  ),
              metric = metric,
              modifier = Modifier.align(Alignment.BottomStart),
          )
        }
      }
    }
  }

  override fun onGetTemplate(): Template {
    val state = uiState
    val destination = viewModel.selectedDestination.value
    cancelPreview.isEnabled = state?.isNavigating() != true && destination != null
    if (state != null && state.isNavigating()) {
      val template = NavigationTemplateBuilder(carContext)
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
      return withStopsAction(template)
    }

    viewModel.arrival.value?.let { return arrivalTemplate(it) }
    destination?.let { return routePreviewTemplate(it) }

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
    val chargersAction =
        Action.Builder()
            .setIcon(
                CarIcon.Builder(IconCompat.createWithResource(carContext, R.drawable.ic_bolt))
                    .build()
            )
            .setTitle(carContext.getString(R.string.ev_chargers))
            .setOnClickListener { screenManager.push(CarChargersScreen(carContext)) }
            .build()
    return NavigationTemplate.Builder()
        .setActionStrip(
            ActionStrip.Builder().addAction(searchAction).addAction(chargersAction).build()
        )
        .setMapActionStrip(ActionStrip.Builder().addAction(Action.PAN).build())
        .build()
  }

  /**
   * The routes to the chosen destination, to pick one and start. While they load, the car shows
   * its loading state; if planning fails, the reason over the map.
   */
  private fun routePreviewTemplate(destination: Destination): Template {
    val title = destination.label ?: carContext.getString(R.string.selected_destination)
    val preview = viewModel.preview.value?.takeIf { it.destination == destination }
    val start =
        Action.Builder()
            .setTitle(carContext.getString(R.string.start_navigation))
            .setOnClickListener { viewModel.startSelectedDestination() }
            .build()
    if (preview == null) {
      val error =
          when (val status = viewModel.routingStatus.value) {
            is RoutingStatus.Failed -> carContext.getString(R.string.route_error, status.message)
            RoutingStatus.NoRoute -> carContext.getString(R.string.no_route)
            RoutingStatus.Offline -> carContext.getString(R.string.route_offline)
            RoutingStatus.Idle,
            RoutingStatus.Loading -> null
          }
      if (error == null) {
        return RoutePreviewNavigationTemplate.Builder()
            .setTitle(title)
            .setHeaderAction(Action.BACK)
            .setLoading(true)
            .build()
      }
      val cancel =
          Action.Builder()
              .setTitle(carContext.getString(R.string.cancel))
              .setOnClickListener { viewModel.clearDestination() }
              .build()
      return NavigationTemplate.Builder()
          .setNavigationInfo(MessageInfo.Builder(title).setText(error).build())
          .setActionStrip(ActionStrip.Builder().addAction(start).addAction(cancel).build())
          .setMapActionStrip(ActionStrip.Builder().addAction(Action.PAN).build())
          .build()
    }

    val routes =
        ItemList.Builder()
            .apply {
              preview.options.forEach { option ->
                val summary =
                    listOfNotNull(
                            formatTripDistance(option.route.distance),
                            preview.stops.size.takeIf { it > 0 }?.let {
                              carContext.resources.getQuantityString(R.plurals.route_stops, it, it)
                            },
                            option.via?.let { carContext.getString(R.string.route_via, it) },
                        )
                        .joinToString(" · ")
                addItem(
                    Row.Builder()
                        .setTitle(formatDuration(carContext, option.durationSeconds.toLong()))
                        .addText(summary)
                        .apply {
                          if (option.trafficEvents > 0) {
                            addText(
                                carContext.resources.getQuantityString(
                                    R.plurals.route_traffic_events,
                                    option.trafficEvents,
                                    option.trafficEvents,
                                )
                            )
                          }
                        }
                        .build()
                )
              }
            }
            .setSelectedIndex(preview.selectedIndex)
            .setOnSelectedListener { viewModel.selectRoute(it) }
            .build()
    return RoutePreviewNavigationTemplate.Builder()
        .setTitle(title)
        .setHeaderAction(Action.BACK)
        .setItemList(routes)
        .setNavigateAction(start)
        .setMapActionStrip(ActionStrip.Builder().addAction(Action.PAN).build())
        .build()
  }

  /**
   * Ferrostar's navigation template with a "Stops on the way" action added to its action strip;
   * its builder has no way to add one.
   */
  private fun withStopsAction(template: Template): Template {
    val navigation = template as? NavigationTemplate ?: return template
    val actions = navigation.actionStrip?.actions.orEmpty()
    if (actions.size >= MAX_STRIP_ACTIONS) return template
    val stops =
        Action.Builder()
            .setIcon(CarIcon.Builder(IconCompat.createWithResource(carContext, R.drawable.ic_fuel)).build())
            .setOnClickListener { screenManager.push(CarStopsScreen(carContext)) }
            .build()
    return NavigationTemplate.Builder()
        .apply {
          navigation.navigationInfo?.let { setNavigationInfo(it) }
          navigation.destinationTravelEstimate?.let { setDestinationTravelEstimate(it) }
          navigation.mapActionStrip?.let { setMapActionStrip(it) }
          setActionStrip(ActionStrip.Builder().apply { (listOf(stops) + actions).forEach(::addAction) }.build())
        }
        .build()
  }

  /** "You have arrived" with the destination and trip summary, until the driver taps Done. */
  private fun arrivalTemplate(arrival: Arrival): Template {
    val flag =
        CarIcon.Builder(IconCompat.createWithResource(carContext, R.drawable.ic_flag))
            .setTint(CarColor.GREEN)
            .build()
    val details =
        listOfNotNull(arrival.destination?.label, arrivalSummary(carContext, arrival))
            .joinToString("\n")
    val done =
        Action.Builder()
            .setTitle(carContext.getString(R.string.done))
            .setOnClickListener { viewModel.dismissArrival() }
            .build()

    return NavigationTemplate.Builder()
        .setNavigationInfo(
            MessageInfo.Builder(carContext.getString(R.string.arrived_title))
                .setText(details)
                .setImage(flag)
                .build()
        )
        .setActionStrip(ActionStrip.Builder().addAction(done).build())
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
              warning.camera.maxSpeedKmh?.let {
                setSubtitle(CarText.create("${displayedSpeedLimit(it, AppConfig.usesMetricUnits)}"))
              }
            }
            .build()
    carContext.getCarService(AppManager::class.java).showAlert(alert)
  }

  /** Shows a traffic warning (e.g. "Roadworks in 1.5 km") as a car alert (Car App API 5+). */
  private fun showTrafficAlert(warning: TrafficWarning) {
    if (carContext.carAppApiLevel < 5) return
    val title =
        CarText.create(
            carContext.getString(
                R.string.traffic_ahead_in,
                carContext.getString(warning.event.kind.labelRes()),
                formatDistance(warning.distanceMetres),
            )
        )
    val icon = CarIcon.Builder(IconCompat.createWithResource(carContext, R.drawable.ic_traffic)).build()
    val alert =
        Alert.Builder(warning.event.id.hashCode(), title, CAMERA_ALERT_DURATION_MS)
            .setIcon(icon)
            .apply { warning.event.restrictions.firstOrNull()?.let { setSubtitle(CarText.create(it)) } }
            .build()
    carContext.getCarService(AppManager::class.java).showAlert(alert)
  }

  private companion object {
    const val CAMERA_ALERT_DURATION_MS = 8_000L
    /** The most actions a navigation template's action strip holds. */
    const val MAX_STRIP_ACTIONS = 4
  }
}
