package com.joegineered.ontheroad.traffic

import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Fintraffic's open road traffic API. Free and keyless; Digitraffic asks clients to identify
 * themselves with the Digitraffic-User header. Data © Fintraffic, licensed CC BY 4.0.
 */
class DigitrafficClient(
    private val httpClient: OkHttpClient,
    private val baseUrl: String = "https://tie.digitraffic.fi",
) {
  suspend fun trafficAnnouncements(): String = get("/api/traffic-message/v2/traffic-announcements")

  suspend fun roadworks(): String = get("/api/traffic-message/v2/roadworks")

  suspend fun stationData(): String = get("/api/tms/v1/stations/data")

  suspend fun stations(): String = get("/api/tms/v1/stations")

  /** Main road sections that road weather forecasts are given for (static; about 240 kB). */
  suspend fun roadWeatherSections(): String = get("/api/weather/v2/forecast-sections-simple")

  suspend fun roadWeatherForecasts(): String = get("/api/weather/v2/forecast-sections-simple/forecasts")

  /** Road weather stations: locations and names (static; about 190 kB). */
  suspend fun weatherStations(): String = get("/api/weather/v2/stations")

  /** Latest measurements from all road weather stations (about 7 MB, 360 kB gzipped). */
  suspend fun weatherStationData(): String = get("/api/weather/v2/stations/data")

  /** Variable speed limit, warning and message signs, as they show now (about 270 kB). */
  suspend fun variableSigns(): String = get("/api/variable-sign/v1/signs")

  private suspend fun get(path: String): String =
      withContext(Dispatchers.IO) {
        val request =
            Request.Builder().url(baseUrl + path).header("Digitraffic-User", USER).build()
        httpClient.newCall(request).execute().use { response ->
          if (!response.isSuccessful) throw IOException("Digitraffic HTTP ${response.code} for $path")
          response.body.string()
        }
      }

  private companion object {
    const val USER = "OnTheRoad/0.1"
  }
}
