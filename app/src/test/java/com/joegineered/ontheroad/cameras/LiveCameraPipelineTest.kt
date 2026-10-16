package com.joegineered.ontheroad.cameras

import java.nio.file.Files
import java.time.Duration
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Runs the camera pipeline against the live Valhalla and Overpass servers. Skipped unless
 * OTR_LIVE_TESTS=1, so normal test runs stay offline:
 *
 *   OTR_LIVE_TESTS=1 ./gradlew :app:testDebugUnitTest --tests '*LiveCameraPipelineTest*' -i
 */
class LiveCameraPipelineTest {
  private val http =
      OkHttpClient.Builder().readTimeout(Duration.ofSeconds(90)).callTimeout(Duration.ofSeconds(90)).build()

  @Test
  fun `cameras along a real route`() = runBlocking {
    assumeTrue(System.getenv("OTR_LIVE_TESTS") == "1")

    // Helsinki centre to Espoo along Länsiväylä.
    val route = valhallaRoute(LatLng(60.1699, 24.9384), LatLng(60.2055, 24.6559))
    val repository =
        SpeedCameraRepository(OverpassClient(http), Files.createTempDirectory("overpass").toFile())

    val cameras = repository.camerasForRoute(route, maneuverPoints = emptyList())

    println("Route: ${route.size} points, ${MeasuredPolyline(route).length.toInt()} m")
    println("Cameras on route: ${cameras.size}")
    cameras.forEach {
      println(
          "  node ${it.camera.id} at ${it.alongRouteMetres.toInt()} m, " +
              "offset ${"%.1f".format(it.offsetMetres)} m, dir ${it.camera.directions}, " +
              "limit ${it.camera.maxSpeedKmh}"
      )
    }
  }

  private fun valhallaRoute(from: LatLng, to: LatLng): List<LatLng> {
    val body =
        """{"costing":"auto","shape_format":"polyline6","locations":[
          {"lat":${from.lat},"lon":${from.lng}},{"lat":${to.lat},"lon":${to.lng}}]}"""
    val request =
        Request.Builder()
            .url("https://valhalla1.openstreetmap.de/route")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
    val json = http.newCall(request).execute().use { it.body.string() }
    val shape =
        Json.parseToJsonElement(json)
            .jsonObject["trip"]!!
            .jsonObject["legs"]!!
            .jsonArray[0]
            .jsonObject["shape"]!!
            .jsonPrimitive
            .content
    return decodePolyline(shape, precision = 1e6)
  }

  private fun decodePolyline(encoded: String, precision: Double): List<LatLng> {
    val points = mutableListOf<LatLng>()
    var index = 0
    var lat = 0L
    var lng = 0L
    while (index < encoded.length) {
      for (axis in 0..1) {
        var result = 0L
        var shift = 0
        var b: Int
        do {
          b = encoded[index++].code - 63
          result = result or ((b and 0x1f).toLong() shl shift)
          shift += 5
        } while (b >= 0x20)
        val delta = if (result and 1L != 0L) (result shr 1).inv() else result shr 1
        if (axis == 0) lat += delta else lng += delta
      }
      points += LatLng(lat / precision, lng / precision)
    }
    return points
  }
}
