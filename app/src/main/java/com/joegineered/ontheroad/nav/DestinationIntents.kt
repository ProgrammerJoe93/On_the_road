package com.joegineered.ontheroad.nav

import android.content.Intent
import android.net.Uri
import com.stadiamaps.ferrostar.car.app.intent.NavigationDestination
import com.stadiamaps.ferrostar.car.app.intent.NavigationIntentParser
import uniffi.ferrostar.GeographicCoordinate

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

/** What a destination link asks for: a place to drive to, or a search for one. */
sealed interface DestinationRequest {
  data class Go(val destination: Destination) : DestinationRequest

  /** Only a name or address, e.g. from "navigate to Kamppi" in the car's assistant. */
  data class Search(val query: String) : DestinationRequest
}

/**
 * Turns a parsed link into a [DestinationRequest]. Assistants send `geo:0,0?q=…` for spoken
 * places, so 0,0 means there are no coordinates.
 */
fun NavigationDestination.toRequest(): DestinationRequest? {
  val lat = latitude
  val lng = longitude
  val query = query?.trim()?.takeIf { it.isNotEmpty() }
  return when {
    lat != null && lng != null && !(lat == 0.0 && lng == 0.0) ->
        DestinationRequest.Go(Destination(GeographicCoordinate(lat, lng), query))
    query != null -> DestinationRequest.Search(query)
    else -> null
  }
}
