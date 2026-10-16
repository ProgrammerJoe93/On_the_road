package com.joegineered.ontheroad.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.foundation.layout.offset
import com.stadiamaps.ferrostar.composeui.config.withInstructionsView
import com.stadiamaps.ferrostar.composeui.views.components.InstructionsView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.joegineered.ontheroad.AppGraph
import com.joegineered.ontheroad.BuildConfig
import com.joegineered.ontheroad.R
import com.joegineered.ontheroad.config.AppConfig
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
import kotlinx.coroutines.flow.debounce
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

@Composable
fun MainScreen(viewModel: OtrNavigationViewModel = AppGraph.navigationViewModel) {
  KeepScreenOnDisposableEffect()
  LocationPermissionEffect(viewModel)
  LaunchedEffect(Unit) {
    AppGraph.speedCameras.start()
    AppGraph.startTraffic()
  }

  val uiState by viewModel.navigationUiState.collectAsState()
  val destination by viewModel.selectedDestination.collectAsState()
  val arrival by viewModel.arrival.collectAsState()
  val cameras by AppGraph.speedCameras.cameras.collectAsState()
  val night by AppGraph.nightMode.isNight.collectAsState()
  val showChargers by AppGraph.settings.showChargers.collectAsState(initial = true)
  val chargers by AppGraph.chargers.visible.collectAsState()
  val liveTraffic by AppGraph.settings.liveTrafficEnabled.collectAsState(initial = true)
  val trafficEvents by AppGraph.traffic.events.collectAsState()
  val slowTraffic by AppGraph.traffic.slowTraffic.collectAsState()
  val navigationMapState = rememberNavigationMapState()
  ChargerViewportEffect(navigationMapState, enabled = showChargers)
  var settingsPage by remember { mutableStateOf<SettingsPage?>(null) }
  // Bottom edge of the turn banner (root coordinates), to place the road signs right below it.
  val bannerBottom = remember { mutableStateOf<Float?>(null) }
  val plugTypes by AppGraph.settings.chargerPlugTypes.collectAsState(initial = SocketType.entries.toSet())

  Box(Modifier.fillMaxSize()) {
    DynamicallyOrientingNavigationView(
        modifier = Modifier.fillMaxSize(),
        baseStyle = BaseStyle.Uri(AppConfig.mapStyleUrl(night)),
        navigationMapState = navigationMapState,
        viewModel = viewModel,
        // The speed limit sign is drawn by RoadSigns (with the roadworks sign beside it).
        config = VisualNavigationViewConfig.Default(),
        views =
            NavigationViewComponentBuilder.Default()
                .withInstructionsView { modifier, state ->
                  state.visualInstruction?.let { instructions ->
                    InstructionsView(
                        modifier =
                            modifier.onGloballyPositioned { bannerBottom.value = it.boundsInRoot().bottom },
                        instructions = instructions,
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
                    Column(modifier) {
                      Speedometer(
                          reading =
                              speedReading(
                                  speed,
                                  state.currentAnnotation?.speedLimit,
                                  AppConfig.usesMetricUnits,
                              ),
                          metric = AppConfig.usesMetricUnits,
                          modifier = Modifier.padding(start = 16.dp, bottom = 8.dp),
                      )
                      TripProgressView(
                          theme = DefaultNavigationUITheme.tripProgressViewTheme,
                          progress = progress,
                          onTapExit = onTapExit,
                      )
                    }
                  }
                }
                .withCustomOverlayView { modifier ->
                  if (uiState.isNavigating()) {
                    NavigatingOverlay(modifier, bannerBottom.value)
                  } else {
                    BrowsingOverlay(
                        modifier,
                        viewModel,
                        navigationMapState,
                        onOpenSettings = { settingsPage = SettingsPage.MAIN },
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
      DestinationPinLayer(destination?.coordinate ?: arrival?.destination?.coordinate)
    }

    settingsPage?.let { page -> SettingsScreen(page, onNavigate = { settingsPage = it }) }
  }
}

@Composable
private fun NavigatingOverlay(modifier: Modifier, bannerBottom: Float?) {
  val warning by AppGraph.speedCameras.activeWarning.collectAsState()
  val selectedCharger by AppGraph.chargers.selected.collectAsState()
  val location by AppGraph.navigationViewModel.location.collectAsState()
  val trafficWarning by AppGraph.traffic.activeWarning.collectAsState()
  val selectedTraffic by AppGraph.traffic.selected.collectAsState()
  val roadworks by AppGraph.traffic.currentRoadworks.collectAsState()
  val uiState by AppGraph.navigationViewModel.navigationUiState.collectAsState()
  var overlayTop by remember { mutableStateOf(0f) }
  val density = LocalDensity.current
  Box(modifier.fillMaxSize().onGloballyPositioned { overlayTop = it.boundsInRoot().top }) {
    // Where Ferrostar would put its speed limit sign: under the banner, 16 dp in.
    bannerBottom?.let { bottom ->
      RoadSigns(
          speedLimit = uiState.currentAnnotation?.speedLimit,
          roadworks = roadworks != null,
          modifier =
              Modifier.offset {
                val gap = with(density) { 16.dp.roundToPx() }
                IntOffset(gap, (bottom - overlayTop).toInt() + gap)
              },
      )
    }
    selectedCharger?.let { station ->
      ChargerCard(
          station = station,
          distanceMetres = location?.coordinates?.let { LatLng(it.lat, it.lng).distanceTo(station) },
          onNavigate = { navigateToCharger(station) },
          onClose = { AppGraph.chargers.clearSelection() },
          // Above the trip progress bar.
          modifier = Modifier.align(Alignment.BottomCenter).padding(start = 16.dp, end = 16.dp, bottom = 120.dp),
      )
    }
    // Left edge, mid-height: clear of the instruction banner (top) and trip progress (bottom).
    Column(
        Modifier.align(Alignment.CenterStart).padding(start = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      warning?.let { CameraWarningBadge(it) }
      trafficWarning?.let { TrafficWarningBadge(it) }
    }
    selectedTraffic?.let { item ->
      TrafficCard(
          item,
          onClose = { AppGraph.traffic.select(null) },
          modifier = Modifier.align(Alignment.BottomCenter).padding(start = 16.dp, end = 16.dp, bottom = 120.dp),
      )
    }
  }
}

@Composable
private fun BrowsingOverlay(
    modifier: Modifier,
    viewModel: OtrNavigationViewModel,
    navigationMapState: NavigationMapState,
    onOpenSettings: () -> Unit,
) {
  val destination by viewModel.selectedDestination.collectAsState()
  val arrival by viewModel.arrival.collectAsState()
  val routingStatus by viewModel.routingStatus.collectAsState()
  val location by viewModel.location.collectAsState()

  val scope = rememberCoroutineScope()
  var searching by remember { mutableStateOf(false) }
  var showNearbyChargers by remember { mutableStateOf(false) }
  val selectedCharger by AppGraph.chargers.selected.collectAsState()
  val selectedTraffic by AppGraph.traffic.selected.collectAsState()
  val here = location?.coordinates?.let { LatLng(it.lat, it.lng) }

  Box(modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
    SearchPanel(
        onPlaceSelected = { place ->
          viewModel.selectDestination(Destination.from(place))
          scope.launch { navigationMapState.showDestination(place.lat, place.lng) }
        },
        modifier = Modifier.align(Alignment.TopCenter).padding(16.dp),
        onActiveChanged = { searching = it },
        onChargersNearby = { showNearbyChargers = true },
    )

    // Hidden while searching so they don't cover the results list.
    if (!searching) Column(
        modifier = Modifier.align(Alignment.CenterEnd).padding(end = 16.dp),
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

    Column(
        modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      arrival?.let { ArrivalCard(it, onDone = { viewModel.dismissArrival() }) }

      if (showNearbyChargers && selectedCharger == null) {
        NearbyChargersCard(
            position = here,
            onSelect = { station ->
              AppGraph.chargers.select(station)
              showNearbyChargers = false
              scope.launch {
                navigationMapState.showDestination(station.position.lat, station.position.lng)
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
        Card(Modifier.fillMaxWidth()) {
          Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                selected.label ?: stringResource(R.string.selected_destination),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                selected.subtitle
                    ?: "%.5f, %.5f"
                        .format(Locale.ROOT, selected.coordinate.lat, selected.coordinate.lng),
                style = MaterialTheme.typography.bodySmall,
            )
            when (val status = routingStatus) {
              is RoutingStatus.Failed ->
                  Text(
                      stringResource(R.string.route_error, status.message),
                      color = MaterialTheme.colorScheme.error,
                  )
              RoutingStatus.NoRoute ->
                  Text(stringResource(R.string.no_route), color = MaterialTheme.colorScheme.error)
              else -> Unit
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
              Button(
                  onClick = { viewModel.startSelectedDestination() },
                  enabled = routingStatus != RoutingStatus.Loading && location != null,
              ) {
                if (routingStatus == RoutingStatus.Loading) {
                  CircularProgressIndicator(Modifier.padding(end = 8.dp).size(20.dp), strokeWidth = 2.dp)
                }
                Text(stringResource(R.string.start_navigation))
              }
              OutlinedButton(onClick = { viewModel.clearDestination() }) {
                Text(stringResource(R.string.cancel))
              }
            }
          }
        }
      }
    }
  }
}

/** Starts navigating to a charger, replacing the current route if there is one. */
private fun navigateToCharger(station: ChargingStation) {
  AppGraph.chargers.clearSelection()
  AppGraph.navigationViewModel.startNavigation(
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
private suspend fun NavigationMapState.showDestination(lat: Double, lng: Double) {
  cameraMode = NavigationCameraMode.FREE
  cameraState.animateTo(
      finalPosition =
          CameraPosition(
              target = Position(longitude = lng, latitude = lat),
              zoom = 15.5,
              padding = PaddingValues(bottom = 240.dp),
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
