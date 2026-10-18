package com.joegineered.ontheroad.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.zIndex
import androidx.compose.ui.focus.FocusRequester
import org.maplibre.compose.util.MaplibreComposable
import androidx.compose.ui.layout.boundsInParent
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.layout
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.offset
import com.stadiamaps.ferrostar.ui.formatters.LocalizedDistanceFormatter
import com.stadiamaps.ferrostar.composeui.config.withInstructionsView
import com.stadiamaps.ferrostar.composeui.views.components.InstructionsView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.joegineered.ontheroad.AppGraph
import com.joegineered.ontheroad.BuildConfig
import com.joegineered.ontheroad.R
import com.joegineered.ontheroad.config.AppConfig
import com.joegineered.ontheroad.offline.OfflineAreaKind
import com.joegineered.ontheroad.cameras.LatLng
import com.joegineered.ontheroad.chargers.ChargingStation
import com.joegineered.ontheroad.chargers.SocketType
import com.joegineered.ontheroad.nav.Destination
import com.joegineered.ontheroad.nav.OtrNavigationViewModel
import com.joegineered.ontheroad.nav.RoutingStatus
import com.joegineered.ontheroad.settings.ThemeMode
import com.stadiamaps.ferrostar.composeui.config.NavigationViewComponentBuilder
import com.stadiamaps.ferrostar.composeui.config.VisualNavigationViewConfig
import com.stadiamaps.ferrostar.composeui.config.withCustomOverlayView
import com.stadiamaps.ferrostar.composeui.config.withProgressView
import com.stadiamaps.ferrostar.composeui.theme.DefaultNavigationUITheme
import com.stadiamaps.ferrostar.composeui.views.components.TripProgressView
import com.stadiamaps.ferrostar.composeui.config.withSpeedLimitStyle
import com.stadiamaps.ferrostar.composeui.runtime.KeepScreenOnDisposableEffect
import com.stadiamaps.ferrostar.composeui.views.components.controls.NavigationUIButton
import com.stadiamaps.ferrostar.composeui.views.components.speedlimit.SignageStyle
import com.stadiamaps.ferrostar.maplibreui.NavigationMapClickResult
import com.stadiamaps.ferrostar.maplibreui.runtime.NavigationCameraMode
import com.stadiamaps.ferrostar.maplibreui.runtime.NavigationMapState
import com.stadiamaps.ferrostar.maplibreui.runtime.rememberNavigationMapState
import com.stadiamaps.ferrostar.maplibreui.views.DynamicallyOrientingNavigationView
import java.util.Locale
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import uniffi.ferrostar.GeographicCoordinate
import kotlin.time.Duration.Companion.milliseconds
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.style.BaseStyle
import org.maplibre.spatialk.geojson.Position

/** Speed limit signs: the US/Canada style in North America, Vienna Convention elsewhere. */
fun signageStyle(): SignageStyle =
    if (Locale.getDefault().country in setOf("US", "CA")) SignageStyle.MUTCD
    else SignageStyle.ViennaConvention

