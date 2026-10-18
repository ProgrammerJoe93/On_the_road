package com.joegineered.ontheroad.search

import java.io.IOException
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Address and place search using Photon (https://photon.komoot.io), an OpenStreetMap geocoder.
 * The public instance is free for fair use; callers should debounce typing.
 */
class PhotonGeocoder(
    private val httpClient: OkHttpClient,
    private val endpoint: String = "https://photon.komoot.io/api/",
    private val reverseEndpoint: String = "https://photon.komoot.io/reverse",
) {

  /** Places matching [query], ranked with a bias towards [near] when given. */
  suspend fun search(
      query: String,
      near: Pair<Double, Double>? = null,
      limit: Int = 8,
  ): List<Place> =
      withContext(Dispatchers.IO) {
        val url =
            endpoint
                .toHttpUrl()
                .newBuilder()
                .addQueryParameter("q", query)
                .addQueryParameter("limit", limit.toString())
                .addQueryParameter("lang", photonLanguage())
                .apply {
                  near?.let { (lat, lng) ->
                    addQueryParameter("lat", lat.toString())
                    addQueryParameter("lon", lng.toString())
                  }
                }
                .build()
        parse(get(url), Locale.getDefault().country)
      }

  /** The town or area at a point, e.g. to name an offline map area. */
  suspend fun locality(lat: Double, lng: Double): String? =
      withContext(Dispatchers.IO) {
        val url =
            reverseEndpoint
                .toHttpUrl()
                .newBuilder()
                .addQueryParameter("lat", lat.toString())
                .addQueryParameter("lon", lng.toString())
                .addQueryParameter("lang", photonLanguage())
                .build()
        parseLocality(get(url))
      }

  private fun get(url: HttpUrl): String {
    val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
    return httpClient.newCall(request).execute().use { response ->
      if (!response.isSuccessful) throw IOException("Photon HTTP ${response.code}")
      response.body.string()
    }
  }

  companion object {
    private const val USER_AGENT = "OnTheRoad/0.1 (Android navigation app)"

    /** Photon localises results in these languages; otherwise it uses local names. */
    private val PHOTON_LANGUAGES = setOf("en", "de", "fr")

    private fun photonLanguage(): String =
        Locale.getDefault().language.takeIf { it in PHOTON_LANGUAGES } ?: "default"

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Parses a Photon GeoJSON response.
     *
     * @param homeCountry ISO country code of the device region; the country is left out of
     *   subtitles for places in it.
     */
    fun parse(body: String, homeCountry: String = ""): List<Place> {
      val features =
          json.parseToJsonElement(body).jsonObject["features"] as? JsonArray ?: return emptyList()
      return features
          .mapNotNull { feature -> (feature as? JsonObject)?.toPlace(homeCountry) }
          .distinctBy { it.title to it.subtitle }
          // Photon can list one place twice with different address details, e.g. a square
          // with and without a street; show it once.
          .fold(emptyList()) { kept, place ->
            if (kept.any { it.title == place.title && it.isSameSpot(place) }) kept else kept + place
          }
    }

    /** The most specific settlement name in a Photon reverse-geocoding response. */
    fun parseLocality(body: String): String? {
      val features = json.parseToJsonElement(body).jsonObject["features"] as? JsonArray
      val props = (features?.firstOrNull() as? JsonObject)?.get("properties") as? JsonObject ?: return null
      return listOf("city", "town", "village", "locality", "district", "county", "state", "name")
          .firstNotNullOfOrNull { key -> props[key]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } }
    }

    private fun JsonObject.toPlace(homeCountry: String): Place? {
      val coordinates =
          (this["geometry"] as? JsonObject)?.get("coordinates")?.jsonArray ?: return null
      val lng = coordinates.getOrNull(0)?.jsonPrimitive?.doubleOrNull ?: return null
      val lat = coordinates.getOrNull(1)?.jsonPrimitive?.doubleOrNull ?: return null
      val props = this["properties"] as? JsonObject ?: return null
      fun prop(key: String) = props[key]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }

      val streetLine =
          prop("street")?.let { street -> listOfNotNull(street, prop("housenumber")).joinToString(" ") }
      val cityLine =
          listOfNotNull(prop("postcode"), prop("city") ?: prop("town") ?: prop("village"))
              .joinToString(" ")
              .takeIf { it.isNotBlank() }
      val country =
          prop("country").takeIf {
            !prop("countrycode").equals(homeCountry, ignoreCase = true)
          }

      val title = prop("name") ?: streetLine ?: cityLine ?: return null
      val subtitle =
          listOfNotNull(streetLine?.takeIf { it != title }, cityLine?.takeIf { it != title }, country)
              .joinToString(", ")
              .takeIf { it.isNotBlank() }
      return Place(title = title, subtitle = subtitle, lat = lat, lng = lng)
    }
  }
}
