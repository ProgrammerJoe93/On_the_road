package com.joegineered.ontheroad.stops

import com.joegineered.ontheroad.cameras.LatLng
import com.joegineered.ontheroad.cameras.MeasuredPolyline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RoadsideStopsTest {
  // A straight road east along 60°N, about 5.6 km long.
  private val road = MeasuredPolyline(listOf(LatLng(60.0, 24.0), LatLng(60.0, 24.1)))

  private fun stop(id: String, lat: Double, lng: Double, category: StopCategory = StopCategory.FUEL) =
      RoadsideStop(id, category, LatLng(lat, lng), name = id)

  @Test
  fun `parses nodes and ways, preferring the name, then the brand`() {
    val body =
        """
        {"elements":[
          {"type":"node","id":1,"lat":60.0,"lon":24.01,"tags":{"amenity":"fuel","brand":"Neste"}},
          {"type":"way","id":2,"center":{"lat":60.0,"lon":24.02},"tags":{"amenity":"fuel","name":"ABC Kehä","brand":"ABC"}},
          {"type":"node","id":3,"lat":60.0,"lon":24.03,"tags":{"amenity":"fuel","access":"private"}},
          {"type":"node","id":4,"lat":60.0,"lon":24.04,"tags":{"amenity":"fuel"}}
        ]}
        """
    val stops = RoadsideStops.parse(body, StopCategory.FUEL)
    assertEquals(listOf("node/1", "way/2", "node/4"), stops.map { it.id })
    assertEquals(listOf("Neste", "ABC Kehä", null), stops.map { it.name })
    assertEquals(LatLng(60.0, 24.02), stops[1].position)
  }

  @Test
  fun `chargers carry their plug details`() {
    val body =
        """{"elements":[{"type":"node","id":5,"lat":60.0,"lon":24.0,"tags":{"amenity":"charging_station","network":"K-Lataus","socket:type2_combo":"2","socket:type2_combo:output":"150 kW"}}]}"""
    val stop = RoadsideStops.parse(body, StopCategory.CHARGING).single()
    assertEquals("K-Lataus", stop.name)
    assertEquals("CCS2 150 kW", stop.charger?.headline)
  }

  @Test
  fun `stops ahead are sorted by distance along the route`() {
    val stops =
        listOf(
            stop("far", 60.001, 24.08),
            stop("near", 59.999, 24.03),
            stop("passed", 60.0, 24.005),
            stop("too far off", 60.01, 24.05),
        )
    val ahead = RoadsideStops.ahead(road, alongMetres = 1000.0, stops = stops)
    assertEquals(listOf("near", "far"), ahead.map { it.stop.id })
    // 24.03° is about 1.67 km along; the car is at 1 km.
    assertEquals(670.0, ahead[0].aheadMetres, 20.0)
    assertEquals(111.0, ahead[0].offRouteMetres, 2.0)
  }

  @Test
  fun `a stop just passed still counts, as the driver may be pulling in`() {
    val justPassed = stop("pulling in", 60.0, 24.0164) // about 910 m along
    val ahead = RoadsideStops.ahead(road, alongMetres = 1000.0, stops = listOf(justPassed)).single()
    assertEquals(0.0, ahead.aheadMetres, 0.0)
  }

  @Test
  fun `food must be closer to the route than fuel`() {
    val fuel = stop("fuel", 60.004, 24.05, StopCategory.FUEL) // about 445 m off the road
    val food = stop("food", 60.004, 24.05, StopCategory.FOOD)
    val ahead = RoadsideStops.ahead(road, alongMetres = 0.0, stops = listOf(fuel, food))
    assertEquals(listOf("fuel"), ahead.map { it.stop.id })
  }

  @Test
  fun `only the chunks overlapping the stretch ahead are searched`() {
    val chunks = listOf("a", "b", "c", "d")
    val lengths = listOf(100.0, 100.0, 100.0, 100.0)
    assertEquals(listOf("b", "c"), RoadsideStops.chunksBetween(chunks, lengths, 150.0, 250.0))
    assertEquals(listOf("d"), RoadsideStops.chunksBetween(chunks, lengths, 350.0, 10_000.0))
  }

  @Test
  fun `the query searches around the route with the category's radius`() {
    val query = RoadsideStops.alongQuery(StopCategory.REST, listOf(LatLng(60.0, 24.0), LatLng(60.1, 24.2)))
    assertTrue(query, query.contains("nwr[highway~\"^(rest_area|services)$\"](around:300,60.00000,24.00000,60.10000,24.20000)"))
    assertTrue(query.endsWith("out center tags;"))
    assertNull(RoadsideStops.parse("""{"elements":[]}""", StopCategory.REST).firstOrNull())
  }
}