@OptIn(FlowPreview::class)
@Composable
fun MainScreen(viewModel: OtrNavigationViewModel = AppGraph.navigationViewModel) {
  KeepScreenOnDisposableEffect()
  LocationPermissionEffect(viewModel)
  LaunchedEffect(Unit) {
    AppGraph.speedCameras.start()
    AppGraph.roadsideStops.start()
    AppGraph.startTraffic()
    AppGraph.offlineMaps.start()
  }

  val uiState by viewModel.navigationUiState.collectAsState()
  val destination by viewModel.selectedDestination.collectAsState()
  val arrival by viewModel.arrival.collectAsState()
  val cameras by AppGraph.speedCameras.cameras.collectAsState()
  val stops by AppGraph.roadsideStops.state.collectAsState()
  val night by AppGraph.nightMode.isNight.collectAsState()
  val showChargers by AppGraph.settings.showChargers.collectAsState(initial = true)
  val chargers by AppGraph.chargers.visible.collectAsState()
  val liveTraffic by AppGraph.settings.liveTrafficEnabled.collectAsState(initial = true)
  val trafficEvents by AppGraph.traffic.events.collectAsState()
  val slowTraffic by AppGraph.traffic.slowTraffic.collectAsState()
  val navigationMapState = rememberNavigationMapState()
  ChargerViewportEffect(navigationMapState, enabled = showChargers)
  var settingsPage by remember { mutableStateOf<SettingsPage?>(null) }
  var pickingOfflineArea by remember { mutableStateOf(false) }
  val offlineAreas by AppGraph.offlineMaps.areas.collectAsState()
  // A trip started from the car ends picking.
  LaunchedEffect(uiState.isNavigating()) { if (uiState.isNavigating()) pickingOfflineArea = false }
  // Bottom-left corner of the turn banner (root coordinates), to place the road signs below it.
  val bannerAnchor = remember { mutableStateOf<Offset?>(null) }
  val plugTypes by AppGraph.settings.chargerPlugTypes.collectAsState(initial = SocketType.entries.toSet())
  val preview by viewModel.preview.collectAsState()
  // The map between the search bar and the cards (root coordinates), where routes are framed.
  var searchBottom by remember { mutableStateOf(0.dp) }
  var cardsTop by remember { mutableStateOf(0.dp) }
  val landscape = isLandscape()
  // Frames all the routes offered, clear of the search bar, map buttons and cards. Waits for the
  // card listing the routes to settle, so the fit uses its final size.
  val systemBars = WindowInsets.systemBars.asPaddingValues()
  val density = LocalDensity.current
  val window = LocalWindowInfo.current.containerSize
  LaunchedEffect(preview?.options) {
    val routes = preview?.options ?: return@LaunchedEffect
    val points = routes.flatMap { it.route.geometry }.ifEmpty { return@LaunchedEffect }
    snapshotFlow { searchBottom to cardsTop }
        .debounce(150)
        .distinctUntilChanged()
        .collectLatest { (search, cards) ->
          val (width, height) = with(density) { window.width.toDp() to window.height.toDp() }
          val padding =
              if (landscape) {
                PaddingValues(
                    start = LandscapePanelWidth + 32.dp,
                    top = systemBars.calculateTopPadding() + 32.dp,
                    end = 80.dp,
                    bottom = systemBars.calculateBottomPadding() + 32.dp,
                )
              } else {
                PaddingValues(
                    start = 32.dp,
                    top = search + 24.dp,
                    end = 80.dp,
                    bottom = (height - cards + 24.dp).coerceAtLeast(24.dp),
                )
              }
          val fit =
              fitBounds(
                  south = points.minOf { it.lat },
                  west = points.minOf { it.lng },
                  north = points.maxOf { it.lat },
                  east = points.maxOf { it.lng },
                  widthDp =
                      (width - padding.calculateLeftPadding(LayoutDirection.Ltr) -
                              padding.calculateRightPadding(LayoutDirection.Ltr))
                          .value
                          .toDouble(),
                  heightDp = (height - padding.calculateTopPadding() - padding.calculateBottomPadding()).value.toDouble(),
              )
          navigationMapState.cameraMode = NavigationCameraMode.FREE
          navigationMapState.cameraState.animateTo(
              finalPosition =
                  CameraPosition(
                      target = Position(longitude = fit.lng, latitude = fit.lat),
                      zoom = fit.zoom,
                      padding = padding,
                  ),
              duration = 1000.milliseconds,
          )
        }
  }
  val metric = AppConfig.unitSystem.collectAsState().value.isMetric()
  val distanceFormatter =
      remember(metric) {
        LocalizedDistanceFormatter(
            distanceMeasurementSystemOverride = AppConfig.distanceMeasurementSystem(metric)
        )
      }

  Box(Modifier.fillMaxSize()) {
    DynamicallyOrientingNavigationView(
        modifier = Modifier.fillMaxSize(),
        baseStyle = BaseStyle.Uri(AppConfig.mapStyleUrl(night)),
        navigationMapState = navigationMapState,
        viewModel = viewModel,
        // The speed limit sign is drawn by RoadSigns (with roadworks and road weather signs beside it).
        config = VisualNavigationViewConfig.Default(),
        views =
            NavigationViewComponentBuilder.Default()
                .withInstructionsView { modifier, state ->
                  state.visualInstruction?.let { instructions ->
                    InstructionsView(
                        modifier =
                            modifier.onGloballyPositioned {
                              val bounds = it.boundsInRoot()
                              bannerAnchor.value = Offset(bounds.left, bounds.bottom)
                            },
                        instructions = instructions,
                        distanceFormatter = distanceFormatter,
                        theme = DefaultNavigationUITheme.instructionRowTheme,
                        remainingSteps = state.remainingSteps,
                        distanceToNextManeuver = state.progress?.distanceToNextManeuver,
                    )
                  }
                }
                // Current speed sits just above the trip progress bar, bottom left.
                .withProgressView { modifier, state, onTapExit ->
                  state.progress?.let { progress ->
                    val speed by viewModel.speedMetresPerSecond.collectAsState()
                    val signs by AppGraph.variableSigns.state.collectAsState()
                    Column(modifier) {
                      Speedometer(
                          reading =
                              speedReading(
                                  speed,
                                  effectiveSpeedLimit(state.currentAnnotation?.speedLimit, signs.speedLimitKmh),
                                  metric,
                              ),
                          metric = metric,
                          // Aligned with the left edge of the banner, signs and progress bar.
                        modifier = Modifier.padding(bottom = 8.dp),
                      )
                      TripProgressView(
                          theme = DefaultNavigationUITheme.tripProgressViewTheme,
                          distanceFormatter = distanceFormatter,
                          progress = progress,
                          onTapExit = onTapExit,
                      )
                    }
                  }
                }
                .withCustomOverlayView { modifier ->
                  if (uiState.isNavigating()) {
                    NavigatingOverlay(modifier, bannerAnchor.value)
                  } else if (pickingOfflineArea) {
                    OfflineAreaPicker(modifier, navigationMapState) { downloaded ->
                      pickingOfflineArea = false
                      // Back to the list, which shows the download's progress.
                      if (downloaded) settingsPage = SettingsPage.OFFLINE_MAPS
                    }
                  } else {
                    BrowsingOverlay(
                        modifier,
                        viewModel,
                        navigationMapState,
                        onOpenSettings = { settingsPage = SettingsPage.MAIN },
                        onSearchBottomChanged = { searchBottom = it },
                        onCardsTopChanged = { cardsTop = it },
                    )
                  }
                },
        onTapExit = { viewModel.stopNavigation() },
        onMapLongClick = { coordinate, _ ->
          if (!uiState.isNavigating()) viewModel.selectDestination(coordinate)
          NavigationMapClickResult.Consume
        },
    ) { _ ->
      if (liveTraffic) TrafficLayer(trafficEvents, slowTraffic, onClick = AppGraph.traffic::selectById)
      if (showChargers) {
        ChargerLayer(chargers.filter { it.matches(plugTypes) }, onClick = AppGraph.chargers::select)
      }
      SpeedCameraLayer(cameras)
      if (uiState.isNavigating()) StopsLayer(stops)
      if (!uiState.isNavigating()) RoutePreviewLayer(preview, onSelect = viewModel::selectRoute)
      StopPinLayerFor(uiState.isNavigating(), viewModel)
      DestinationPinLayer(destination?.coordinate ?: arrival?.destination?.coordinate)
      if (pickingOfflineArea && !uiState.isNavigating()) {
        // One pack per area: the day style's, as the night pack covers the same area.
        val packs =
            offlineAreas
                .filter { it.info.kind == OfflineAreaKind.AREA }
                .flatMap { area -> area.newest.map { it.pack }.filter { it.definition.styleUrl == AppConfig.MAP_STYLE_URL } }
                .toSet()
        OfflineAreasLayer(packs)
      }
    }

    settingsPage?.let { page ->
      SettingsScreen(
          page,
          onNavigate = { settingsPage = it },
          onPickOfflineArea = {
            settingsPage = null
            pickingOfflineArea = true
          },
      )
    }
  }
}

