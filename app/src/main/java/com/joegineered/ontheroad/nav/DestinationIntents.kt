package com.joegineered.ontheroad.nav

import android.content.Intent
import android.net.Uri
import com.stadiamaps.ferrostar.car.app.intent.NavigationDestination
import com.stadiamaps.ferrostar.car.app.intent.NavigationIntentParser

/**
 * Parses `geo:` and `google.navigation:` destination links.
 *
 * Both are opaque URIs, so [Uri.getQueryParameter] throws on them. Ferrostar 0.58's
 * [NavigationIntentParser.parse] calls it and crashes on links like `geo:60.17,24.94?q=Home`, so
 * we split the query ourselves and reuse Ferrostar's coordinate parsing.
 */
fun parseDestinationIntent(intent: Intent): NavigationDestination? {
  val uri = intent.data ?: return null
  val ssp = uri.schemeSpecificPart ?: return null
  val query = ssp.substringAfter('?', missingDelimiterValue = "")
  return when (uri.scheme) {
    "geo" -> NavigationIntentParser.parseGeoSsp(ssp.substringBefore('?'), queryParameter(query, "q"))
    "google.navigation" ->
        queryParameter(query, "q")?.let { NavigationIntentParser.parseGoogleNavigationSsp(it) }
    else -> null
  }
}

private fun queryParameter(query: String, name: String): String? =
    query
        .split('&')
        .firstOrNull { it.substringBefore('=') == name }
        ?.substringAfter('=', missingDelimiterValue = "")
        ?.let(Uri::decode)
        ?.takeIf { it.isNotBlank() }
