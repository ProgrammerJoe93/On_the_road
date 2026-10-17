package com.joegineered.ontheroad.nav

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Test

class RoutingFailureTest {
  @Test
  fun `HTTP 400 from Valhalla means there is no drivable route`() {
    assertEquals(RoutingStatus.NoRoute, routingFailureStatus(IOException("Invalid status code 400"), online = true))
    // Even offline: the server answered, so the place is unreachable rather than the phone offline.
    assertEquals(RoutingStatus.NoRoute, routingFailureStatus(IOException("status code 400"), online = false))
  }

  @Test
  fun `other failures offline are shown as offline`() {
    assertEquals(RoutingStatus.Offline, routingFailureStatus(IOException("timeout"), online = false))
  }

  @Test
  fun `other failures online show their reason`() {
    assertEquals(RoutingStatus.Failed("timeout"), routingFailureStatus(IOException("timeout"), online = true))
    assertEquals(RoutingStatus.Failed("IllegalStateException"), routingFailureStatus(IllegalStateException(), online = true))
  }
}
