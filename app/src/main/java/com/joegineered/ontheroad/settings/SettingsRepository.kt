package com.joegineered.ontheroad.settings

import android.content.Context
import android.telephony.TelephonyManager
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.joegineered.ontheroad.chargers.SocketType
import com.joegineered.ontheroad.nav.RouteAvoidance
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

  /** Whether turn instructions are spoken. When off, camera and traffic warnings still are. */
  val turnInstructionsSpoken: Flow<Boolean> =
      context.dataStore.data.map { prefs -> prefs[TURN_INSTRUCTIONS_SPOKEN] ?: true }

  suspend fun setTurnInstructionsSpoken(spoken: Boolean) {
    context.dataStore.edit { it[TURN_INSTRUCTIONS_SPOKEN] = spoken }
  }

  /** How fast the navigation voice speaks. */
  val speechRate: Flow<SpeechRate> =
      context.dataStore.data.map { prefs ->
        prefs[SPEECH_RATE]?.let { runCatching { SpeechRate.valueOf(it) }.getOrNull() } ?: SpeechRate.NORMAL
      }

  suspend fun setSpeechRate(rate: SpeechRate) {
    context.dataStore.edit { it[SPEECH_RATE] = rate.name }
  }

  /** Roads routes avoid where they reasonably can. */
  val routeAvoidance: Flow<RouteAvoidance> =
      context.dataStore.data.map { prefs ->
        RouteAvoidance(
            tolls = prefs[AVOID_TOLLS] ?: false,
            motorways = prefs[AVOID_MOTORWAYS] ?: false,
            ferries = prefs[AVOID_FERRIES] ?: false,
        )
      }

  suspend fun setRouteAvoidance(avoid: RouteAvoidance) {
    context.dataStore.edit {
      it[AVOID_TOLLS] = avoid.tolls
      it[AVOID_MOTORWAYS] = avoid.motorways
      it[AVOID_FERRIES] = avoid.ferries
    }
  }

  /** Units for distances and speeds. */
  val unitSystem: Flow<UnitSystem> =
      context.dataStore.data.map { prefs ->
        prefs[UNIT_SYSTEM]?.let { runCatching { UnitSystem.valueOf(it) }.getOrNull() } ?: UnitSystem.AUTO
      }

  suspend fun setUnitSystem(units: UnitSystem) {
    context.dataStore.edit { it[UNIT_SYSTEM] = units.name }
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

  /** Whether the map along each route is downloaded when navigation starts, for use offline. */
  val saveRouteMaps: Flow<Boolean> = context.dataStore.data.map { prefs -> prefs[SAVE_ROUTE_MAPS] ?: true }

  suspend fun setSaveRouteMaps(enabled: Boolean) {
    context.dataStore.edit { it[SAVE_ROUTE_MAPS] = enabled }
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
    private val SAVE_ROUTE_MAPS = booleanPreferencesKey("save_route_maps")
    private val TURN_INSTRUCTIONS_SPOKEN = booleanPreferencesKey("turn_instructions_spoken")
    private val SPEECH_RATE = stringPreferencesKey("speech_rate")
    private val UNIT_SYSTEM = stringPreferencesKey("unit_system")
    private val AVOID_TOLLS = booleanPreferencesKey("avoid_tolls")
    private val AVOID_MOTORWAYS = booleanPreferencesKey("avoid_motorways")
    private val AVOID_FERRIES = booleanPreferencesKey("avoid_ferries")

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

/** Units for distances and speeds. */
enum class UnitSystem {
  /** Imperial only where the device region uses it for road distances. */
  AUTO,
  METRIC,
  IMPERIAL;

  fun isMetric(countryCode: String = Locale.getDefault().country): Boolean =
      when (this) {
        AUTO -> countryCode.uppercase(Locale.ROOT) !in IMPERIAL_COUNTRIES
        METRIC -> true
        IMPERIAL -> false
      }

  private companion object {
    val IMPERIAL_COUNTRIES = setOf("US", "LR", "MM")
  }
}

/** Speech rates for the navigation voice, as multipliers of the engine's normal rate. */
enum class SpeechRate(val multiplier: Float) {
  SLOW(0.8f),
  NORMAL(1.0f),
  FAST(1.25f),
}
