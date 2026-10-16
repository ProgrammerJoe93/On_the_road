package com.joegineered.ontheroad.config

import com.joegineered.ontheroad.BuildConfig
import com.joegineered.ontheroad.settings.UnitSystem
import com.stadiamaps.ferrostar.ui.formatters.DistanceMeasurementSystem
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow

object AppConfig {
  /** OpenFreeMap vector styles; free and keyless. Data © OpenStreetMap contributors (ODbL). */
  const val MAP_STYLE_URL = "https://tiles.openfreemap.org/styles/liberty"
  const val NIGHT_MAP_STYLE_URL = "https://tiles.openfreemap.org/styles/fiord"

  fun mapStyleUrl(night: Boolean) = if (night) NIGHT_MAP_STYLE_URL else MAP_STYLE_URL

  /** Both styles, so offline areas work by day and by night. */
  val MAP_STYLE_URLS = listOf(MAP_STYLE_URL, NIGHT_MAP_STYLE_URL)

  /** The TileJSON of the vector tiles both styles use. */
  const val MAP_TILES_URL = "https://tiles.openfreemap.org/planet"

  /** OpenFreeMap's highest tile zoom; the map overzooms beyond it. */
  const val MAP_MAX_TILE_ZOOM = 14

  val valhallaUrl: String = BuildConfig.VALHALLA_URL

  const val VALHALLA_COSTING = "auto"

  /** Instruction languages supported by Valhalla's narrative builder. */
  private val valhallaLanguages =
      listOf(
          "bg-BG", "ca-ES", "cs-CZ", "da-DK", "de-DE", "el-GR", "en-GB", "en-US", "es-ES", "et-EE",
          "fi-FI", "fr-FR", "hi-IN", "hu-HU", "it-IT", "ja-JP", "nb-NO", "nl-NL", "pl-PL", "pt-BR",
          "pt-PT", "ro-RO", "ru-RU", "sk-SK", "sl-SI", "sv-SE", "tr-TR", "uk-UA",
      )

  /**
   * Picks the Valhalla instruction language closest to [locale], so spoken and written
   * instructions match the device language. Falls back to US English.
   */
  fun instructionLanguage(locale: Locale = Locale.getDefault()): String {
    val tag = locale.toLanguageTag()
    return valhallaLanguages.firstOrNull { it.equals(tag, ignoreCase = true) }
        ?: valhallaLanguages.firstOrNull { it.substringBefore('-') == locale.language }
        ?: "en-US"
  }

  /**
   * The units setting, mirrored here by `AppGraph` so formatting code that isn't composed (route
   * requests, voice text) can read it. Compose code should collect it, to update on changes.
   */
  val unitSystem = MutableStateFlow(UnitSystem.AUTO)

  val usesMetricUnits: Boolean
    get() = unitSystem.value.isMetric()

  /** Ferrostar's distance formatting for [metric] units. */
  fun distanceMeasurementSystem(metric: Boolean = usesMetricUnits) =
      if (metric) DistanceMeasurementSystem.SI else DistanceMeasurementSystem.IMPERIAL
}
