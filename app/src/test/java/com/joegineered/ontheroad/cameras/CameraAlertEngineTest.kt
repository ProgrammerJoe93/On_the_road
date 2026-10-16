package com.joegineered.ontheroad.cameras

import com.joegineered.ontheroad.cameras.TestGeometry.at
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class CameraAlertEngineTest {
  private fun matched(id: Long, along: Double) =
      MatchedCamera(SpeedCamera(id, at(along)), alongRouteMetres = along, offsetMetres = 0.0)

  private val engine = CameraAlertEngine().apply { setCameras(listOf(matched(1, 2000.0))) }

  @Test
  fun `no warning outside the window`() {
    val update = engine.update(alongRouteMetres = 1000.0, speedMetresPerSecond = 10.0)

    assertNull(update.active)
    assertNull(update.announce)
  }

  @Test
  fun `warns 500 m ahead at low speed`() {
    assertNull(engine.update(1499.0, 10.0).active)

    val update = engine.update(1501.0, 10.0)
    assertEquals(499.0, update.active!!.distanceMetres, 0.01)
    assertNotNull(update.announce)
  }

  @Test
  fun `window grows to 15 seconds at high speed`() {
    // 120 km/h = 33.3 m/s -> 500 m window at 15 s
    assertEquals(500.0, engine.warningDistanceMetres(33.3), 1.0)
    // 160 km/h = 44.4 m/s -> 666 m window
    val update = engine.update(2000.0 - 650.0, 44.4)
    assertNotNull(update.announce)
  }

  @Test
  fun `each camera is announced only once`() {
    assertNotNull(engine.update(1600.0, 10.0).announce)
    val later = engine.update(1800.0, 10.0)

    assertNotNull(later.active)
    assertNull(later.announce)
  }

  @Test
  fun `not announced again after a reroute over the same camera`() {
    assertNotNull(engine.update(1600.0, 10.0).announce)

    // Rerouted: the same camera is now 300 m into the new route.
    engine.setCameras(listOf(matched(1, 300.0)))
    assertNull(engine.update(0.0, 10.0).announce)
  }

  @Test
  fun `warning clears once the camera is passed`() {
    engine.update(1600.0, 10.0)

    assertNotNull(engine.update(2010.0, 10.0).active) // within the GPS margin
    assertNull(engine.update(2050.0, 10.0).active)
  }

  @Test
  fun `moves on to the next camera`() {
    engine.setCameras(listOf(matched(1, 2000.0), matched(2, 2300.0)))
    engine.update(1600.0, 10.0)

    val update = engine.update(2100.0, 10.0)
    assertEquals(2L, update.active!!.camera.id)
    assertNotNull(update.announce)
  }
}
