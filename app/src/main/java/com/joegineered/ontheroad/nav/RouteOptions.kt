package com.joegineered.ontheroad.nav

/**
 * Kinds of road the router avoids. Avoiding is a strong preference, not an exclusion, so places
 * only reachable by ferry (islands) or a toll road still get a route.
 */
data class RouteAvoidance(
    val tolls: Boolean = false,
    val motorways: Boolean = false,
    val ferries: Boolean = false,
)

/**
 * Valhalla request options: instruction [language], [metric] units for spoken distances, roads
 * to [avoid], and how many [alternates] to return besides the best route.
 */
fun valhallaOptions(
    language: String,
    metric: Boolean,
    avoid: RouteAvoidance = RouteAvoidance(),
    alternates: Int = 0,
): Map<String, Any> = buildMap {
  put("language", language)
  put("units", if (metric) "kilometers" else "miles")
  if (alternates > 0) put("alternates", alternates)
  // 0.0 is Valhalla's strongest avoidance short of excluding the road.
  val costing =
      buildMap<String, Double> {
        if (avoid.tolls) put("use_tolls", 0.0)
        if (avoid.motorways) put("use_highways", 0.0)
        if (avoid.ferries) put("use_ferry", 0.0)
      }
  if (costing.isNotEmpty()) put("costing_options", mapOf("auto" to costing))
}

/**
 * A short name for each route ("via E12"), so routes can be told apart: the road each route
 * spends most distance on, skipping roads already used to name an earlier route. Each route is
 * given as its steps' road names and distances. A route with no named road left gets null.
 */
fun distinctiveRoads(routes: List<List<Pair<String?, Double>>>): List<String?> {
  val used = mutableSetOf<String>()
  return routes.map { steps ->
    steps
        .mapNotNull { (name, distance) -> name?.trim()?.takeIf { it.isNotEmpty() }?.let { it to distance } }
        .groupBy({ it.first }, { it.second })
        .mapValues { (_, distances) -> distances.sum() }
        .entries
        .sortedByDescending { it.value }
        .firstOrNull { it.key !in used }
        ?.key
        ?.also { used += it }
  }
}