@Composable
private fun NavigatingOverlay(modifier: Modifier, bannerAnchor: Offset?) {
  val warning by AppGraph.speedCameras.activeWarning.collectAsState()
  val selectedCharger by AppGraph.chargers.selected.collectAsState()
  val location by AppGraph.navigationViewModel.location.collectAsState()
  val trafficWarning by AppGraph.traffic.activeWarning.collectAsState()
  val selectedTraffic by AppGraph.traffic.selected.collectAsState()
  val roadworks by AppGraph.traffic.currentRoadworks.collectAsState()
  val roadWeather by AppGraph.traffic.currentRoadWeather.collectAsState()
  val variableSigns by AppGraph.variableSigns.state.collectAsState()
  val uiState by AppGraph.navigationViewModel.navigationUiState.collectAsState()
  val stopSearch by AppGraph.roadsideStops.state.collectAsState()
  val remainingStops by AppGraph.navigationViewModel.remainingStops.collectAsState()
  var showStops by remember { mutableStateOf(false) }
  val context = LocalContext.current
  var overlayOrigin by remember { mutableStateOf(Offset.Zero) }
  val landscape = isLandscape()
  Box(modifier.fillMaxSize().onGloballyPositioned { overlayOrigin = it.boundsInRoot().topLeft }) {
    // Where Ferrostar would put its speed limit sign: under the banner, aligned with its edge.
    bannerAnchor?.let { anchor ->
      RoadSigns(
          speedLimit = effectiveSpeedLimit(uiState.currentAnnotation?.speedLimit, variableSigns.speedLimitKmh),
          roadworks = roadworks != null,
          roadWeather = roadWeather,
          warningCodes = variableSigns.warningCodes,
          modifier =
              Modifier.offset {
                val gap = 16.dp.roundToPx()
                IntOffset(
                    (anchor.x - overlayOrigin.x).toInt(),
                    (anchor.y - overlayOrigin.y).toInt() + gap,
                )
              },
      )
    }

    // Portrait: left edge, mid-height, between the banner and the trip progress bar.
    // Landscape: Ferrostar's banner, signs and progress fill the left half, so use the right half.
    val badgeModifier =
        if (landscape) Modifier.align(Alignment.TopStart).statusBarsPadding().padding(top = 16.dp).offsetToRightHalf()
        else Modifier.align(Alignment.CenterStart).padding(start = 16.dp)
    Column(badgeModifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
      OfflineIndicator()
      PositionEstimatedIndicator()
      warning?.let { CameraWarningBadge(it) }
      trafficWarning?.let { TrafficWarningBadge(it) }
      variableSigns.message?.let { VariableMessageSign(it) }
    }

    // Cards: above the trip progress bar in portrait; bottom of the right half in landscape,
    // clear of the map buttons on the right edge.
    val cardModifier =
        if (landscape) {
          Modifier.align(Alignment.BottomEnd).fillMaxWidth(0.5f).padding(start = 16.dp, end = 96.dp, bottom = 16.dp)
        } else {
          Modifier.align(Alignment.BottomCenter).padding(start = 16.dp, end = 16.dp, bottom = 120.dp)
        }
    // Right edge, under Ferrostar's overview and mute buttons (which start just below the banner)
    // and above its zoom buttons.
    if (!showStops) {
      val top = bannerAnchor?.let { with(LocalDensity.current) { (it.y - overlayOrigin.y).toDp() } } ?: 0.dp
      Box(Modifier.align(Alignment.TopEnd).padding(top = top + StopsButtonGap)) {
        NavigationUIButton(onClick = { showStops = true }, buttonSize = DpSize(56.dp, 56.dp)) {
          Icon(painterResource(R.drawable.ic_fuel), stringResource(R.string.stops_on_the_way))
        }
      }
    }

    selectedCharger?.let { station ->
      ChargerCard(
          station = station,
          distanceMetres = location?.coordinates?.let { LatLng(it.lat, it.lng).distanceTo(station) },
          onNavigate = { navigateToCharger(station) },
          onClose = { AppGraph.chargers.clearSelection() },
          modifier = cardModifier,
      )
    }
    if (showStops && selectedCharger == null) {
      StopsAlongRouteCard(
          search = stopSearch,
          tripStops = remainingStops,
          onSkipStop = AppGraph.navigationViewModel::skipStop,
          onSearch = AppGraph.roadsideStops::search,
          onSelect = { stop ->
            showStops = false
            AppGraph.roadsideStops.clear()
            AppGraph.navigationViewModel.stopOnTheWay(stop.toDestination(context))
          },
          onClose = {
            showStops = false
            AppGraph.roadsideStops.clear()
          },
          modifier = cardModifier,
          maxListHeight = if (landscape) 140.dp else 240.dp,
      )
    }
    selectedTraffic?.let { item ->
      TrafficCard(item, onClose = { AppGraph.traffic.select(null) }, modifier = cardModifier)
    }
  }
}

