package com.joegineered.ontheroad.traffic

import com.joegineered.ontheroad.cameras.LatLng
import com.joegineered.ontheroad.cameras.TestGeometry.at
import com.joegineered.ontheroad.cameras.TestGeometry.northboundRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VariableSignsTest {
  // Shapes taken from Digitraffic's live response (trimmed).
  private val body =
      """
      {"type":"FeatureCollection","features":[
        {"type":"Feature","geometry":{"type":"Point","coordinates":[24.587602,60.231528]},
         "properties":{"id":"VME/TIO012801","type":"INFORMATION","roadAddress":"1 6 3577","direction":"DECREASING",
           "carriageway":"LEFT","displayValue":"142","reliability":"NORMAL","textRows":[
             {"screen":2,"rowNumber":1,"text":"KORJAUS"},
             {"screen":1,"rowNumber":2,"text":"BELÄGGNINGSSKADA"},
             {"screen":1,"rowNumber":1,"text":"PÄÄLLYSTEVAURIO"},
             {"screen":1,"rowNumber":3,"text":" "}]}},
        {"type":"Feature","geometry":{"type":"Point","coordinates":[24.624724,60.223074]},
         "properties":{"id":"KRM012851_KeMu","type":"SPEEDLIMIT","roadAddress":"1 6 1283","direction":"DECREASING",
           "displayValue":"80","reliability":"NORMAL","textRows":[]}},
        {"type":"Feature","geometry":{"type":"Point","coordinates":[23.192654,60.411968]},
         "properties":{"id":"KRM021663","type":"SPEEDLIMIT","roadAddress":"1 22 8464","direction":"INCREASING",
           "reliability":"NORMAL","textRows":[]}},
        {"type":"Feature","geometry":{"type":"Point","coordinates":[25.0,61.0]},
         "properties":{"id":"BLANK","type":"WARNING","roadAddress":"9 204 213","reliability":"NORMAL","textRows":[]}},
        {"type":"Feature","geometry":{"type":"Point","coordinates":[25.0,61.0]},
         "properties":{"id":"BROKEN","type":"SPEEDLIMIT","displayValue":"100","reliability":"MALFUNCTION","textRows":[]}}
      ]}
      """

  @Test
  fun `parses signs, leaving out blank warnings and broken signs`() {
    val signs = DigitrafficParser.parseVariableSigns(body).associateBy { it.id }

    assertEquals(setOf("VME/TIO012801", "KRM012851_KeMu", "KRM021663"), signs.keys)
    val message = signs.getValue("VME/TIO012801")
    assertEquals(VariableSignType.INFORMATION, message.type)
    assertEquals("142", message.warningCode)
    assertEquals(listOf(listOf("PÄÄLLYSTEVAURIO", "BELÄGGNINGSSKADA"), listOf("KORJAUS")), message.screens)
    assertEquals(RoadAddress(1, 6, 3577), message.address)
    assertEquals(RoadDirection.DECREASING, message.direction)
    assertEquals(80, signs.getValue("KRM012851_KeMu").speedLimitKmh)
    assertNull(signs.getValue("KRM021663").speedLimitKmh) // blank, but kept: it ends a variable limit
  }

  private fun speedSign(
      id: String,
      position: LatLng,
      distance: Int,
      direction: RoadDirection,
      limit: Int? = 100,
      road: Int = 1,
  ) =
      VariableSign(
          id = id,
          type = VariableSignType.SPEED_LIMIT,
          position = position,
          address = RoadAddress(road, 5, distance),
          direction = direction,
          speedLimitKmh = limit,
      )

  @Test
  fun `keeps the signs for the direction the route drives the road`() {
    // Northbound route along road 1, whose addresses increase northwards.
    val signs =
        listOf(
            speedSign("up1", at(300.0, 5.0), 300, RoadDirection.INCREASING, 100),
            speedSign("down1", at(305.0, -30.0), 305, RoadDirection.DECREASING, 80),
            speedSign("up2", at(1300.0, 5.0), 1300, RoadDirection.INCREASING, 100),
            speedSign("down2", at(1295.0, -30.0), 1295, RoadDirection.DECREASING, 80),
        )

    val matched = matchVariableSigns(northboundRoute(), signs)

    assertEquals(listOf("up1", "up2"), matched.map { it.sign.id })
    assertEquals(300.0, matched.first().alongRouteMetres, 2.0)
  }

  @Test
  fun `a speed limit gantry on a road the route only crosses is left out`() {
    val crossing = speedSign("cross", at(500.0, 5.0), 4000, RoadDirection.INCREASING, road = 4)

    assertTrue(matchVariableSigns(northboundRoute(), listOf(crossing)).isEmpty())
  }

  @Test
  fun `without a known direction, warning signs count on the right of the route only`() {
    fun warning(id: String, position: LatLng) =
        VariableSign(id, VariableSignType.WARNING, position, address = null, direction = null, warningCode = "155")

    val matched =
        matchVariableSigns(northboundRoute(), listOf(warning("right", at(500.0, 25.0)), warning("left", at(800.0, -25.0))))

    assertEquals(listOf("right"), matched.map { it.sign.id })
  }

  private fun onRoute(sign: VariableSign, along: Double) = RouteVariableSign(sign, along)

  @Test
  fun `a variable limit applies from its sign until the next one`() {
    val tracker = VariableSignTracker()
    tracker.setSigns(
        listOf(
            onRoute(speedSign("a", at(0.0), 0, RoadDirection.INCREASING, 100), 1_000.0),
            onRoute(speedSign("b", at(0.0), 0, RoadDirection.INCREASING, 80), 2_000.0),
            onRoute(speedSign("c", at(0.0), 0, RoadDirection.INCREASING, null), 3_000.0),
        )
    )

    assertNull(tracker.update(900.0, 120).speedLimitKmh)
    assertEquals(100, tracker.update(1_100.0, 120).speedLimitKmh)
    assertEquals(80, tracker.update(2_500.0, 120).speedLimitKmh)
    assertNull(tracker.update(3_100.0, 120).speedLimitKmh) // blank sign
  }

  @Test
  fun `the lowest limit on a gantry is shown`() {
    val tracker = VariableSignTracker()
    tracker.setSigns(
        listOf(
            onRoute(speedSign("lane1", at(0.0), 0, RoadDirection.INCREASING, 100), 1_000.0),
            onRoute(speedSign("lane2", at(0.0), 0, RoadDirection.INCREASING, 80), 1_010.0),
        )
    )

    assertEquals(80, tracker.update(1_100.0, 120).speedLimitKmh)
  }

  @Test
  fun `a variable limit ends when the mapped limit changes or after a long way`() {
    val tracker = VariableSignTracker(maxLimitMetres = 5_000.0)
    tracker.setSigns(listOf(onRoute(speedSign("a", at(0.0), 0, RoadDirection.INCREASING, 100), 1_000.0)))

    // The mapped limit settles a little after the sign, and then counts as the road's own.
    assertEquals(100, tracker.update(1_050.0, 80).speedLimitKmh)
    assertEquals(100, tracker.update(1_300.0, 120).speedLimitKmh)
    assertEquals(100, tracker.update(3_000.0, 120).speedLimitKmh)
    assertNull(tracker.update(3_100.0, 60).speedLimitKmh) // left the motorway
    assertNull(tracker.update(3_200.0, 120).speedLimitKmh)

    val far = VariableSignTracker(maxLimitMetres = 5_000.0)
    far.setSigns(listOf(onRoute(speedSign("a", at(0.0), 0, RoadDirection.INCREASING, 100), 1_000.0)))
    assertNull(far.update(6_100.0, null).speedLimitKmh)
  }

  @Test
  fun `warnings show after passing their sign, and messages just before`() {
    val elk = VariableSign("elk", VariableSignType.WARNING, at(0.0), null, null, warningCode = "155")
    val message =
        VariableSign("msg", VariableSignType.INFORMATION, at(0.0), null, null, screens = listOf(listOf("TIETYÖ")))
    val tracker = VariableSignTracker(warningMetres = 1_500.0, messageAheadMetres = 1_000.0)
    tracker.setSigns(listOf(onRoute(elk, 1_000.0), onRoute(message, 5_000.0)))

    assertEquals(emptyList<String>(), tracker.update(900.0, null).warningCodes)
    assertEquals(listOf("155"), tracker.update(1_200.0, null).warningCodes)
    assertEquals(emptyList<String>(), tracker.update(2_600.0, null).warningCodes)
    assertNull(tracker.update(3_900.0, null).message)
    assertEquals("msg", tracker.update(4_200.0, null).message?.id)
    assertNull(tracker.update(5_200.0, null).message)
  }
}
