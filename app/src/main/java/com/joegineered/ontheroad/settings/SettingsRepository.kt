package com.joegineered.ontheroad.settings

import android.content.Context
import android.telephony.TelephonyManager
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
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

    /**
     * Countries where apps warning of speed camera locations are prohibited or restricted. This
     * list is a conservative default, not legal advice; review it before release.
     */
    val CAMERA_WARNING_RESTRICTED_COUNTRIES = setOf("DE", "CH", "FR", "AT")

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
