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

/**
 * What the driver asked to do with a place: the `intent` parameter of Assistant's navigation
 * links (`geo:0,0?q=…&intent=add_a_stop`). Without one, Assistant means [NAVIGATE].
 */
enum class TripAction {
  /** "Navigate to …": start, or change, the trip. */
  NAVIGATE,

  /** "Add a stop at …". */
  ADD_STOP,

  /** "Directions to …": show the routes without starting. */
  DIRECTIONS;

  companion object {
    fun fromParameter(value: String?): TripAction =
        when (value) {
          "add_a_stop" -> ADD_STOP
          "directions" -> DIRECTIONS
          else -> NAVIGATE
        }
  }
}

/** What a destination link asks for: a place to drive to, or a search for one. */
sealed interface DestinationRequest {
  val action: TripAction

  data class Go(
      val destination: Destination,
      override val action: TripAction = TripAction.NAVIGATE,
  ) : DestinationRequest

  /** Only a name or address, e.g. from "navigate to Kamppi" in the car's assistant. */
  data class Search(
      val query: String,
      override val action: TripAction = TripAction.NAVIGATE,
  ) : DestinationRequest
}

/**
 * The request for a link that was opened rather than spoken: a plain "navigate" becomes
 * [TripAction.DIRECTIONS], to look at the routes before starting.
 */
fun DestinationRequest.viewed(): DestinationRequest {
  if (action != TripAction.NAVIGATE) return this
  return when (this) {
    is DestinationRequest.Go -> copy(action = TripAction.DIRECTIONS)
    is DestinationRequest.Search -> copy(action = TripAction.DIRECTIONS)
  }
}

/** Parses a `geo:` or `google.navigation:` intent into a [DestinationRequest]. */
fun parseDestinationRequest(intent: Intent): DestinationRequest? {
  val uri = intent.data ?: return null
  return parseDestinationRequestUri(uri.scheme, uri.schemeSpecificPart)
}

/** [parseDestinationRequest] for a link's [scheme] and scheme-specific part [ssp]. */
fun parseDestinationRequestUri(
    scheme: String?,
    ssp: String?,
    decode: (String) -> String = Uri::decode,
): DestinationRequest? {
  val destination = parseDestinationUri(scheme, ssp, decode) ?: return null
  val action =
      if (scheme == "geo") {
        TripAction.fromParameter(queryParameter(ssp!!.substringAfter('?', ""), "intent", decode))
      } else {
        TripAction.NAVIGATE
      }
  return destination.toRequest(action)
}

/**
 * Turns a parsed link into a [DestinationRequest]. Assistants send `geo:0,0?q=…` for spoken
 * places, so 0,0 means there are no coordinates.
 */
fun NavigationDestination.toRequest(action: TripAction = TripAction.NAVIGATE): DestinationRequest? {
  val lat = latitude
  val lng = longitude
  val query = query?.trim()?.takeIf { it.isNotEmpty() }
  return when {
    lat != null && lng != null && !(lat == 0.0 && lng == 0.0) ->
        DestinationRequest.Go(Destination(GeographicCoordinate(lat, lng), query), action)
    query != null -> DestinationRequest.Search(query, action)
    else -> null
  }
}

/** What to do with a place the driver picked or asked for, given what is on screen. */
enum class DestinationOutcome {
  /** Start navigating to it. */
  START,

  /** Show the routes to it, to pick one and start. */
  PREVIEW,

  /** Add it to the routes being previewed, as a stop before the destination. */
  ADD_TO_PREVIEW,

  /** Add it to the trip in progress, in route order. */
  ADD_TO_TRIP,

  /** Ask whether to replace the trip in progress or add it as a stop. */
  ASK,
}

/**
 * Decides what to do with a destination. During a trip, only an explicit "add a stop" skips the
 * question: replacing the trip would drop its stops, and "navigate to …" doesn't say whether the
 * driver meant that. Without a [hasLocation] fix, routes can't be planned yet, so the preview
 * waits for the first fix rather than failing.
 */
fun destinationOutcome(
    action: TripAction,
    navigating: Boolean,
    previewing: Boolean,
    hasLocation: Boolean,
): DestinationOutcome =
    when {
      navigating ->
          if (action == TripAction.ADD_STOP) DestinationOutcome.ADD_TO_TRIP else DestinationOutcome.ASK
      action == TripAction.ADD_STOP && previewing -> DestinationOutcome.ADD_TO_PREVIEW
      action == TripAction.DIRECTIONS || !hasLocation -> DestinationOutcome.PREVIEW
      else -> DestinationOutcome.START
    }
