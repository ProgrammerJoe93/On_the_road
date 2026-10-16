package com.joegineered.ontheroad.settings

import android.content.Context
import android.telephony.TelephonyManager
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.joegineered.ontheroad.chargers.SocketType
import androidx.datastore.preferences.preferencesDataStore
import java.util.Locale
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "settings")

class SettingsRepository(private val context: Context) {

  /**
   * Whether speed camera warnings are on. Until the user chooses, this defaults to off in
   * countries where warning about camera locations is restricted.
   */
  val cameraWarningsEnabled: Flow<Boolean> =
      context.dataStore.data.map { prefs ->
        prefs[CAMERA_WARNINGS] ?: cameraWarningsAllowedByDefault(currentCountry())
      }

  /** Whether turn instructions and warnings are spoken. Mirrors the mute button. */
  val voiceGuidanceEnabled: Flow<Boolean> =
      context.dataStore.data.map { prefs -> prefs[VOICE_GUIDANCE] ?: true }

  suspend fun setVoiceGuidanceEnabled(enabled: Boolean) {
    context.dataStore.edit { it[VOICE_GUIDANCE] = enabled }
  }

  /** Day/night colours for the map and UI. */
  val themeMode: Flow<ThemeMode> =
      context.dataStore.data.map { prefs ->
        prefs[THEME_MODE]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.AUTO
      }

  suspend fun setThemeMode(mode: ThemeMode) {
    context.dataStore.edit { it[THEME_MODE] = mode.name }
  }

  /** Whether EV chargers are shown on the map. */
  val showChargers: Flow<Boolean> = context.dataStore.data.map { prefs -> prefs[SHOW_CHARGERS] ?: true }

  suspend fun setShowChargers(show: Boolean) {
    context.dataStore.edit { it[SHOW_CHARGERS] = show }
  }

  /**
   * Plug types to show chargers for. "All types" is stored as no filter at all, so plug types
   * added in later versions are included automatically.
   */
  val chargerPlugTypes: Flow<Set<SocketType>> =
      context.dataStore.data.map { prefs -> decodePlugTypes(prefs[CHARGER_PLUG_TYPES]) }

  suspend fun setChargerPlugTypes(types: Set<SocketType>) {
    context.dataStore.edit { prefs ->
      if (types.containsAll(SocketType.entries)) prefs.remove(CHARGER_PLUG_TYPES)
      else prefs[CHARGER_PLUG_TYPES] = types.map { it.name }.toSet()
    }
  }

  /** Live traffic from Fintraffic (Finland): incidents, roadworks, slow traffic, closures avoided. */
  val liveTrafficEnabled: Flow<Boolean> = context.dataStore.data.map { prefs -> prefs[LIVE_TRAFFIC] ?: true }

  suspend fun setLiveTrafficEnabled(enabled: Boolean) {
    context.dataStore.edit { it[LIVE_TRAFFIC] = enabled }
  }

  suspend fun setCameraWarningsEnabled(enabled: Boolean) {
    context.dataStore.edit { it[CAMERA_WARNINGS] = enabled }
  }

  /** The country the phone is currently in (mobile network), falling back to the device region. */
  private fun currentCountry(): String {
    val telephony = context.getSystemService(TelephonyManager::class.java)
    val network = telephony?.networkCountryIso.orEmpty()
    return network.ifEmpty { Locale.getDefault().country }.uppercase(Locale.ROOT)
  }

  companion object {
    private val CAMERA_WARNINGS = booleanPreferencesKey("camera_warnings")
    private val VOICE_GUIDANCE = booleanPreferencesKey("voice_guidance")
    private val THEME_MODE = stringPreferencesKey("theme_mode")
    private val SHOW_CHARGERS = booleanPreferencesKey("show_chargers")
    private val LIVE_TRAFFIC = booleanPreferencesKey("live_traffic")
    private val CHARGER_PLUG_TYPES = stringSetPreferencesKey("charger_plug_types")

    /**
     * Countries where apps warning of speed camera locations are prohibited or restricted. This
     * list is a conservative default, not legal advice; review it before release.
     */
    val CAMERA_WARNING_RESTRICTED_COUNTRIES = setOf("DE", "CH", "FR", "AT")

    /** Reads stored plug types; "CCS" was the name of CCS2 before CCS1 was added. */
    fun decodePlugTypes(stored: Set<String>?): Set<SocketType> {
      val types =
          stored
              ?.mapNotNull { name ->
                if (name == "CCS") SocketType.CCS2 else SocketType.entries.firstOrNull { it.name == name }
              }
              ?.toSet()
      return if (types.isNullOrEmpty()) SocketType.entries.toSet() else types
    }

    fun cameraWarningsAllowedByDefault(countryCode: String): Boolean =
        countryCode.uppercase(Locale.ROOT) !in CAMERA_WARNING_RESTRICTED_COUNTRIES
  }
}

/** How the app picks day or night colours. */
enum class ThemeMode {
  /** Night between sunset and sunrise at the current location (phone); the car's signal in Android Auto. */
  AUTO,
  DAY,
  NIGHT,
}
