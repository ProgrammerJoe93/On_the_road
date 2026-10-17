package com.joegineered.ontheroad.ui

import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.joegineered.ontheroad.nav.RouteAvoidance
import com.joegineered.ontheroad.offline.OfflineArea
import com.joegineered.ontheroad.offline.OfflineAreaKind
import com.joegineered.ontheroad.offline.OfflineAreaState
import java.text.DateFormat
import java.util.Date
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.joegineered.ontheroad.AppGraph
import com.joegineered.ontheroad.BuildConfig
import com.joegineered.ontheroad.R
import com.joegineered.ontheroad.chargers.SocketType
import com.joegineered.ontheroad.settings.SpeechRate
import com.joegineered.ontheroad.settings.ThemeMode
import com.joegineered.ontheroad.settings.UnitSystem
import kotlinx.coroutines.launch

/** Pages of the full-screen settings menu. */
enum class SettingsPage {
  MAIN,
  CHARGERS,
  OFFLINE_MAPS,
}

/**
 * Full-screen settings. [onNavigate] moves between pages; null closes settings. The system back
 * gesture goes up one level. [onPickOfflineArea] closes settings to frame an area on the map.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    page: SettingsPage,
    onNavigate: (SettingsPage?) -> Unit,
    onPickOfflineArea: () -> Unit,
) {
  val up = { onNavigate(if (page == SettingsPage.MAIN) null else SettingsPage.MAIN) }
  BackHandler(onBack = up)

  Scaffold(
      topBar = {
        TopAppBar(
            title = {
              Text(
                  stringResource(
                      when (page) {
                        SettingsPage.MAIN -> R.string.settings_menu
                        SettingsPage.CHARGERS -> R.string.ev_chargers
                        SettingsPage.OFFLINE_MAPS -> R.string.offline_maps
                      }
                  )
              )
            },
            navigationIcon = {
              IconButton(onClick = up) {
                Icon(painterResource(R.drawable.ic_arrow_back), stringResource(R.string.back))
              }
            },
        )
      },
  ) { padding ->
    Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
      when (page) {
        SettingsPage.MAIN ->
            MainSettings(
                onOpenChargers = { onNavigate(SettingsPage.CHARGERS) },
                onOpenOfflineMaps = { onNavigate(SettingsPage.OFFLINE_MAPS) },
            )
        SettingsPage.CHARGERS -> ChargerSettings()
        SettingsPage.OFFLINE_MAPS -> OfflineMapSettings(onPickOfflineArea)
      }
    }
  }
}

@Composable
private fun MainSettings(onOpenChargers: () -> Unit, onOpenOfflineMaps: () -> Unit) {
  val viewModel = AppGraph.navigationViewModel
  val settings = AppGraph.settings
  val scope = rememberCoroutineScope()
  val themeMode by settings.themeMode.collectAsState(initial = ThemeMode.AUTO)
  val voiceEnabled by settings.voiceGuidanceEnabled.collectAsState(initial = true)
  val turnInstructions by settings.turnInstructionsSpoken.collectAsState(initial = true)
  val speechRate by settings.speechRate.collectAsState(initial = SpeechRate.NORMAL)
  val units by settings.unitSystem.collectAsState(initial = UnitSystem.AUTO)
  val avoid by settings.routeAvoidance.collectAsState(initial = RouteAvoidance())
  val camerasEnabled by settings.cameraWarningsEnabled.collectAsState(initial = false)
  val liveTraffic by settings.liveTrafficEnabled.collectAsState(initial = true)
  val showChargers by settings.showChargers.collectAsState(initial = true)
  val plugTypes by settings.chargerPlugTypes.collectAsState(initial = SocketType.entries.toSet())
  val simulated by viewModel.simulated.collectAsState()
  val offlineAreas by AppGraph.offlineMaps.areas.collectAsState()
  val context = LocalContext.current

  SectionHeader(stringResource(R.string.settings_section_map))
  ChoiceRow(
      title = stringResource(R.string.night_mode),
      options =
          listOf(
              ThemeMode.AUTO to R.string.night_mode_auto,
              ThemeMode.DAY to R.string.night_mode_day,
              ThemeMode.NIGHT to R.string.night_mode_night,
          ),
      selected = themeMode,
  ) {
    scope.launch { settings.setThemeMode(it) }
  }
  NavigationRow(
      title = stringResource(R.string.ev_chargers),
      summary =
          when {
            !showChargers -> stringResource(R.string.chargers_hidden)
            plugTypes.containsAll(SocketType.entries) -> stringResource(R.string.all_plug_types)
            else -> SocketType.entries.filter { it in plugTypes }.joinToString(", ") { it.label }
          },
      onClick = onOpenChargers,
  )
  NavigationRow(
      title = stringResource(R.string.offline_maps),
      summary =
          if (offlineAreas.isEmpty()) stringResource(R.string.offline_maps_none)
          else
              stringResource(
                  R.string.offline_maps_summary,
                  offlineAreas.size,
                  Formatter.formatShortFileSize(context, offlineAreas.sumOf { it.sizeBytes }),
              ),
      onClick = onOpenOfflineMaps,
  )

  HorizontalDivider(Modifier.padding(top = 8.dp))
  SectionHeader(stringResource(R.string.settings_section_navigation))
  SwitchRow(
      title = stringResource(R.string.voice_guidance),
      summary = stringResource(R.string.voice_guidance_summary),
      checked = voiceEnabled,
  ) {
    scope.launch { settings.setVoiceGuidanceEnabled(it) }
  }
  if (voiceEnabled) {
    SwitchRow(
        title = stringResource(R.string.turn_instructions),
        summary = stringResource(R.string.turn_instructions_summary),
        checked = turnInstructions,
    ) {
      scope.launch { settings.setTurnInstructionsSpoken(it) }
    }
    ChoiceRow(
        title = stringResource(R.string.speech_rate),
        options =
            listOf(
                SpeechRate.SLOW to R.string.speech_rate_slow,
                SpeechRate.NORMAL to R.string.speech_rate_normal,
                SpeechRate.FAST to R.string.speech_rate_fast,
            ),
        selected = speechRate,
    ) {
      scope.launch { settings.setSpeechRate(it) }
    }
  }
  SwitchRow(
      title = stringResource(R.string.live_traffic),
      summary = stringResource(R.string.live_traffic_summary),
      checked = liveTraffic,
  ) {
    scope.launch { settings.setLiveTrafficEnabled(it) }
  }
  SwitchRow(
      title = stringResource(R.string.camera_warnings),
      summary = stringResource(R.string.camera_warnings_summary),
      checked = camerasEnabled,
  ) {
    scope.launch { settings.setCameraWarningsEnabled(it) }
  }
  ChoiceRow(
      title = stringResource(R.string.units),
      options =
          listOf(
              UnitSystem.AUTO to R.string.units_auto,
              UnitSystem.METRIC to R.string.units_metric,
              UnitSystem.IMPERIAL to R.string.units_imperial,
          ),
      selected = units,
  ) {
    scope.launch { settings.setUnitSystem(it) }
  }

  HorizontalDivider(Modifier.padding(top = 8.dp))
  SectionHeader(stringResource(R.string.settings_section_routes))
  SwitchRow(title = stringResource(R.string.avoid_tolls), checked = avoid.tolls) {
    scope.launch { settings.setRouteAvoidance(avoid.copy(tolls = it)) }
  }
  SwitchRow(title = stringResource(R.string.avoid_motorways), checked = avoid.motorways) {
    scope.launch { settings.setRouteAvoidance(avoid.copy(motorways = it)) }
  }
  SwitchRow(title = stringResource(R.string.avoid_ferries), checked = avoid.ferries) {
    scope.launch { settings.setRouteAvoidance(avoid.copy(ferries = it)) }
  }
  Text(
      stringResource(R.string.avoid_hint),
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
  )

  if (BuildConfig.DEBUG) {
    HorizontalDivider(Modifier.padding(top = 8.dp))
    SectionHeader(stringResource(R.string.settings_section_developer))
    SwitchRow(
        title = stringResource(R.string.simulate_drive),
        summary = stringResource(R.string.simulate_drive_summary),
        checked = simulated,
    ) {
      viewModel.setSimulated(it)
    }
  }
}

@Composable
private fun ChargerSettings() {
  val settings = AppGraph.settings
  val scope = rememberCoroutineScope()
  val showChargers by settings.showChargers.collectAsState(initial = true)
  val plugTypes by settings.chargerPlugTypes.collectAsState(initial = SocketType.entries.toSet())

  SwitchRow(title = stringResource(R.string.show_chargers_on_map), checked = showChargers) {
    scope.launch { settings.setShowChargers(it) }
  }

  HorizontalDivider(Modifier.padding(top = 8.dp))
  Row(
      Modifier.fillMaxWidth().padding(end = 8.dp),
      verticalAlignment = Alignment.CenterVertically,
  ) {
    SectionHeader(stringResource(R.string.plug_types), Modifier.weight(1f))
    if (!plugTypes.containsAll(SocketType.entries)) {
      TextButton(onClick = { scope.launch { settings.setChargerPlugTypes(SocketType.entries.toSet()) } }) {
        Text(stringResource(R.string.all_plug_types))
      }
    }
  }
  SocketType.entries.forEach { type ->
    val checked = type in plugTypes
    CheckboxRow(
        title = type.label,
        summary = stringResource(type.descriptionRes()),
        checked = checked,
        // Keep at least one type selected; an empty filter would hide every charger.
        enabled = !(checked && plugTypes.size == 1),
    ) { selected ->
      val updated = if (selected) plugTypes + type else plugTypes - type
      scope.launch { settings.setChargerPlugTypes(updated) }
    }
  }
  Text(
      stringResource(R.string.plug_types_hint),
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.padding(16.dp),
  )
}

@Composable
private fun OfflineMapSettings(onPickArea: () -> Unit) {
  val settings = AppGraph.settings
  val offlineMaps = AppGraph.offlineMaps
  val scope = rememberCoroutineScope()
  val saveRoutes by settings.saveRouteMaps.collectAsState(initial = true)
  val areas by offlineMaps.areas.collectAsState()
  val network by AppGraph.network.status.collectAsState()
  var confirmDelete by remember { mutableStateOf<OfflineArea<*>?>(null) }

  SwitchRow(
      title = stringResource(R.string.save_route_maps),
      summary = stringResource(R.string.save_route_maps_summary),
      checked = saveRoutes,
  ) {
    scope.launch { settings.setSaveRouteMaps(it) }
  }

  HorizontalDivider(Modifier.padding(top = 8.dp))
  SectionHeader(stringResource(R.string.offline_saved_areas))
  if (areas.isEmpty()) {
    Text(
        stringResource(R.string.offline_no_areas),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
  }
  areas.forEach { area ->
    OfflineAreaRow(
        area = area,
        online = network.online,
        onUpdate = { offlineMaps.update(area) },
        onRetry = { offlineMaps.retry(area) },
        onDelete = { confirmDelete = area },
    )
  }
  Button(onClick = onPickArea, enabled = network.online, modifier = Modifier.padding(16.dp)) {
    Text(stringResource(R.string.offline_download_area))
  }
  Text(
      stringResource(R.string.offline_maps_hint),
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
  )

  confirmDelete?.let { area ->
    AlertDialog(
        onDismissRequest = { confirmDelete = null },
        title = { Text(stringResource(R.string.offline_delete_confirm, area.displayName())) },
        confirmButton = {
          TextButton(
              onClick = {
                areas.firstOrNull { it.info.id == area.info.id }?.let(offlineMaps::delete)
                confirmDelete = null
              }
          ) {
            Text(stringResource(R.string.offline_delete))
          }
        },
        dismissButton = {
          TextButton(onClick = { confirmDelete = null }) { Text(stringResource(R.string.cancel)) }
        },
    )
  }
}

@Composable
private fun OfflineArea<*>.displayName(): String =
    if (info.kind == OfflineAreaKind.ROUTE) stringResource(R.string.offline_route_name, info.name) else info.name

@Composable
private fun OfflineAreaRow(
    area: OfflineArea<*>,
    online: Boolean,
    onUpdate: () -> Unit,
    onRetry: () -> Unit,
    onDelete: () -> Unit,
) {
  val context = LocalContext.current
  val size = Formatter.formatShortFileSize(context, area.sizeBytes)
  val state = area.state
  val fraction =
      when (state) {
        is OfflineAreaState.Downloading -> state.fraction
        is OfflineAreaState.Updating -> state.fraction
        else -> null
      }
  val percent = fraction?.let { "${(it * 100).toInt()}%" } ?: size
  val status =
      when {
        state is OfflineAreaState.Ready -> {
          val saved =
              stringResource(
                  R.string.offline_status_ready,
                  size,
                  DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(area.info.createdAtMillis)),
              )
          if (area.outdated) "$saved · ${stringResource(R.string.offline_update_available)}" else saved
        }
        !online -> stringResource(R.string.offline_status_waiting)
        state is OfflineAreaState.Updating -> stringResource(R.string.offline_status_updating, percent)
        state is OfflineAreaState.Failed -> stringResource(R.string.offline_status_failed, state.message)
        else -> stringResource(R.string.offline_status_downloading, percent)
      }

  Row(
      Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
      verticalAlignment = Alignment.CenterVertically,
  ) {
    Column(Modifier.weight(1f)) {
      SettingText(area.displayName(), status)
      if (state is OfflineAreaState.Downloading || state is OfflineAreaState.Updating) {
        val progressModifier = Modifier.fillMaxWidth().padding(top = 6.dp)
        if (fraction != null) LinearProgressIndicator(progress = { fraction }, modifier = progressModifier)
        else LinearProgressIndicator(progressModifier)
      }
    }
    if (area.outdated && online) TextButton(onClick = onUpdate) { Text(stringResource(R.string.offline_update)) }
    if (state is OfflineAreaState.Failed && online) TextButton(onClick = onRetry) { Text(stringResource(R.string.retry)) }
    IconButton(onClick = onDelete) {
      Icon(painterResource(R.drawable.ic_delete), stringResource(R.string.offline_delete))
    }
  }
}

private fun SocketType.descriptionRes(): Int =
    when (this) {
      SocketType.CCS2 -> R.string.plug_ccs2
      SocketType.TYPE2 -> R.string.plug_type2
      SocketType.CHADEMO -> R.string.plug_chademo
      SocketType.TESLA -> R.string.plug_tesla
      SocketType.CCS1 -> R.string.plug_ccs1
      SocketType.TYPE1 -> R.string.plug_type1
      SocketType.GBT -> R.string.plug_gbt
      SocketType.TYPE3 -> R.string.plug_type3
      SocketType.SCHUKO -> R.string.plug_schuko
      SocketType.HOUSEHOLD_OTHER -> R.string.plug_household_other
      SocketType.CEE_BLUE -> R.string.plug_cee_blue
      SocketType.CEE_RED -> R.string.plug_cee_red
      SocketType.NEMA_RV -> R.string.plug_nema_rv
    }

@Composable
private fun SectionHeader(text: String, modifier: Modifier = Modifier) {
  Text(
      text,
      style = MaterialTheme.typography.titleSmall,
      color = MaterialTheme.colorScheme.primary,
      modifier = modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
  )
}

@Composable
private fun SettingText(title: String, summary: String?, modifier: Modifier = Modifier) {
  Column(modifier) {
    Text(title, style = MaterialTheme.typography.bodyLarge)
    summary?.let {
      Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
  }
}

/** A titled row of segmented buttons, one per option (value to label). */
@Composable
private fun <T> ChoiceRow(
    title: String,
    options: List<Pair<T, Int>>,
    selected: T,
    onSelect: (T) -> Unit,
) {
  Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
    Text(title, style = MaterialTheme.typography.bodyLarge)
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(top = 8.dp)) {
      options.forEachIndexed { index, (value, label) ->
        SegmentedButton(
            selected = selected == value,
            onClick = { onSelect(value) },
            shape = SegmentedButtonDefaults.itemShape(index, options.size),
        ) {
          Text(stringResource(label))
        }
      }
    }
  }
}