/**
 * The highest the cards reach in portrait: below the search bar (about 80 dp) and the two map
 * buttons (108 dp), with gaps.
 */
private val CardsTopPortrait = 220.dp

/** From the banner's bottom to the stops button: past Ferrostar's two buttons (56 dp + gap). */
private val StopsButtonGap = 160.dp

/** Shifts content to start at the middle of the screen (plus a gap), for landscape layouts. */
private fun Modifier.offsetToRightHalf(): Modifier =
    this.layout { measurable, constraints ->
      val placeable = measurable.measure(constraints.copy(minWidth = 0))
      layout(placeable.width, placeable.height) {
        placeable.place(constraints.maxWidth / 2 + 16.dp.roundToPx(), 0)
      }
    }

@Composable
private fun BrowsingOverlay(
    modifier: Modifier,
    viewModel: OtrNavigationViewModel,
    navigationMapState: NavigationMapState,
    onOpenSettings: () -> Unit,
    onSearchBottomChanged: (Dp) -> Unit,
    onCardsTopChanged: (Dp) -> Unit,
) {
  val destination by viewModel.selectedDestination.collectAsState()
  val preview by viewModel.preview.collectAsState()
  val arrival by viewModel.arrival.collectAsState()
  val routingStatus by viewModel.routingStatus.collectAsState()
  val location by viewModel.location.collectAsState()

  val plannedStops by viewModel.plannedStops.collectAsState()
  val scope = rememberCoroutineScope()
  var searching by remember { mutableStateOf(false) }
  // "Add stop" focuses the search bar; the next place picked is added as a stop.
  var addingStop by remember { mutableStateOf(false) }
  val searchFocus = remember { FocusRequester() }
  var showNearbyChargers by remember { mutableStateOf(false) }
  val selectedCharger by AppGraph.chargers.selected.collectAsState()
  val selectedTraffic by AppGraph.traffic.selected.collectAsState()
  val here = location?.coordinates?.let { LatLng(it.lat, it.lng) }
  // Landscape: search and cards share a side panel on the left, leaving the map visible.
  val landscape = isLandscape()
  val density = LocalDensity.current
  val panelWidth = if (landscape) Modifier.width(LandscapePanelWidth) else Modifier.fillMaxWidth()
  // Where the search bar ends and the cards begin, in this box, to keep the map buttons between
  // them (portrait); and the sizes of the box and the buttons.
  var searchBottomPx by remember { mutableStateOf(0) }
  var cardsTopPx by remember { mutableStateOf(Int.MAX_VALUE) }
  var boxHeightPx by remember { mutableStateOf(0) }
  var buttonsHeightPx by remember { mutableStateOf(0) }

  Box(modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().onSizeChanged { boxHeightPx = it.height }) {
    SearchPanel(
        onPlaceSelected = { place ->
          if (addingStop && destination != null) {
            // The routes are framed again once planned, so the map doesn't move to the stop.
            addingStop = false
            viewModel.addStop(Destination.from(place))
          } else {
            viewModel.selectDestination(Destination.from(place))
            scope.launch { navigationMapState.showDestination(place.lat, place.lng, landscape) }
          }
        },
        modifier =
            Modifier.align(Alignment.TopStart)
                // Its results list drops down over the cards.
                .zIndex(1f)
                .padding(16.dp)
                .then(panelWidth)
                .onGloballyPositioned {
                  searchBottomPx = it.boundsInParent().bottom.toInt()
                  onSearchBottomChanged(with(density) { it.boundsInRoot().bottom.toDp() })
                },
        maxListHeight = if (landscape) 200.dp else 320.dp,
        // Down to near the bottom of the screen: below the search bar and its margins.
        maxListHeightWithoutKeyboard = with(density) { boxHeightPx.toDp() } - 120.dp,
        onActiveChanged = {
          searching = it
          if (!it) addingStop = false
        },
        onChargersNearby = { showNearbyChargers = true },
        focusRequester = searchFocus,
        hint = stringResource(if (addingStop) R.string.search_stop_hint else R.string.search_hint),
    )

    // Hidden while searching so they don't cover the results list. Mid-height on the right, or
    // just above the cards when they reach higher (portrait; landscape cards stay on the left).
    if (!searching) Column(
        modifier =
            Modifier.align(Alignment.TopEnd)
                .padding(end = 16.dp)
                .onSizeChanged { buttonsHeightPx = it.height }
                .offset {
                  val gap = 16.dp.roundToPx()
                  val centred = (boxHeightPx - buttonsHeightPx) / 2
                  val y =
                      if (landscape) centred
                      else centred.coerceAtMost(cardsTopPx - gap - buttonsHeightPx).coerceAtLeast(searchBottomPx + gap)
                  IntOffset(0, y)
                },
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      NavigationUIButton(onClick = onOpenSettings, buttonSize = DpSize(48.dp, 48.dp)) {
        Icon(
            painter = painterResource(R.drawable.ic_tune),
            contentDescription = stringResource(R.string.settings_menu),
        )
      }
      NavigationUIButton(
          onClick = { navigationMapState.recenter(isNavigating = false) },
          buttonSize = DpSize(48.dp, 48.dp),
      ) {
        Icon(
            painter = painterResource(R.drawable.ic_my_location),
            contentDescription = stringResource(R.string.center_on_my_location),
        )
      }
    }

    // Kept below the search bar and scrollable, so stacked cards never run off a short screen. In
    // portrait they also leave room for the map buttons under the search bar.
    Column(
        modifier =
            Modifier.align(Alignment.BottomStart)
                .padding(start = 16.dp, end = 16.dp, bottom = 16.dp, top = if (landscape) 96.dp else CardsTopPortrait)
                .onGloballyPositioned {
                  cardsTopPx = it.boundsInParent().top.toInt()
                  onCardsTopChanged(with(density) { it.boundsInRoot().top.toDp() })
                }
                .then(panelWidth)
                .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      OfflineIndicator()
      arrival?.let { ArrivalCard(it, onDone = { viewModel.dismissArrival() }) }

      if (showNearbyChargers && selectedCharger == null) {
        NearbyChargersCard(
            position = here,
            onSelect = { station ->
              AppGraph.chargers.select(station)
              showNearbyChargers = false
              scope.launch {
                navigationMapState.showDestination(station.position.lat, station.position.lng, landscape)
              }
            },
            onClose = { showNearbyChargers = false },
        )
      }

      selectedTraffic?.let { item -> TrafficCard(item, onClose = { AppGraph.traffic.select(null) }) }

      selectedCharger?.let { station ->
        ChargerCard(
            station = station,
            distanceMetres = here?.distanceTo(station),
            onNavigate = { navigateToCharger(station) },
            onClose = { AppGraph.chargers.clearSelection() },
        )
      }

      if (location == null) {
        Card(Modifier.fillMaxWidth()) {
          Text(stringResource(R.string.no_location_yet), Modifier.padding(16.dp))
        }
      }

      destination?.let { selected ->
        DestinationCard(
            destination = selected,
            stops = plannedStops,
            preview = preview,
            routingStatus = routingStatus,
            hasLocation = location != null,
            onSelectRoute = viewModel::selectRoute,
            onAddStop = {
              addingStop = true
              searchFocus.requestFocus()
            },
            onRemoveStop = viewModel::removeStop,
            onStart = { viewModel.startSelectedDestination() },
            onCancel = { viewModel.clearDestination() },
        )
      }
    }
  }
}

