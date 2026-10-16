package com.joegineered.ontheroad.chargers

import com.joegineered.ontheroad.cameras.LatLng
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChargerParsingTest {
  private val here = LatLng(60.17, 24.94)

  // Real tags from a Helen station in Helsinki.
  private val helen =
      mapOf(
          "amenity" to "charging_station", "capacity" to "4", "network" to "Helen",
          "operator" to "Helen", "opening_hours" to "24/7",
          "socket:chademo" to "1", "socket:chademo:output" to "50 kW",
          "socket:type2" to "3", "socket:type2:output" to "22 kW",
          "socket:type2_combo" to "1", "socket:type2_combo:output" to "50 kW",
      )

  @Test
  fun `parses sockets, power and details`() {
    val station = chargingStationFromTags("node/1", here, helen)!!

    assertEquals("Helen", station.title)
    assertEquals(4, station.capacity)
    assertEquals(50.0, station.maxPowerKw!!, 0.0)
    assertTrue(station.isFast)
    assertTrue(station.hasCcs)
    assertEquals("CCS 50 kW", station.headline)
    val type2 = station.sockets.single { it.type == SocketType.TYPE2 }
    assertEquals(3, type2.count)
    assertEquals(22.0, type2.outputKw!!, 0.0)
  }

  @Test
  fun `AC-only station is not fast and headlines its best AC plug`() {
    val station =
        chargingStationFromTags(
            "node/2", here, mapOf("socket:type2" to "2", "socket:type2:output" to "11 kW", "operator" to "K-Lataus"),
        )!!

    assertFalse(station.isFast)
    assertEquals("Type 2 11 kW", station.headline)
    assertEquals("K-Lataus", station.title)
  }

  @Test
  fun `private and non-car chargers are skipped`() {
    assertNull(chargingStationFromTags("node/3", here, mapOf("access" to "private")))
    assertNull(chargingStationFromTags("node/4", here, mapOf("motorcar" to "no")))
  }

  @Test
  fun `name wins over network and operator`() {
    val station =
        chargingStationFromTags("node/5", here, mapOf("name" to "Kamppi P", "network" to "Virta"))!!
    assertEquals("Kamppi P", station.title)
  }

  @Test
  fun `parses power outputs in different forms`() {
    assertEquals(50.0, parseOutputKw("50 kW")!!, 0.0)
    assertEquals(22.0, parseOutputKw("22kW;11 kW")!!, 0.0)
    assertEquals(7.4, parseOutputKw("7400 W")!!, 0.001)
    assertEquals(150.0, parseOutputKw("150")!!, 0.0)
    assertNull(parseOutputKw(null))
  }

  @Test
  fun `parses an Overpass response with nodes and way centres`() {
    val body =
        """
        {"elements":[
          {"type":"node","id":10,"lat":60.1,"lon":24.9,"tags":{"amenity":"charging_station","socket:type2_combo":"2","socket:type2_combo:output":"150 kW"}},
          {"type":"way","id":20,"center":{"lat":60.2,"lon":24.8},"tags":{"amenity":"charging_station","socket:type2":"4"}},
          {"type":"node","id":30,"lat":60.3,"lon":24.7,"tags":{"amenity":"charging_station","access":"private"}}
        ]}
        """

    val stations = ChargerRepository.parse(body)

    assertEquals(listOf("node/10", "way/20"), stations.map { it.id })
    assertEquals(LatLng(60.2, 24.8), stations[1].position)
  }

  @Test
  fun `tiles contain their point and cover a bounding box`() {
    val tile = Tile.containing(60.17, 24.94, 10)
    assertTrue(60.17 in tile.south..tile.north)
    assertTrue(24.94 in tile.west..tile.east)

    val covering = Tile.covering(60.10, 24.80, 60.25, 25.10, 10)
    assertTrue(covering.contains(tile))
    assertTrue(covering.size in 2..6)
  }
}
