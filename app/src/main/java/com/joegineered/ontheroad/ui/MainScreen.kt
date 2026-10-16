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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.joegineered.ontheroad.AppGraph
import com.joegineered.ontheroad.BuildConfig
import com.joegineered.ontheroad.R
import com.joegineered.ontheroad.config.AppConfig
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
import kotlinx.coroutines.launch
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
  LaunchedEffect(Unit) { AppGraph.speedCameras.start() }

  val uiState by viewModel.navigationUiState.collectAsState()
  val destination by viewModel.selectedDestination.collectAsState()
  val arrival by viewModel.arrival.collectAsState()
  val cameras by AppGraph.speedCameras.cameras.collectAsState()
  val night by AppGraph.nightMode.isNight.collectAsState()
  val navigationMapState = rememberNavigationMapState()
  // Kept at screen level so the menu state lives as long as the screen, not the overlay slot.
  val settingsMenuOpen = remember { mutableStateOf(false) }

  DynamicallyOrientingNavigationView(
      modifier = Modifier.fillMaxSize(),
      baseStyle = BaseStyle.Uri(AppConfig.mapStyleUrl(night)),
      navigationMapState = navigationMapState,
      viewModel = viewModel,
      config = VisualNavigationViewConfig.Default().withSpeedLimitStyle(signageStyle()),
      views =
          NavigationViewComponentBuilder.Default()
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
                  NavigatingOverlay(modifier)
                } else {
                  BrowsingOverlay(modifier, viewModel, navigationMapState, settingsMenuOpen)
                }
              },
      onTapExit = { viewModel.stopNavigation() },
      onMapLongClick = { coordinate, _ ->
        if (!uiState.isNavigating()) viewModel.selectDestination(coordinate)
        NavigationMapClickResult.Consume
      },
  ) { _ ->
    SpeedCameraLayer(cameras)
    DestinationPinLayer(destination?.coordinate ?: arrival?.destination?.coordinate)
  }
}

@Composable
private fun NavigatingOverlay(modifier: Modifier) {
  val warning by AppGraph.speedCameras.activeWarning.collectAsState()
  Box(modifier.fillMaxSize()) {
    warning?.let {
      // Left edge, mid-height: clear of the instruction banner (top) and trip progress (bottom).
      CameraWarningBadge(it, Modifier.align(Alignment.CenterStart).padding(start = 16.dp))
    }
  }
}

@Composable
private fun BrowsingOverlay(
    modifier: Modifier,
    viewModel: OtrNavigationViewModel,
    navigationMapState: NavigationMapState,
    settingsMenuOpen: MutableState<Boolean>,
) {
  val destination by viewModel.selectedDestination.collectAsState()
  val arrival by viewModel.arrival.collectAsState()
  val routingStatus by viewModel.routingStatus.collectAsState()
  val location by viewModel.location.collectAsState()

  val scope = rememberCoroutineScope()
  var searching by remember { mutableStateOf(false) }

  Box(modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
    SearchPanel(
        onPlaceSelected = { place ->
          viewModel.selectDestination(Destination.from(place))
          scope.launch { navigationMapState.showDestination(place.lat, place.lng) }
        },
        modifier = Modifier.align(Alignment.TopCenter).padding(16.dp),
        onActiveChanged = { searching = it },
    )

    // Hidden while searching so they don't cover the results list.
    if (!searching) Column(
        modifier = Modifier.align(Alignment.CenterEnd).padding(end = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      SettingsMenu(viewModel, settingsMenuOpen)
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

/** A settings button that opens a menu with the app's toggles. */
@Composable
private fun SettingsMenu(viewModel: OtrNavigationViewModel, open: MutableState<Boolean>) {
  val simulated by viewModel.simulated.collectAsState()
  val camerasEnabled by AppGraph.settings.cameraWarningsEnabled.collectAsState(initial = false)
  val voiceEnabled by AppGraph.settings.voiceGuidanceEnabled.collectAsState(initial = true)
  val themeMode by AppGraph.settings.themeMode.collectAsState(initial = ThemeMode.AUTO)
  val scope = rememberCoroutineScope()
  var expanded by open

  Box {
    NavigationUIButton(onClick = { expanded = true }, buttonSize = DpSize(48.dp, 48.dp)) {
      Icon(
          painter = painterResource(R.drawable.ic_tune),
          contentDescription = stringResource(R.string.settings_menu),
      )
    }
    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
      DropdownMenuItem(
          text = { Text(stringResource(R.string.night_mode)) },
          // Tapping cycles Automatic -> Day -> Night.
          onClick = {
            val next = ThemeMode.entries[(themeMode.ordinal + 1) % ThemeMode.entries.size]
            scope.launch { AppGraph.settings.setThemeMode(next) }
          },
          trailingIcon = {
            Text(
                stringResource(
                    when (themeMode) {
                      ThemeMode.AUTO -> R.string.night_mode_auto
                      ThemeMode.DAY -> R.string.night_mode_day
                      ThemeMode.NIGHT -> R.string.night_mode_night
                    }
                ),
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 16.dp),
            )
          },
      )
      ToggleMenuItem(stringResource(R.string.voice_guidance), voiceEnabled) { enabled ->
        scope.launch { AppGraph.settings.setVoiceGuidanceEnabled(enabled) }
      }
      ToggleMenuItem(stringResource(R.string.camera_warnings), camerasEnabled) { enabled ->
        scope.launch { AppGraph.settings.setCameraWarningsEnabled(enabled) }
      }
      if (BuildConfig.DEBUG) {
        ToggleMenuItem(stringResource(R.string.simulate_drive), simulated) {
          viewModel.setSimulated(it)
        }
      }
    }
  }
}

@Composable
private fun ToggleMenuItem(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
  DropdownMenuItem(
      text = { Text(label) },
      onClick = { onChange(!checked) },
      trailingIcon = {
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            modifier = Modifier.padding(start = 16.dp),
        )
      },
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
