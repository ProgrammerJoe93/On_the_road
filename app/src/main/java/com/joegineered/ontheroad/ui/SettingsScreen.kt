package com.joegineered.ontheroad.ui

import androidx.activity.compose.BackHandler
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
import com.joegineered.ontheroad.settings.ThemeMode
import kotlinx.coroutines.launch

/** Pages of the full-screen settings menu. */
enum class SettingsPage {
  MAIN,
  CHARGERS,
}

/**
 * Full-screen settings. [onNavigate] moves between pages; null closes settings. The system back
 * gesture goes up one level.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(page: SettingsPage, onNavigate: (SettingsPage?) -> Unit) {
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
        SettingsPage.MAIN -> MainSettings(onOpenChargers = { onNavigate(SettingsPage.CHARGERS) })
        SettingsPage.CHARGERS -> ChargerSettings()
      }
    }
  }
}

@Composable
private fun MainSettings(onOpenChargers: () -> Unit) {
  val viewModel = AppGraph.navigationViewModel
  val settings = AppGraph.settings
  val scope = rememberCoroutineScope()
  val themeMode by settings.themeMode.collectAsState(initial = ThemeMode.AUTO)
  val voiceEnabled by settings.voiceGuidanceEnabled.collectAsState(initial = true)
  val camerasEnabled by settings.cameraWarningsEnabled.collectAsState(initial = false)
  val liveTraffic by settings.liveTrafficEnabled.collectAsState(initial = true)
  val showChargers by settings.showChargers.collectAsState(initial = true)
  val plugTypes by settings.chargerPlugTypes.collectAsState(initial = SocketType.entries.toSet())
  val simulated by viewModel.simulated.collectAsState()

  SectionHeader(stringResource(R.string.settings_section_map))
  Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
    Text(stringResource(R.string.night_mode), style = MaterialTheme.typography.bodyLarge)
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(top = 8.dp)) {
      val modes =
          listOf(
              ThemeMode.AUTO to R.string.night_mode_auto,
              ThemeMode.DAY to R.string.night_mode_day,
              ThemeMode.NIGHT to R.string.night_mode_night,
          )
      modes.forEachIndexed { index, (mode, label) ->
        SegmentedButton(
            selected = themeMode == mode,
            onClick = { scope.launch { settings.setThemeMode(mode) } },
            shape = SegmentedButtonDefaults.itemShape(index, modes.size),
        ) {
          Text(stringResource(label))
        }
      }
    }
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

  HorizontalDivider(Modifier.padding(top = 8.dp))
  SectionHeader(stringResource(R.string.settings_section_navigation))
  SwitchRow(
      title = stringResource(R.string.voice_guidance),
      summary = stringResource(R.string.voice_guidance_summary),
      checked = voiceEnabled,
  ) {
    scope.launch { settings.setVoiceGuidanceEnabled(it) }
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
