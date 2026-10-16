package com.joegineered.ontheroad.traffic

import com.joegineered.ontheroad.cameras.LatLng
import com.joegineered.ontheroad.cameras.TestGeometry.at
import com.joegineered.ontheroad.cameras.TestGeometry.northboundRoute
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrafficTest {
  private val now = Instant.parse("2026-10-16T22:00:00Z")

  // Shapes taken from Digitraffic's live responses (trimmed).
  private val announcements =
      """
      {"type":"FeatureCollection","features":[
        {"type":"Feature","geometry":{"type":"Point","coordinates":[24.0,63.2,0.0]},
         "properties":{"situationId":"GUID1","situationType":"traffic announcement","trafficAnnouncementType":"preliminary accident report",
           "announcements":[{"language":"fi","title":"Tie 13, Perho. Ensitiedote liikenneonnettomuudesta. ",
             "location":{"description":"Tie 13 välillä Kokkola - Jyväskylä, Perho."},
             "features":[{"name":"Onnettomuus"}],
             "timeAndDuration":{"startTime":"2026-10-16T21:57:00.000Z","endTime":"2026-10-16T22:27:37.295Z"}}]}},
        {"type":"Feature","geometry":{"type":"LineString","coordinates":[[25.15,60.21],[25.16,60.215]]},
         "properties":{"situationId":"GUID2","situationType":"traffic announcement","trafficAnnouncementType":"general",
           "announcements":[{"language":"fi","title":"Tie 103, eli Vuosaaren satamatie, Helsinki. Liikennetiedote.",
             "features":[{"name":"Tie on suljettu liikenteeltä"},{"name":"Paikalla on kiertotieopastus"}],
             "timeAndDuration":{"startTime":"2026-01-06T04:00:00.000Z","endTime":"2026-12-31T22:00:00.000Z"}}]}},
        {"type":"Feature","geometry":{"type":"Point","coordinates":[24.0,61.0]},
         "properties":{"situationId":"GUID3","situationType":"traffic announcement","trafficAnnouncementType":"general",
           "announcements":[{"language":"fi","title":"Old",
             "timeAndDuration":{"startTime":"2026-01-01T00:00:00.000Z","endTime":"2026-02-01T00:00:00.000Z"}}]}}
      ]}
      """

  private val roadworks =
      """
      {"type":"FeatureCollection","features":[
        {"type":"Feature","geometry":{"type":"MultiLineString","coordinates":[[[24.9,60.2],[24.91,60.21]]]},
         "properties":{"situationId":"GUID10","situationType":"road work",
           "announcements":[{"language":"fi","title":"Tie 956, Enontekiö. Tietyö.","features":[{"name":"Nopeusrajoitus","quantity":50.0,"unit":"km/h"}],
             "roadWorkPhases":[{"timeAndDuration":{"startTime":"2026-10-16T00:00:00.000Z","endTime":"2026-10-19T20:59:59.999Z"},
               "restrictions":[{"type":"speed limit","restriction":{"name":"Nopeusrajoitus","quantity":50.0,"unit":"km/h"}},
                               {"type":"single lane closed","restriction":{"name":"Yksi ajokaista suljettu"}}]}]}]}},
        {"type":"Feature","geometry":{"type":"Point","coordinates":[25.0,61.0]},
         "properties":{"situationId":"GUID11","situationType":"road work",
           "announcements":[{"language":"fi","title":"Future","roadWorkPhases":[{"timeAndDuration":{"startTime":"2026-11-01T00:00:00.000Z","endTime":"2026-11-02T00:00:00.000Z"},"restrictions":[]}]}]}}
      ]}
      """

  @Test
  fun `parses active announcements and classifies them`() {
    val events = DigitrafficParser.parseMessages(announcements, now).associateBy { it.id }

    assertEquals(setOf("GUID1", "GUID2"), events.keys) // GUID3 has ended
    assertEquals(TrafficEventKind.ACCIDENT, events.getValue("GUID1").kind)
    assertEquals(listOf("Accident"), events.getValue("GUID1").restrictions)
    val closure = events.getValue("GUID2")
    assertTrue(closure.isClosure)
    assertEquals(listOf("Road closed", "Detour signposted"), closure.restrictions)
    assertEquals(2, closure.lines.single().size)
  }

  @Test
  fun `roadworks use the active phase and English restriction labels`() {
    val events = DigitrafficParser.parseMessages(roadworks, now)

    val work = events.single() // GUID11 only starts in November
    assertEquals(TrafficEventKind.ROADWORKS, work.kind)
    assertEquals(listOf("Speed limit 50 km/h", "Lane closed"), work.restrictions)
    assertEquals(Instant.parse("2026-10-19T20:59:59.999Z"), work.end)
  }

  @Test
  fun `slow traffic uses percent of free-flow speed per direction`() {
    val data =
        """
        {"stations":[
          {"id":1,"sensorValues":[
            {"name":"KESKINOPEUS_5MIN_LIUKUVA_SUUNTA1_VVAPAAS1","value":45.0},
            {"name":"KESKINOPEUS_5MIN_LIUKUVA_SUUNTA2_VVAPAAS2","value":98.0},
            {"name":"KESKINOPEUS_5MIN_LIUKUVA_SUUNTA1","value":36.0}]},
          {"id":2,"sensorValues":[{"name":"KESKINOPEUS_5MIN_LIUKUVA_SUUNTA1_VVAPAAS1","value":92.0}]},
          {"id":3,"sensorValues":[{"name":"KESKINOPEUS_5MIN_LIUKUVA_SUUNTA1_VVAPAAS1","value":0.0}]}
        ]}
        """
    val stations = mapOf(1 to LatLng(60.0, 24.0), 2 to LatLng(61.0, 25.0), 3 to LatLng(62.0, 26.0))

    val slow = DigitrafficParser.parseSlowTraffic(data, stations, slowPercent = 75.0)

    assertEquals(listOf(1), slow.map { it.stationId }) // 0% means no vehicles, not a jam
    assertEquals(45.0, slow.single().worstPercent!!, 0.0)
    assertEquals(36.0, slow.single().direction1Kmh!!, 0.0)
  }

  @Test
  fun `closures become small closed squares for Valhalla`() {
    val closure = DigitrafficParser.parseMessages(announcements, now).first { it.isClosure }

    val polygons = ClosureAvoidance.excludePolygons(listOf(closure))

    assertEquals(3, polygons.size) // start, middle and end of the closed stretch
    polygons.forEach { ring -> assertEquals(ring.first(), ring.last()) }
    val accidentsOnly = DigitrafficParser.parseMessages(announcements, now).filter { !it.isClosure }
    assertTrue(ClosureAvoidance.excludePolygons(accidentsOnly).isEmpty())
  }

  @Test
  fun `only closures near the trip are sent, within Valhalla's vertex limit`() {
    // 40 closures spread north of the origin, every 5 km.
    val closures =
        (1..40).map { i -> event("c$i", TrafficEventKind.ROAD_CLOSED, at(i * 5_000.0), at(i * 5_000.0 + 100)) }
    val polygons = ClosureAvoidance.excludePolygons(closures, from = at(0.0), to = at(30_000.0))

    // Up to 20 squares (100 vertices); none from closures far beyond the 30 km trip's corridor.
    assertTrue(polygons.size <= 20)
    assertTrue(polygons.sumOf { it.size } <= 100)
    val farthestLat = polygons.maxOf { ring -> ring.maxOf { it[1] } }
    assertTrue(farthestLat < at(55_000.0).lat)
  }

  private fun event(id: String, kind: TrafficEventKind, vararg points: LatLng) =
      TrafficEvent(id, kind, "t", null, listOf(points.toList()), emptyList(), null, null)

  @Test
  fun `events on the route are matched and warned about once`() {
    val engine = TrafficAlertEngine()
    val route = northboundRoute(10_000)
    val matched =
        engine.match(
            route,
            listOf(
                event("works", TrafficEventKind.ROADWORKS, at(4_000.0, east = 10.0), at(4_300.0, east = 10.0)),
                event("elsewhere", TrafficEventKind.ACCIDENT, at(4_000.0, east = 500.0)),
            ),
        )
    assertEquals(listOf("works"), matched.map { it.event.id })
    engine.setEvents(matched)

    assertNull(engine.update(1_000.0).active) // 3 km away: outside the 2 km window
    val first = engine.update(2_500.0)
    assertNotNull(first.announce)
    assertEquals(1_500.0, first.active!!.distanceMetres, 5.0)
    assertNull(engine.update(3_000.0).announce)
  }

  @Test
  fun `closures are warned about earlier`() {
    val engine = TrafficAlertEngine()
    val route = northboundRoute(10_000)
    engine.setEvents(engine.match(route, listOf(event("closed", TrafficEventKind.ROAD_CLOSED, at(8_000.0)))))

    assertFalse(engine.update(2_000.0).active != null) // 6 km away
    assertNotNull(engine.update(3_500.0).announce) // 4.5 km away
  }

  @Test
  fun `knows when the driver is inside a roadwork stretch`() {
    val engine = TrafficAlertEngine()
    val route = northboundRoute(10_000)
    engine.setEvents(
        engine.match(
            route,
            listOf(
                event("works", TrafficEventKind.ROADWORKS, at(4_000.0), at(4_500.0), at(5_000.0)),
                event("crash", TrafficEventKind.ACCIDENT, at(7_000.0), at(7_200.0)),
            ),
        )
    )

    assertNull(engine.roadworksAt(3_800.0))
    assertEquals("works", engine.roadworksAt(3_960.0)?.id) // just before: 50 m slack
    assertEquals("works", engine.roadworksAt(4_700.0)?.id)
    assertNull(engine.roadworksAt(5_100.0))
    assertNull(engine.roadworksAt(7_100.0)) // only roadworks get the sign
  }
}
