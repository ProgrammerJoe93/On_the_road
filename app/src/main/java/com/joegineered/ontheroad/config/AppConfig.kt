package com.joegineered.ontheroad.config

import com.joegineered.ontheroad.BuildConfig
import java.util.Locale

object AppConfig {
  /** OpenFreeMap vector style; free and keyless. Data © OpenStreetMap contributors (ODbL). */
  const val MAP_STYLE_URL = "https://tiles.openfreemap.org/styles/liberty"

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

  /** Imperial units only where the device region uses them for road distances. */
  val usesMetricUnits: Boolean
    get() = Locale.getDefault().country !in setOf("US", "LR", "MM")
}
