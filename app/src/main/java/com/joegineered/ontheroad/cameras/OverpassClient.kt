package com.joegineered.ontheroad.cameras

import java.io.IOException
import java.util.Locale
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request

/** Queries the OpenStreetMap Overpass API for speed cameras and nearby roads. */
class OverpassClient(
    private val httpClient: OkHttpClient,
    private val endpoints: List<String> = DEFAULT_ENDPOINTS,
    private val maxAttempts: Int = 4,
    private val initialBackoff: Duration = 2.seconds,
) {

  /**
   * Raw Overpass JSON for [query]. Public Overpass servers reject queries when busy (HTTP 429 or
   * 502-504), so this retries with exponential backoff, rotating through [endpoints].
   */
  suspend fun execute(query: String): String {
    var backoff = initialBackoff
    var lastError: IOException? = null
    repeat(maxAttempts) { attempt ->
      val endpoint = endpoints[attempt % endpoints.size]
      try {
        return post(endpoint, query)
      } catch (e: RetryableOverpassException) {
        lastError = e
      } catch (e: IOException) {
        lastError = e
      }
      if (attempt < maxAttempts - 1) {
        delay(backoff)
        backoff *= 2
      }
    }
    throw lastError ?: IOException("Overpass query failed")
  }

  private suspend fun post(endpoint: String, query: String): String =
      withContext(Dispatchers.IO) {
        val request =
            Request.Builder()
                .url(endpoint)
                .header("User-Agent", USER_AGENT)
                .post(FormBody.Builder().add("data", query).build())
                .build()
        httpClient.newCall(request).execute().use { response ->
          when {
            response.isSuccessful -> response.body.string()
            response.code == 429 || response.code in 502..504 ->
                throw RetryableOverpassException("Overpass busy: HTTP ${response.code} from $endpoint")
            else -> throw IOException("Overpass HTTP ${response.code} from $endpoint")
          }
        }
      }

  private class RetryableOverpassException(message: String) : IOException(message)

  companion object {
    private const val USER_AGENT = "OnTheRoad/0.1 (Android navigation app)"

    val DEFAULT_ENDPOINTS =
        listOf(
            "https://overpass-api.de/api/interpreter",
            "https://overpass.private.coffee/api/interpreter",
        )

    /**
     * Speed cameras within [radiusMetres] of a polyline, plus any enforcement relations they belong
     * to and those relations' member nodes (the `from`/`to` points of the enforced section).
     */
    fun camerasAlongQuery(polyline: List<LatLng>, radiusMetres: Int = 50): String {
      val around = "around:$radiusMetres," + polyline.joinToString(",") { it.format() }
      return """
        [out:json][timeout:60];
        node[highway=speed_camera]($around)->.cams;
        rel(bn.cams)[type=enforcement]->.rels;
        node(r.rels)->.members;
        (.cams; .rels; .members;);
        out body;
      """
          .trimIndent()
    }

    /** Roads within [radiusMetres] of each point, with their geometry. */
    fun roadsNearQuery(points: List<LatLng>, radiusMetres: Int = 40): String {
      val clauses =
          points.joinToString("\n") {
            "  way[highway~\"$DRIVABLE_HIGHWAYS\"](around:$radiusMetres,${it.format()});"
          }
      return "[out:json][timeout:60];\n(\n$clauses\n);\nout geom;"
    }

    private const val DRIVABLE_HIGHWAYS =
        "^(motorway|trunk|primary|secondary|tertiary|unclassified|residential|living_street|" +
            "service|road|motorway_link|trunk_link|primary_link|secondary_link|tertiary_link)$"

    private fun LatLng.format() = String.format(Locale.ROOT, "%.6f,%.6f", lat, lng)

    private val json = Json { ignoreUnknownKeys = true }

    /** Parses a [camerasAlongQuery] response. */
    fun parseCameras(body: String): List<SpeedCamera> {
      val elements = elements(body)
      val nodes =
          elements
              .filter { it.type == "node" }
              .associateBy { it.id }

      // Camera node id -> enforcement relation details.
      data class Enforcement(val from: LatLng?, val maxSpeed: Int?)
      val enforcements = mutableMapOf<Long, Enforcement>()
      for (relation in elements.filter { it.type == "relation" }) {
        val from = relation.members.firstOrNull { it.role == "from" }?.let { nodes[it.ref]?.position }
        val maxSpeed = parseMaxSpeedKmh(relation.tags["maxspeed"])
        relation.members
            .filter { it.role == "device" && it.type == "node" }
            .forEach { enforcements[it.ref] = Enforcement(from, maxSpeed) }
      }

      return nodes.values
          .filter { it.tags["highway"] == "speed_camera" }
          .mapNotNull { node ->
            val position = node.position ?: return@mapNotNull null
            val enforcement = enforcements[node.id]
            SpeedCamera(
                id = node.id,
                position = position,
                directions = parseDirectionTag(node.tags["direction"]),
                maxSpeedKmh = parseMaxSpeedKmh(node.tags["maxspeed"]) ?: enforcement?.maxSpeed,
                enforcementFrom = enforcement?.from,
            )
          }
    }

    /** Parses a [roadsNearQuery] response into road geometries. */
    fun parseRoads(body: String): List<List<LatLng>> =
        elements(body)
            .filter { it.type == "way" }
            .distinctBy { it.id }
            .map { it.geometry }
            .filter { it.size >= 2 }

    private data class Member(val type: String, val ref: Long, val role: String)

    private data class Element(
        val type: String,
        val id: Long,
        val position: LatLng?,
        val tags: Map<String, String>,
        val members: List<Member>,
        val geometry: List<LatLng>,
    )

    private fun elements(body: String): List<Element> {
      val root = json.parseToJsonElement(body).jsonObject
      val elements = root["elements"] as? JsonArray ?: return emptyList()
      return elements.map { it.jsonObject.toElement() }
    }

    private fun JsonObject.toElement(): Element {
      val lat = this["lat"]?.jsonPrimitive?.doubleOrNull
      val lon = this["lon"]?.jsonPrimitive?.doubleOrNull
      return Element(
          type = this["type"]?.jsonPrimitive?.contentOrNull.orEmpty(),
          id = this["id"]?.jsonPrimitive?.longOrNull ?: 0L,
          position = if (lat != null && lon != null) LatLng(lat, lon) else null,
          tags =
              (this["tags"] as? JsonObject)
                  ?.mapValues { it.value.jsonPrimitive.contentOrNull.orEmpty() }
                  .orEmpty(),
          members =
              (this["members"] as? JsonArray)
                  ?.map { member ->
                    val m = member.jsonObject
                    Member(
                        type = m["type"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                        ref = m["ref"]?.jsonPrimitive?.longOrNull ?: 0L,
                        role = m["role"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    )
                  }
                  .orEmpty(),
          geometry =
              (this["geometry"] as? JsonArray)
                  ?.mapNotNull { point ->
                    val p = point.jsonObject
                    val pLat = p["lat"]?.jsonPrimitive?.doubleOrNull
                    val pLon = p["lon"]?.jsonPrimitive?.doubleOrNull
                    if (pLat != null && pLon != null) LatLng(pLat, pLon) else null
                  }
                  .orEmpty(),
      )
    }
  }
}
