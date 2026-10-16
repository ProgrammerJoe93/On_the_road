package com.joegineered.ontheroad.settings

import android.content.Context
import android.telephony.TelephonyManager
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
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

    /**
     * Countries where apps warning of speed camera locations are prohibited or restricted. This
     * list is a conservative default, not legal advice; review it before release.
     */
    val CAMERA_WARNING_RESTRICTED_COUNTRIES = setOf("DE", "CH", "FR", "AT")

    fun cameraWarningsAllowedByDefault(countryCode: String): Boolean =
        countryCode.uppercase(Locale.ROOT) !in CAMERA_WARNING_RESTRICTED_COUNTRIES
  }
}
