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
  return parseDestinationUri(uri.scheme, uri.schemeSpecificPart)
}

/**
 * Parses a destination link given its [scheme] and scheme-specific part [ssp] (everything after
 * the colon). [decode] decodes query values; it is Android's [Uri.decode] in the app.
 */
fun parseDestinationUri(
    scheme: String?,
    ssp: String?,
    decode: (String) -> String = Uri::decode,
): NavigationDestination? {
  ssp ?: return null
  return when (scheme) {
    "geo" -> {
      val query = ssp.substringAfter('?', missingDelimiterValue = "")
      NavigationIntentParser.parseGeoSsp(ssp.substringBefore('?'), queryParameter(query, "q", decode))
    }
    // The parameters are the whole part after the colon: google.navigation:q=60.17,24.94&mode=d
    "google.navigation" ->
        queryParameter(ssp.removePrefix("?"), "q", decode)?.let { NavigationIntentParser.parseGoogleNavigationSsp(it) }
    else -> null
  }
}

private fun queryParameter(query: String, name: String, decode: (String) -> String): String? =
    query
        .split('&')
        .firstOrNull { it.substringBefore('=') == name }
        ?.substringAfter('=', missingDelimiterValue = "")
        ?.let(decode)
        ?.takeIf { it.isNotBlank() }
