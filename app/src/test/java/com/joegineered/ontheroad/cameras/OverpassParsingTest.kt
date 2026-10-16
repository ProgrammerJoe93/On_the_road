package com.joegineered.ontheroad.cameras

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OverpassParsingTest {

  @Test
  fun `parses camera nodes with tags and enforcement relations`() {
    val body =
        """
        {"elements": [
          {"type": "node", "id": 101, "lat": 60.17, "lon": 24.94,
           "tags": {"highway": "speed_camera", "direction": "NE;225", "maxspeed": "50"}},
          {"type": "node", "id": 102, "lat": 60.18, "lon": 24.95,
           "tags": {"highway": "speed_camera"}},
          {"type": "node", "id": 900, "lat": 60.179, "lon": 24.95},
          {"type": "relation", "id": 5,
           "members": [
             {"type": "node", "ref": 102, "role": "device"},
             {"type": "node", "ref": 900, "role": "from"}
           ],
           "tags": {"type": "enforcement", "enforcement": "maxspeed", "maxspeed": "30 mph"}}
        ]}
        """

    val cameras = OverpassClient.parseCameras(body).associateBy { it.id }

    assertEquals(setOf(101L, 102L), cameras.keys)
    assertEquals(listOf(45.0, 225.0), cameras.getValue(101).directions)
    assertEquals(50, cameras.getValue(101).maxSpeedKmh)
    assertNull(cameras.getValue(101).enforcementFrom)
    assertEquals(LatLng(60.179, 24.95), cameras.getValue(102).enforcementFrom)
    assertEquals(48, cameras.getValue(102).maxSpeedKmh)
  }

  @Test
  fun `parses road geometries`() {
    val body =
        """
        {"elements": [
          {"type": "way", "id": 1, "geometry": [{"lat": 60.0, "lon": 24.0}, {"lat": 60.001, "lon": 24.0}]},
          {"type": "way", "id": 1, "geometry": [{"lat": 60.0, "lon": 24.0}, {"lat": 60.001, "lon": 24.0}]},
          {"type": "way", "id": 2, "geometry": [{"lat": 60.0, "lon": 24.0}]}
        ]}
        """

    assertEquals(1, OverpassClient.parseRoads(body).size)
  }

  @Test
  fun `camera query uses the polyline around filter`() {
    val query = OverpassClient.camerasAlongQuery(listOf(LatLng(60.0, 24.0), LatLng(60.1, 24.1)))

    assertTrue(query.contains("node[highway=speed_camera](around:50,60.000000,24.000000,60.100000,24.100000)"))
    assertTrue(query.contains("rel(bn.cams)[type=enforcement]"))
  }

  @Test
  fun `direction tag parsing`() {
    assertEquals(emptyList<Double>(), parseDirectionTag(null))
    assertEquals(emptyList<Double>(), parseDirectionTag("forward"))
    assertEquals(listOf(270.0), parseDirectionTag("W"))
    assertEquals(listOf(350.0), parseDirectionTag("-10"))
  }

  @Test
  fun `polyline chunks overlap at their boundaries`() {
    val chunks = (1..10).toList().chunkedOverlapping(4)

    assertEquals(listOf(listOf(1, 2, 3, 4), listOf(4, 5, 6, 7), listOf(7, 8, 9, 10)), chunks)
  }

  @Test
  fun `simplification keeps corners and drops collinear points`() {
    val straight = TestGeometry.northboundRoute(1000)
    val withCorner = straight + TestGeometry.at(1000.0, east = 500.0)

    assertEquals(2, simplify(straight, 5.0).size)
    assertEquals(3, simplify(withCorner, 5.0).size)
  }
}
