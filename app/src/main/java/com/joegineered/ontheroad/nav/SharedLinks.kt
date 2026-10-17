package com.joegineered.ontheroad.nav

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import uniffi.ferrostar.GeographicCoordinate

/**
 * What text shared from another app (a map link, an address, coordinates) points to. A short
 * link holds no place itself; it is resolved online with [ShortLinkResolver], falling back to
 * [request] (a search for the rest of the text) when that fails.
 */
data class SharedText(val request: DestinationRequest?, val shortLink: HttpUrl? = null)

/**
 * Reads a place from text shared with the app, such as "Kamppi Center\nhttps://maps.app.goo.gl/…"
 * from Google Maps. Uses the first link with a place in it, then coordinates written in the text,
 * then searches for the text itself. A shared place is previewed rather than started, so the
 * requests use [TripAction.DIRECTIONS].
 */
fun parseSharedText(text: String): SharedText? {
  val links = LINK.findAll(text).map { it.value.trimEnd('.', ',', ')', '!', '?') }.toList()
  val urls = links.mapNotNull { it.toHttpUrlOrNull() }
  urls.firstNotNullOfOrNull(::parseMapUrl)?.let { return SharedText(it) }
  links
      .firstOrNull { it.startsWith("geo:", ignoreCase = true) }
      ?.let { parseDestinationRequestUri("geo", it.substring(4), ::decodeQueryValue) }
      ?.let { return SharedText(it.viewed()) }

  val rest = links.fold(text) { remaining, link -> remaining.replace(link, " ") }
  coordinatesIn(rest)?.let { return SharedText(DestinationRequest.Go(Destination(it), TripAction.DIRECTIONS)) }
  val query =
      rest.lines().map { it.trim().trim(',') }.filter { it.isNotEmpty() }.joinToString(", ").take(MAX_QUERY)
  val search = query.takeIf { it.length >= 3 }?.let { DestinationRequest.Search(it, TripAction.DIRECTIONS) }
  val shortLink = urls.firstOrNull(::isShortMapLink)
  if (search == null && shortLink == null) return null
  return SharedText(search, shortLink)
}

/**
 * The place in a Google Maps, OpenStreetMap or Apple Maps link, as a destination when it has
 * coordinates or a search when it only names a place. Null for other links.
 */
fun parseMapUrl(url: HttpUrl): DestinationRequest? {
  val host = url.host.removePrefix("www.")
  return when {
    isGoogleMaps(url) -> parseGoogleMaps(url)
    host == "openstreetmap.org" || host == "osm.org" -> parseOpenStreetMap(url)
    host == "maps.apple.com" -> parseAppleMaps(url)
    // Google asks EU users for cookie consent on the way to Maps; the link it continues to is ours.
    host == "consent.google.com" -> url.queryParameter("continue")?.toHttpUrlOrNull()?.let(::parseMapUrl)
    else -> null
  }
}

/** Short links from the Google Maps share sheet, which redirect to a full Maps link. */
fun isShortMapLink(url: HttpUrl): Boolean =
    url.host == "maps.app.goo.gl" ||
        (url.host == "goo.gl" && url.encodedPath.startsWith("/maps")) ||
        url.host == "g.co"

private fun isGoogleMaps(url: HttpUrl): Boolean {
  val host = url.host
  val google = GOOGLE_HOST.matches(host)
  return google && (host.startsWith("maps.") || url.pathSegments.firstOrNull() == "maps")
}

/**
 * Google Maps links: `/maps/place/<name>/@<map centre>/data=…!3d<lat>!4d<lng>`, the `api=1`
 * links (`query=`, `destination=`), the old `?q=` and `?daddr=` links, and `/maps/@<lat>,<lng>`.
 * The `!3d…!4d…` pair is the place itself; `@` is only where the map was centred, so it is used
 * just when the link has nothing else.
 */
