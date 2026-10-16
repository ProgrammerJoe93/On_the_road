package com.joegineered.ontheroad.traffic

import com.joegineered.ontheroad.cameras.LatLng
import java.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Parses Digitraffic road APIs (https://www.digitraffic.fi/en/road-traffic/). */
object DigitrafficParser {
  private val json = Json { ignoreUnknownKeys = true }

  /** Traffic announcements or roadworks (`/api/traffic-message/v2/...`), active at [now]. */
  fun parseMessages(body: String, now: Instant): List<TrafficEvent> {
    val features = json.parseToJsonElement(body).jsonObject["features"] as? JsonArray ?: return emptyList()
    return features.mapNotNull { (it as? JsonObject)?.toEvent(now) }
  }

  private fun JsonObject.toEvent(now: Instant): TrafficEvent? {
    val props = this["properties"] as? JsonObject ?: return null
    val announcement = (props["announcements"] as? JsonArray)?.firstOrNull() as? JsonObject ?: return null
    val lines = parseGeometry(this["geometry"] as? JsonObject)
    if (lines.isEmpty()) return null

    val isRoadwork = props.string("situationType") == "road work"
    val featureNames = (announcement["features"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.string("name") }
    val phases = (announcement["roadWorkPhases"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }

    // Roadworks are active while one of their phases is; announcements have their own window.
    val (start, end) =
        if (isRoadwork) {
          val active = phases.firstOrNull { phase -> phase.timeWindow().let { (s, e) -> isActive(s, e, now) } }
              ?: return null
          active.timeWindow()
        } else {
          (announcement["timeAndDuration"] as? JsonObject)?.timeWindow() ?: (null to null)
        }
    if (!isActive(start, end, now)) return null

    val restrictionTypes =
        phases.flatMap { phase ->
          (phase["restrictions"] as? JsonArray).orEmpty().mapNotNull { r ->
            val obj = r as? JsonObject ?: return@mapNotNull null
            val type = obj.string("type") ?: return@mapNotNull null
            val detail = obj["restriction"] as? JsonObject
            type to detail
          }
        }

    val closed =
        CLOSED_FEATURE in featureNames ||
            restrictionTypes.any { it.first == "road closed" || it.first == "single carriageway closed" }
    val kind =
        when {
          closed -> TrafficEventKind.ROAD_CLOSED
          props.string("trafficAnnouncementType")?.contains("accident") == true ||
              ACCIDENT_FEATURE in featureNames -> TrafficEventKind.ACCIDENT
          featureNames.any { it in CONGESTION_FEATURES } -> TrafficEventKind.CONGESTION
          isRoadwork -> TrafficEventKind.ROADWORKS
          else -> TrafficEventKind.OTHER
        }

    val restrictions =
        if (isRoadwork) restrictionTypes.mapNotNull { (type, detail) -> englishRestriction(type, detail) }.distinct()
        else featureNames.mapNotNull { FEATURE_LABELS[it] }.distinct()

    return TrafficEvent(
        id = props.string("situationId") ?: return null,
        kind = kind,
        title = announcement.string("title")?.trim().orEmpty(),
        description = (announcement["location"] as? JsonObject)?.string("description"),
        lines = lines,
        restrictions = restrictions,
        start = start,
        end = end,
    )
  }

  /**
   * Measuring stations where traffic flows below [slowPercent] of its free-flow speed in either
   * direction, from `/api/tms/v1/stations/data` and the station locations in `/api/tms/v1/stations`.
   */
  fun parseSlowTraffic(dataBody: String, stations: Map<Int, LatLng>, slowPercent: Double): List<SlowTraffic> {
    val list = json.parseToJsonElement(dataBody).jsonObject["stations"] as? JsonArray ?: return emptyList()
    return list.mapNotNull { element ->
      val station = element as? JsonObject ?: return@mapNotNull null
      val id = station["id"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
      val position = stations[id] ?: return@mapNotNull null
      val values =
          (station["sensorValues"] as? JsonArray).orEmpty().mapNotNull { v ->
            val obj = v as? JsonObject ?: return@mapNotNull null
            val name = obj.string("name") ?: return@mapNotNull null
            val value = obj["value"]?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
            name to value
          }.toMap()
      // Percent of free-flow speed; 0 means no vehicles were measured, not a standstill.
      val p1 = values["KESKINOPEUS_5MIN_LIUKUVA_SUUNTA1_VVAPAAS1"]?.takeIf { it > 0 }
      val p2 = values["KESKINOPEUS_5MIN_LIUKUVA_SUUNTA2_VVAPAAS2"]?.takeIf { it > 0 }
      val slow = listOfNotNull(p1, p2).any { it < slowPercent }
      if (!slow) return@mapNotNull null
      SlowTraffic(
          stationId = id,
          position = position,
          direction1Percent = p1,
          direction2Percent = p2,
          direction1Kmh = values["KESKINOPEUS_5MIN_LIUKUVA_SUUNTA1"],
          direction2Kmh = values["KESKINOPEUS_5MIN_LIUKUVA_SUUNTA2"],
      )
    }
  }

  /** Station id to location, from the `/api/tms/v1/stations` GeoJSON (stations still collecting). */
  fun parseStations(body: String): Map<Int, LatLng> {
    val features = json.parseToJsonElement(body).jsonObject["features"] as? JsonArray ?: return emptyMap()
    return features.mapNotNull { f ->
      val obj = f as? JsonObject ?: return@mapNotNull null
      val props = obj["properties"] as? JsonObject ?: return@mapNotNull null
      if (props.string("collectionStatus") != "GATHERING") return@mapNotNull null
      val id = props["id"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
      val point = parseGeometry(obj["geometry"] as? JsonObject).flatten().firstOrNull() ?: return@mapNotNull null
      id to point
    }.toMap()
  }

  private fun parseGeometry(geometry: JsonObject?): List<List<LatLng>> {
    geometry ?: return emptyList()
    val coordinates = geometry["coordinates"] ?: return emptyList()
    fun point(e: JsonElement): LatLng? {
      val a = e as? JsonArray ?: return null
      val lng = a.getOrNull(0)?.jsonPrimitive?.doubleOrNull ?: return null
      val lat = a.getOrNull(1)?.jsonPrimitive?.doubleOrNull ?: return null
      return LatLng(lat, lng)
    }
    fun line(e: JsonElement) = (e as? JsonArray).orEmpty().mapNotNull(::point)
    return when (geometry.string("type")) {
      "Point" -> listOfNotNull(point(coordinates)).map { listOf(it) }
      "LineString" -> listOf(line(coordinates)).filter { it.isNotEmpty() }
      "MultiLineString" -> coordinates.jsonArray.map(::line).filter { it.isNotEmpty() }
      else -> emptyList()
    }
  }

  private fun JsonObject.timeWindow(): Pair<Instant?, Instant?> {
    val window = (this["timeAndDuration"] as? JsonObject) ?: this
    return window.instant("startTime") to window.instant("endTime")
  }

  private fun isActive(start: Instant?, end: Instant?, now: Instant) =
      (start == null || !start.isAfter(now)) && (end == null || end.isAfter(now))

  private fun JsonObject.string(key: String) = this[key]?.jsonPrimitive?.contentOrNull

  private fun JsonObject.instant(key: String) = string(key)?.let { runCatching { Instant.parse(it) }.getOrNull() }

  private fun englishRestriction(type: String, detail: JsonObject?): String? {
    val label = RESTRICTION_LABELS[type] ?: return null
    val quantity = detail?.get("quantity")?.jsonPrimitive?.doubleOrNull
    val unit = detail?.string("unit")
    return if (quantity != null && unit != null && type in QUANTIFIED) "$label ${quantity.toInt()} $unit" else label
  }

  private const val CLOSED_FEATURE = "Tie on suljettu liikenteeltä"
  private const val ACCIDENT_FEATURE = "Onnettomuus"
  private val CONGESTION_FEATURES =
      setOf("Liikenne ruuhkautuu", "Liikenne on jonoutunut", "Liikenne saattaa ruuhkautua")

  /** English labels for the announcement features that matter to drivers. */
  private val FEATURE_LABELS =
      mapOf(
          "Onnettomuus" to "Accident",
          "Tie on suljettu liikenteeltä" to "Road closed",
          "Ajokaista suljettu liikenteeltä" to "Lane closed",
          "Ajokaistoja suljettu liikenteeltä" to "Lanes closed",
          "Liikenne ruuhkautuu" to "Congestion",
          "Liikenne on jonoutunut" to "Queues",
          "Liikenne saattaa ruuhkautua" to "Congestion possible",
          "Liikennejärjestelyt ovat muuttuneet" to "Changed traffic arrangements",
          "Tapahtumapaikalla on käytössä kiertotie" to "Detour in use",
          "Paikalla on kiertotieopastus" to "Detour signposted",
          "Liikenne pysäytetään ajoittain" to "Traffic stopped at times",
          "Lautan kantavuus on muuttunut" to "Ferry weight limit changed",
      )

  /** English labels for roadwork restriction types (Digitraffic's own codes). */
  private val RESTRICTION_LABELS =
      mapOf(
          "road closed" to "Road closed",
          "single carriageway closed" to "Carriageway closed",
          "single lane closed" to "Lane closed",
          "multiple lanes closed" to "Lanes closed",
          "narrow lanes" to "Narrow lanes",
          "speed limit" to "Speed limit",
          "single alternate line traffic" to "Alternating one-lane traffic",
          "traffic lights" to "Temporary traffic lights",
          "intermittent short term stops" to "Short stops",
          "intermittent short term closure" to "Short closures",
          "detour" to "Detour",
          "estimated delay" to "Estimated delay",
          "slow moving maintenance vehicle" to "Slow work vehicles",
          "contra flow traffic" to "Contraflow",
      )
  private val QUANTIFIED = setOf("speed limit", "estimated delay")
}
