package com.joegineered.ontheroad.traffic

import com.joegineered.ontheroad.cameras.LatLng
import com.joegineered.ontheroad.cameras.distanceMetres
import java.time.Instant
import java.util.Locale
import kotlin.math.abs
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

  /**
   * Road weather forecast sections (`/api/weather/v2/forecast-sections-simple`): main road
   * stretches, tens of kilometres long, with sparse rounded coordinates. Lines are filled in to a
   * point every [ROAD_WEATHER_SPACING_METRES] so that they can be matched to a route.
   */
  fun parseRoadWeatherSections(body: String): Map<String, RoadWeatherSection> {
    val features = json.parseToJsonElement(body).jsonObject["features"] as? JsonArray ?: return emptyMap()
    return features.mapNotNull { f ->
      val obj = f as? JsonObject ?: return@mapNotNull null
      val props = obj["properties"] as? JsonObject
      val id = obj.string("id") ?: props?.string("id") ?: return@mapNotNull null
      val lines = parseGeometry(obj["geometry"] as? JsonObject).filter { it.size >= 2 }.map(::densify)
      if (lines.isEmpty()) return@mapNotNull null
      id to RoadWeatherSection(id, props?.string("description"), lines)
    }.toMap()
  }

  /**
   * Sections with poor or very poor driving conditions now, from
   * `/api/weather/v2/forecast-sections-simple/forecasts`. Each section has an observation and
   * forecasts a few hours apart; the one nearest [now] is used.
   */
  fun parseRoadWeather(body: String, sections: Map<String, RoadWeatherSection>, now: Instant): List<TrafficEvent> {
    val list = json.parseToJsonElement(body).jsonObject["forecastSections"] as? JsonArray ?: return emptyList()
    return list.mapNotNull { element ->
      val obj = element as? JsonObject ?: return@mapNotNull null
      val section = obj.string("id")?.let { sections[it] } ?: return@mapNotNull null
      val forecast =
          (obj["forecasts"] as? JsonArray).orEmpty()
              .mapNotNull { f -> (f as? JsonObject)?.let { it to (it.instant("time") ?: return@mapNotNull null) } }
              .minByOrNull { (_, time) -> abs(time.epochSecond - now.epochSecond) }
              ?.first ?: return@mapNotNull null
      val kind =
          when (forecast.string("overallRoadCondition")) {
            "POOR_CONDITION" -> TrafficEventKind.POOR_CONDITIONS
            "EXTREMELY_POOR_CONDITION" -> TrafficEventKind.VERY_POOR_CONDITIONS
            else -> return@mapNotNull null
          }
      val roadCondition = forecast.string("roadCondition")
      val roadTemperature = forecast["roadTemperature"]?.jsonPrimitive?.doubleOrNull
      TrafficEvent(
          id = "weather-${section.id}",
          kind = kind,
          // E.g. "Vt 1: Helsinki - Kehä III".
          title = section.description?.trim().orEmpty(),
          description = null,
          lines = section.lines,
          restrictions =
              listOfNotNull(
                  ROAD_CONDITION_LABELS[roadCondition],
                  roadTemperature?.let(::roadSurfaceLabel),
              ),
          start = null,
          end = null,
          slippery = roadCondition in SLIPPERY_CONDITIONS,
      )
    }
  }

  /** Road weather stations still collecting data (`/api/weather/v2/stations`), by id. */
  fun parseWeatherStations(body: String): Map<Int, WeatherStation> {
    val features = json.parseToJsonElement(body).jsonObject["features"] as? JsonArray ?: return emptyMap()
    return features.mapNotNull { f ->
      val obj = f as? JsonObject ?: return@mapNotNull null
      val props = obj["properties"] as? JsonObject ?: return@mapNotNull null
      if (props.string("collectionStatus") != "GATHERING") return@mapNotNull null
      val id = props["id"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
      val point = parseGeometry(obj["geometry"] as? JsonObject).flatten().firstOrNull() ?: return@mapNotNull null
      id to WeatherStation(id, props.string("name")?.let(::stationName).orEmpty(), point)
    }.toMap()
  }

  /**
   * Hazards measured at road weather stations (`/api/weather/v2/stations/data`): a slippery road
   * (ice, snow, slush or frost on it, the station's own slipperiness or frost warning, freezing
   * rain, low grip, or a wet road below freezing) and poor visibility. Readings older than
   * [MAX_STATION_READING_AGE_SECONDS] are ignored, as the station may have stopped reporting.
   */
  fun parseWeatherStationHazards(body: String, stations: Map<Int, WeatherStation>, now: Instant): List<TrafficEvent> {
    val list = json.parseToJsonElement(body).jsonObject["stations"] as? JsonArray ?: return emptyList()
    return list.flatMap { element ->
      val obj = element as? JsonObject ?: return@flatMap emptyList()
      val station = obj["id"]?.jsonPrimitive?.intOrNull?.let { stations[it] } ?: return@flatMap emptyList()
      val readings =
          (obj["sensorValues"] as? JsonArray).orEmpty().mapNotNull { v ->
            val sensor = v as? JsonObject ?: return@mapNotNull null
            val name = sensor.string("name")?.takeIf { it in STATION_SENSORS } ?: return@mapNotNull null
            val value = sensor["value"]?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
            val measured = sensor.instant("measuredTime") ?: return@mapNotNull null
            if (measured.isBefore(now.minusSeconds(MAX_STATION_READING_AGE_SECONDS))) return@mapNotNull null
            name to StationReading(value, measured)
          }.toMap()
      stationHazards(station, readings)
    }
  }

  private data class StationReading(val value: Double, val measured: Instant)

  private fun stationHazards(station: WeatherStation, readings: Map<String, StationReading>): List<TrafficEvent> {
    fun value(name: String) = readings[name]?.value
    // Stations have one or two road sensors, usually one per direction.
    val sensors = listOf(1, 2)
    val roadTemperature = sensors.mapNotNull { value("TIE_$it") }.minOrNull()

    val slippery = buildList {
      sensors.forEach { n ->
        val surface = value("KELI_$n")?.toInt()
        STATION_SURFACE_LABELS[surface]?.let(::add)
        if (surface == SURFACE_WET && value("TIE_$n")?.let { it <= 0.0 } == true) add("Wet road below freezing")
        STATION_WARNING_LABELS[value("VAROITUS_$n")?.toInt()]?.let(::add)
        value("KITKA$n")?.takeIf { it > 0.0 && it < LOW_FRICTION }?.let { add("Low grip (friction %.2f)".format(Locale.ROOT, it)) }
      }
      FREEZING_PRECIPITATION_LABELS[value("SATEEN_OLOMUOTO_PWDXX")?.toInt()]?.let(::add)
    }.distinct()
    val visibility = value("NÄKYVYYS_M")?.takeIf { it > 0.0 && it < POOR_VISIBILITY_METRES }

    val measured = readings.values.maxOfOrNull { it.measured }
    fun hazard(kind: TrafficEventKind, idPrefix: String, details: List<String>) =
        TrafficEvent(
            id = "$idPrefix-${station.id}",
            kind = kind,
            title = station.name,
            description = null,
            lines = listOf(listOf(station.position)),
            restrictions = details,
            start = measured,
            end = null,
            slippery = kind == TrafficEventKind.SLIPPERY_ROAD,
        )
    return listOfNotNull(
        slippery.takeIf { it.isNotEmpty() }?.let {
          hazard(TrafficEventKind.SLIPPERY_ROAD, "station-slippery", it + listOfNotNull(roadTemperature?.let(::roadSurfaceLabel)))
        },
        visibility?.let { hazard(TrafficEventKind.POOR_VISIBILITY, "station-visibility", listOf("Visibility ${Math.round(it)} m")) },
    )
  }

  /** "vt1_Espoo_Nupuri" as "Vt 1, Espoo, Nupuri". */
  private fun stationName(name: String): String =
      name.split('_').filter { it.isNotBlank() }.mapIndexed { i, part ->
        val road = STATION_ROAD.matchEntire(part)
        if (i == 0 && road != null) "${road.groupValues[1].lowercase().replaceFirstChar { it.uppercase() }} ${road.groupValues[2]}"
        else part
      }.joinToString(", ")

  /** With a no-break space, so that "°C" doesn't wrap onto a line of its own. */
  private fun roadSurfaceLabel(celsius: Double) = "Road surface ${Math.round(celsius)}\u00A0°C"

  /**
   * Variable signs (`/api/variable-sign/v1/signs`) as they show now. Blank warning and message
   * signs, and signs reported as broken, are left out; blank speed limit signs are kept, as they
   * end a variable limit.
   */
  fun parseVariableSigns(body: String): List<VariableSign> {
    val features = json.parseToJsonElement(body).jsonObject["features"] as? JsonArray ?: return emptyList()
    return features.mapNotNull { f ->
      val obj = f as? JsonObject ?: return@mapNotNull null
      val props = obj["properties"] as? JsonObject ?: return@mapNotNull null
      if (props.string("reliability") == "MALFUNCTION") return@mapNotNull null
      val position = parseGeometry(obj["geometry"] as? JsonObject).flatten().firstOrNull() ?: return@mapNotNull null
      val type =
          when (props.string("type")) {
            "SPEEDLIMIT" -> VariableSignType.SPEED_LIMIT
            "WARNING" -> VariableSignType.WARNING
            "INFORMATION" -> VariableSignType.INFORMATION
            else -> return@mapNotNull null
          }
      val value = props.string("displayValue")?.trim()?.takeIf { it.isNotEmpty() }
      val screens =
          (props["textRows"] as? JsonArray).orEmpty()
              .mapNotNull { row ->
                val r = row as? JsonObject ?: return@mapNotNull null
                val text = r.string("text")?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                Triple(r["screen"]?.jsonPrimitive?.intOrNull ?: 1, r["rowNumber"]?.jsonPrimitive?.intOrNull ?: 0, text)
              }
              .groupBy { it.first }
              .toSortedMap()
              .values
              .map { rows -> rows.sortedBy { it.second }.map { it.third } }
      val sign =
          VariableSign(
              id = props.string("id") ?: return@mapNotNull null,
              type = type,
              position = position,
              address = props.string("roadAddress")?.let(::parseRoadAddress),
              direction =
                  when (props.string("direction")) {
                    "INCREASING" -> RoadDirection.INCREASING
                    "DECREASING" -> RoadDirection.DECREASING
                    else -> null
                  },
              speedLimitKmh = if (type == VariableSignType.SPEED_LIMIT) value?.toIntOrNull() else null,
              warningCode = if (type != VariableSignType.SPEED_LIMIT) value else null,
              screens = screens,
          )
      when {
        type == VariableSignType.SPEED_LIMIT -> sign
        sign.warningCode == null && sign.screens.isEmpty() -> null
        else -> sign
      }
    }
  }

  /** "1 6 3577": road 1, part 6, 3577 m into the part. */
  private fun parseRoadAddress(text: String): RoadAddress? {
    val parts = text.trim().split(Regex("\\s+")).map { it.toIntOrNull() ?: return null }
    return if (parts.size == 3) RoadAddress(parts[0], parts[1], parts[2]) else null
  }

  private fun densify(line: List<LatLng>): List<LatLng> =
      line.zipWithNext().flatMap { (a, b) ->
        val steps = (distanceMetres(a, b) / ROAD_WEATHER_SPACING_METRES).toInt().coerceAtLeast(1)
        (0 until steps).map { i ->
          val t = i.toDouble() / steps
          LatLng(a.lat + t * (b.lat - a.lat), a.lng + t * (b.lng - a.lng))
        }
      } + line.last()

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

  private const val ROAD_WEATHER_SPACING_METRES = 250.0

  /** English labels for road weather road conditions; dry and moist roads go unmentioned. */
  private val ROAD_CONDITION_LABELS =
      mapOf(
          "WET" to "Wet road",
          "SLUSH" to "Slushy road",
          "FROST" to "Frost",
          "PARTLY_ICY" to "Partly icy",
          "ICE" to "Icy road",
          "SNOW" to "Snowy road",
      )
  private val SLIPPERY_CONDITIONS = setOf("SLUSH", "FROST", "PARTLY_ICY", "ICE", "SNOW")

  /** Road weather station sensors that hazards are read from. */
  private val STATION_SENSORS =
      setOf(
          "TIE_1", "TIE_2", "KELI_1", "KELI_2", "VAROITUS_1", "VAROITUS_2", "KITKA1", "KITKA2",
          "SATEEN_OLOMUOTO_PWDXX", "NÄKYVYYS_M",
      )
  /** Main road classes in station names: "vt1" is highway 1 ("valtatie"). */
  private val STATION_ROAD = Regex("^(vt|kt|st|mt|yt)(\\d+)$", RegexOption.IGNORE_CASE)
  private const val MAX_STATION_READING_AGE_SECONDS = 3_600L

  /** Road surface states (`KELI_n`) that make the road slippery; 3 is wet, 4 wet and salted. */
  private val STATION_SURFACE_LABELS = mapOf(5 to "Frost", 6 to "Snowy road", 7 to "Icy road", 9 to "Slushy road")
  private const val SURFACE_WET = 3

  /** The station's own warnings (`VAROITUS_n`); 1, "beware", is too common to warn about. */
  private val STATION_WARNING_LABELS = mapOf(2 to "Slippery road warning", 3 to "Frost warning")

  /** Precipitation types (`SATEEN_OLOMUOTO_PWDXX`) that freeze on the road. */
  private val FREEZING_PRECIPITATION_LABELS = mapOf(18 to "Freezing drizzle", 19 to "Freezing rain")

  /** Dry asphalt measures about 0.8; snow and ice are 0.1–0.4. */
  private const val LOW_FRICTION = 0.4
  private const val POOR_VISIBILITY_METRES = 200.0
}

/** A road weather station, e.g. "Vt 1, Espoo, Nupuri". */
data class WeatherStation(val id: Int, val name: String, val position: LatLng)

/** A main road stretch that Fintraffic gives road weather forecasts for. */
data class RoadWeatherSection(val id: String, val description: String?, val lines: List<List<LatLng>>)
