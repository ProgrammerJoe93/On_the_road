package com.joegineered.ontheroad.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class RouteOptionsTest {
  @Test
  fun `no avoidance sends no costing options`() {
    val options = valhallaOptions("fi-FI", metric = true)
    assertEquals(mapOf("language" to "fi-FI", "units" to "kilometers"), options)
  }

  @Test
  fun `avoided roads become Valhalla costing options`() {
    val options =
        valhallaOptions(
            "en-US",
            metric = false,
            avoid = RouteAvoidance(tolls = true, ferries = true),
            alternates = 2,
        )
    assertEquals("miles", options["units"])
    assertEquals(2, options["alternates"])
    assertEquals(mapOf("auto" to mapOf("use_tolls" to 0.0, "use_ferry" to 0.0)), options["costing_options"])
  }

  @Test
  fun `zero alternates are left out`() {
    assertFalse("alternates" in valhallaOptions("en-US", metric = true, alternates = 0))
  }

  @Test
  fun `routes are named after their longest road, each a different one`() {
    val motorway = listOf("Mannerheimintie" to 2_000.0, "E12" to 150_000.0, "Hämeenkatu" to 1_000.0)
    val alsoE12 = listOf("E12" to 60_000.0, "Route 130" to 90_000.0)
    val viaE12Too = listOf("E12" to 120_000.0, "Route 54" to 20_000.0)
    assertEquals(
        listOf("E12", "Route 130", "Route 54"),
        distinctiveRoads(listOf(motorway, alsoE12, viaE12Too)),
    )
  }

  @Test
  fun `distances of the same road add up and blank names are skipped`() {
    val route = listOf("A" to 500.0, null to 10_000.0, " " to 9_000.0, "B" to 700.0, "A" to 400.0)
    assertEquals(listOf("A"), distinctiveRoads(listOf(route)))
    assertEquals(listOf<String?>(null), distinctiveRoads(listOf(listOf(null to 100.0))))
  }
}