@Composable
private fun SwitchRow(title: String, summary: String? = null, checked: Boolean, onChange: (Boolean) -> Unit) {
  Row(
      Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(horizontal = 16.dp, vertical = 12.dp),
      verticalAlignment = Alignment.CenterVertically,
  ) {
    SettingText(title, summary, Modifier.weight(1f).padding(end = 16.dp))
    Switch(checked = checked, onCheckedChange = onChange)
  }
}

@Composable
private fun CheckboxRow(
    title: String,
    summary: String?,
    checked: Boolean,
    enabled: Boolean,
    onChange: (Boolean) -> Unit,
) {
  Row(
      Modifier.fillMaxWidth()
          .clickable(enabled = enabled) { onChange(!checked) }
          .padding(start = 4.dp, end = 16.dp, top = 4.dp, bottom = 4.dp),
      verticalAlignment = Alignment.CenterVertically,
  ) {
    Checkbox(checked = checked, onCheckedChange = onChange, enabled = enabled)
    SettingText(title, summary, Modifier.weight(1f))
  }
}

@Composable
private fun NavigationRow(title: String, summary: String?, onClick: () -> Unit) {
  Row(
      Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
      verticalAlignment = Alignment.CenterVertically,
  ) {
    SettingText(title, summary, Modifier.weight(1f))
    Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null)
  }
}