private fun parseGoogleMaps(url: HttpUrl): DestinationRequest? {
  val segments = url.pathSegments.dropWhile { it == "maps" }
  val name =
      segments.getOrNull(1)?.takeIf { segments[0] == "place" || segments[0] == "search" }?.plusToSpace()
  val nameCoordinates = name?.let(::coordinatesIn)
  val label = name?.takeIf { nameCoordinates == null }

  PLACE_DATA.find(url.encodedPath)?.let { match ->
    val (lat, lng) = match.destructured
    return go(GeographicCoordinate(lat.toDouble(), lng.toDouble()), label)
  }
  nameCoordinates?.let { return go(it, null) }
  for (key in listOf("destination", "daddr", "query", "q", "ll")) {
    val value = url.queryParameter(key)?.trim()?.takeIf { it.isNotEmpty() } ?: continue
    return coordinatesIn(value)?.let { go(it, label) } ?: search(value)
  }
  label?.let { return search(it) }
  return segments.firstOrNull { it.startsWith("@") }?.let(::coordinatesIn)?.let { go(it, null) }
}

/** OpenStreetMap links: the marker (`mlat`, `mlon`), else the map view (`#map=<zoom>/<lat>/<lng>`). */
private fun parseOpenStreetMap(url: HttpUrl): DestinationRequest? {
  val lat = url.queryParameter("mlat")?.toDoubleOrNull()
  val lng = url.queryParameter("mlon")?.toDoubleOrNull()
  if (lat != null && lng != null) return go(GeographicCoordinate(lat, lng), null)
  val view = url.fragment?.let(OSM_MAP_VIEW::find) ?: return null
  val (viewLat, viewLng) = view.destructured
  return go(GeographicCoordinate(viewLat.toDouble(), viewLng.toDouble()), null)
}

/** Apple Maps links: `ll=` or `coordinate=`, labelled by `q=` or `name=`; else `daddr=`, `address=`, `q=`. */
private fun parseAppleMaps(url: HttpUrl): DestinationRequest? {
  val label = (url.queryParameter("name") ?: url.queryParameter("q"))?.trim()?.takeIf { it.isNotEmpty() }
  for (key in listOf("coordinate", "ll")) {
    url.queryParameter(key)?.let(::coordinatesIn)?.let { return go(it, label) }
  }
  for (key in listOf("daddr", "address", "q")) {
    val value = url.queryParameter(key)?.trim()?.takeIf { it.isNotEmpty() } ?: continue
    return coordinatesIn(value)?.let { go(it, null) } ?: search(value)
  }
  return null
}

/** Decimal coordinates written as "60.1699, 24.9384" (latitude first), or null. */
private fun coordinatesIn(text: String): GeographicCoordinate? {
  val match = COORDINATES.find(text) ?: return null
  val lat = match.groupValues[1].toDouble()
  val lng = match.groupValues[2].toDouble()
  if (lat !in -90.0..90.0 || lng !in -180.0..180.0) return null
  return GeographicCoordinate(lat, lng)
}

private fun go(coordinate: GeographicCoordinate, label: String?) =
    DestinationRequest.Go(Destination(coordinate, label), TripAction.DIRECTIONS)

private fun search(query: String) = DestinationRequest.Search(query.take(MAX_QUERY), TripAction.DIRECTIONS)

/** Google writes spaces in place names as "+", which [HttpUrl.pathSegments] keeps. */
private fun String.plusToSpace() = replace('+', ' ').trim()

/** Decodes a `geo:` query value as a URL would be (shared text isn't an Android [android.net.Uri]). */
private fun decodeQueryValue(value: String): String =
    value.replace('+', ' ').let { "https://x/?v=$it".toHttpUrlOrNull()?.queryParameter("v") ?: it }

private const val MAX_QUERY = 200

private val LINK = Regex("""(?:https?://|geo:)\S+""", RegexOption.IGNORE_CASE)

private val GOOGLE_HOST = Regex("""^(?:www\.|maps\.)?google\.[a-z]{2,3}(?:\.[a-z]{2})?$""")

private val PLACE_DATA = Regex("""!3d(-?\d+(?:\.\d+)?)!4d(-?\d+(?:\.\d+)?)""")

private val OSM_MAP_VIEW = Regex("""map=\d+(?:\.\d+)?/(-?\d+(?:\.\d+)?)/(-?\d+(?:\.\d+)?)""")

/** Two decimal numbers with a fraction, so "Mannerheimintie 1, 00100" isn't taken for one. */
private val COORDINATES = Regex("""(?<![\d.])(-?\d{1,2}\.\d+)\s*,\s*(-?\d{1,3}\.\d+)(?![\d.])""")
