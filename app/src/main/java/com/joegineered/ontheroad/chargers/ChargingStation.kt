package com.joegineered.ontheroad.chargers

import com.joegineered.ontheroad.cameras.LatLng

/**
 * Plug types, from OpenStreetMap's `socket:*` keys (see taginfo for usage). CCS2 and Type 2 are
 * the European standards; CCS1 and Type 1 their North American counterparts. Rarely tagged
 * household sockets are grouped, so the filter list stays short.
 */
enum class SocketType(
    val osmKeys: List<String>,
    val label: String,
    val dc: Boolean,
    /** Keys of this type that are AC, for types with both DC and AC variants. */
    val acKeys: Set<String> = emptySet(),
) {
  // Tesla's European Superchargers are tagged tesla_supercharger_ccs and are plain CCS2.
  CCS2(listOf("type2_combo", "tesla_supercharger_ccs"), "CCS2", dc = true),
  TYPE2(listOf("type2", "type2_cable"), "Type 2", dc = false),
  CHADEMO(listOf("chademo"), "CHAdeMO", dc = true),
  // Tesla's own connector: NACS (SAE J3400) in North America, and older Tesla-only
  // Superchargers and wall connectors.
  TESLA(
      listOf("tesla_supercharger", "nacs", "tesla_standard", "tesla_destination", "tesla"),
      "Tesla / NACS",
      dc = true,
  ),
  CCS1(listOf("type1_combo"), "CCS1", dc = true),
  TYPE1(listOf("type1", "type1_cable", "j1772"), "Type 1", dc = false),
  GBT(listOf("gb_dc", "gb_ac"), "GB/T", dc = true, acKeys = setOf("gb_ac")),
  TYPE3(listOf("type3", "type3a", "type3c"), "Type 3", dc = false),
  // Continental Europe's household sockets: Type F ("Schuko") and the French Type E.
  SCHUKO(listOf("typee", "typef", "schuko"), "Household (Type E/F)", dc = false),
  // A/B (North America, Japan), G (UK, Ireland), I (Australia, China), J (Switzerland),
  // K (Denmark), L (Italy).
  HOUSEHOLD_OTHER(
      listOf("nema_1_15", "nema_5_15", "nema_5_20", "bs1363", "as3112", "sev1011_t13", "typek", "typeL", "domestic"),
      "Household (other countries)",
      dc = false,
  ),
  CEE_BLUE(listOf("cee_blue"), "Camping (CEE blue)", dc = false),
  CEE_RED(listOf("cee_red_16a", "cee_red_32a", "cee_red_63a", "cee_red_125a", "cee_red", "sev1011_t23", "sev1011_t25"), "Industrial (CEE red)", dc = false),
  NEMA_RV(listOf("nema_14_30", "nema_14_50", "nema_TT_30"), "RV outlets (NEMA)", dc = false),
}

data class Socket(
    val type: SocketType,
    val count: Int?,
    val outputKw: Double?,
    /** DC fast charging. */
    val dc: Boolean = type.dc,
)

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
    get() = sockets.any { it.dc }

  val hasCcs: Boolean
    get() = sockets.any { it.type == SocketType.CCS2 }

  /**
   * Whether the station has one of the [plugTypes]. With every type selected, stations without
   * plug details in OSM are shown too; once the user narrows the filter, only stations known to
   * have a matching plug are.
   */
  fun matches(plugTypes: Set<SocketType>): Boolean =
      plugTypes.containsAll(SocketType.entries) || sockets.any { it.type in plugTypes }

  /** "CCS 150 kW", or the best AC option, for one-line summaries. */
  val headline: String?
    get() {
      val best =
          sockets.sortedWith(compareByDescending<Socket> { it.dc }.thenByDescending { it.outputKw ?: 0.0 })
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
        Socket(type, count, output, dc = type.dc && keys.any { it !in type.acKeys })
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
