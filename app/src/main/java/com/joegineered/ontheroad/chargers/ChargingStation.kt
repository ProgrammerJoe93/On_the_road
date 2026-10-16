package com.joegineered.ontheroad.chargers

import com.joegineered.ontheroad.cameras.LatLng

/** Plug types, from OpenStreetMap's `socket:*` keys. */
enum class SocketType(val osmKeys: List<String>, val label: String, val dc: Boolean) {
  CCS(listOf("type2_combo", "tesla_supercharger_ccs"), "CCS", dc = true),
  CHADEMO(listOf("chademo"), "CHAdeMO", dc = true),
  TESLA(listOf("tesla_supercharger"), "Tesla", dc = true),
  TYPE2(listOf("type2", "type2_cable"), "Type 2", dc = false),
  SCHUKO(listOf("typee", "typef", "schuko"), "Schuko", dc = false),
}

data class Socket(val type: SocketType, val count: Int?, val outputKw: Double?)

/** An EV charging station from OpenStreetMap (`amenity=charging_station`). */
data class ChargingStation(
    /** OSM element, e.g. "node/123". */
    val id: String,
    val position: LatLng,
    val name: String?,
    val operator: String?,
    val network: String?,
    val sockets: List<Socket>,
    /** Number of vehicles that can charge at once. */
    val capacity: Int?,
    val fee: Boolean?,
    val openingHours: String?,
) {
  /** Best available name: few stations are named in OSM, so fall back to who runs them. */
  val title: String?
    get() = name ?: network ?: operator

  val maxPowerKw: Double?
    get() = sockets.mapNotNull { it.outputKw }.maxOrNull()

  /** DC fast charging (CCS, CHAdeMO, Tesla). */
  val isFast: Boolean
    get() = sockets.any { it.type.dc }

  val hasCcs: Boolean
    get() = sockets.any { it.type == SocketType.CCS }

  /** "CCS 150 kW", or the best AC option, for one-line summaries. */
  val headline: String?
    get() {
      val best =
          sockets.sortedWith(compareByDescending<Socket> { it.type.dc }.thenByDescending { it.outputKw ?: 0.0 })
              .firstOrNull() ?: return null
      return listOfNotNull(best.type.label, best.outputKw?.let { formatKw(it) }).joinToString(" ")
    }
}

fun formatKw(kw: Double): String =
    if (kw == kw.toLong().toDouble()) "${kw.toLong()} kW" else "%.1f kW".format(kw)

/**
 * Parses OSM tags into a [ChargingStation]. Returns null for stations not usable by the public
 * (`access=private/no`) or not for cars (`motorcar=no`).
 */
fun chargingStationFromTags(id: String, position: LatLng, tags: Map<String, String>): ChargingStation? {
  if (tags["access"] in setOf("private", "no")) return null
  if (tags["motorcar"] == "no") return null

  val sockets =
      SocketType.entries.mapNotNull { type ->
        val keys = type.osmKeys.filter { tags.containsKey("socket:$it") }
        if (keys.isEmpty()) return@mapNotNull null
        val count = keys.sumOf { tags["socket:$it"]?.toIntOrNull() ?: 0 }.takeIf { it > 0 }
        val output = keys.mapNotNull { parseOutputKw(tags["socket:$it:output"]) }.maxOrNull()
        Socket(type, count, output)
      }

  return ChargingStation(
      id = id,
      position = position,
      name = tags["name"],
      operator = tags["operator"],
      network = tags["network"] ?: tags["brand"],
      sockets = sockets,
      capacity = tags["capacity"]?.toIntOrNull(),
      fee = when (tags["fee"]) { "yes" -> true; "no" -> false; else -> null },
      openingHours = tags["opening_hours"],
  )
}

/** Parses outputs like "50 kW", "22kW;11 kW", "7400 W" into the highest value in kW. */
fun parseOutputKw(value: String?): Double? {
  if (value.isNullOrBlank()) return null
  return value.split(';', ',').mapNotNull { part ->
    val match = Regex("""([\d.]+)\s*(kw|w|mw)?""", RegexOption.IGNORE_CASE).find(part.trim()) ?: return@mapNotNull null
    val number = match.groupValues[1].toDoubleOrNull() ?: return@mapNotNull null
    when (match.groupValues[2].lowercase()) {
      "w" -> number / 1000
      "mw" -> number * 1000
      else -> number
    }
  }.maxOrNull()
}
