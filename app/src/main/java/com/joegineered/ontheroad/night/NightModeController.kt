package com.joegineered.ontheroad.night

import com.joegineered.ontheroad.settings.SettingsRepository
import com.joegineered.ontheroad.settings.ThemeMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import uniffi.ferrostar.UserLocation

/**
 * Whether the phone UI should use night colours: forced by the user's setting, or automatically
 * from the sun's position at the current location, re-checked every minute. Without a location
 * fix, automatic mode follows the system dark theme.
 */
class NightModeController(
    settings: SettingsRepository,
    location: StateFlow<UserLocation?>,
    private val systemDarkTheme: () -> Boolean,
    scope: CoroutineScope,
) {
  private val tracker = DayNightTracker()

  private val minuteTicks = flow {
    while (true) {
      emit(System.currentTimeMillis())
      delay(60_000)
    }
  }

  val isNight: StateFlow<Boolean> =
      combine(settings.themeMode, location, minuteTicks) { mode, userLocation, now ->
            when (mode) {
              ThemeMode.DAY -> false
              ThemeMode.NIGHT -> true
              ThemeMode.AUTO -> {
                val coordinates = userLocation?.coordinates
                if (coordinates == null) systemDarkTheme()
                else
                    tracker.isNight(
                        SolarPosition.elevationDegrees(coordinates.lat, coordinates.lng, now)
                    )
              }
            }
          }
          .stateIn(scope, SharingStarted.Eagerly, systemDarkTheme())
}