/** Pins for the stops on the way: those planned while previewing, or those still ahead. */
@Composable
@MaplibreComposable
private fun StopPinLayerFor(navigating: Boolean, viewModel: OtrNavigationViewModel) {
  val planned by viewModel.plannedStops.collectAsState()
  val remaining by viewModel.remainingStops.collectAsState()
  StopPinsLayer((if (navigating) remaining else planned).map { it.coordinate })
}

/** Starts navigating to a charger; during a trip, as a stop on the way to its destination. */
private fun navigateToCharger(station: ChargingStation) {
  AppGraph.chargers.clearSelection()
  AppGraph.navigationViewModel.stopOnTheWay(
      Destination(
          GeographicCoordinate(station.position.lat, station.position.lng),
          label = station.title,
          subtitle = station.headline,
      )
  )
}

/** Loads chargers for the visible map area whenever the camera stops moving. */
@OptIn(FlowPreview::class)
@Composable
private fun ChargerViewportEffect(mapState: NavigationMapState, enabled: Boolean) {
  LaunchedEffect(mapState, enabled) {
    if (!enabled) return@LaunchedEffect
    // Re-emits when the camera moves and once the map is laid out (projection available).
    snapshotFlow {
          val position = mapState.cameraState.position
          mapState.cameraState.projection?.queryVisibleBoundingBox()?.let { position.zoom to it }
        }
        .filterNotNull()
        .debounce(300)
        .collect { (zoom, bounds) ->
          AppGraph.chargers.onViewportChanged(bounds.south, bounds.west, bounds.north, bounds.east, zoom)
        }
  }
}

/** Moves the map to a chosen destination, leaving room for the destination card below it. */
private suspend fun NavigationMapState.showDestination(lat: Double, lng: Double, landscape: Boolean) {
  cameraMode = NavigationCameraMode.FREE
  cameraState.animateTo(
      finalPosition =
          CameraPosition(
              target = Position(longitude = lng, latitude = lat),
              zoom = 15.5,
              // Keep the place clear of the cards: below them in portrait, beside them in landscape.
              padding =
                  if (landscape) PaddingValues(start = LandscapePanelWidth + 32.dp)
                  else PaddingValues(bottom = 240.dp),
          ),
      duration = 1200.milliseconds,
  )
}

@Composable
private fun LocationPermissionEffect(viewModel: OtrNavigationViewModel) {
  val context = LocalContext.current
  val permissions =
      buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
              add(Manifest.permission.POST_NOTIFICATIONS)
            }
          }
          .toTypedArray()

  val launcher =
      rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
          result ->
        viewModel.setLocationPermission(result[Manifest.permission.ACCESS_FINE_LOCATION] == true)
      }

  LaunchedEffect(Unit) {
    val granted =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
    if (granted) viewModel.setLocationPermission(true) else launcher.launch(permissions)
  }
}
