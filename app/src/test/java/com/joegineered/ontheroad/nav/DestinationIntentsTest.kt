package com.joegineered.ontheroad.nav

import java.net.URLDecoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DestinationIntentsTest {
  // Android's Uri.decode isn't available on the JVM; for these inputs it decodes the same.
  private fun parse(scheme: String?, ssp: String?) =
      parseDestinationUri(scheme, ssp, decode = { URLDecoder.decode(it, "UTF-8") })

  @Test
  fun `geo links with coordinates`() {
    val destination = parse("geo", "60.17,24.94")!!
    assertEquals(60.17, destination.latitude!!, 1e-9)
    assertEquals(24.94, destination.longitude!!, 1e-9)
  }

  @Test
  fun `geo links with a label in the query don't crash`() {
    // Ferrostar's own parser throws on these, as the URI is opaque.
    val destination = parse("geo", "60.17,24.94?q=Home")!!
    assertEquals(60.17, destination.latitude!!, 1e-9)
    assertEquals(24.94, destination.longitude!!, 1e-9)
  }

  @Test
  fun `geo links with only a search query`() {
    val destination = parse("geo", "0,0?q=Kamppi%20Helsinki")!!
    assertEquals("Kamppi Helsinki", destination.query)
  }

  @Test
  fun `google navigation links with coordinates`() {
    // The parameters follow the colon directly, without a "?".
    val destination = parse("google.navigation", "q=60.17,24.94&mode=d")!!
    assertEquals(60.17, destination.latitude!!, 1e-9)
    assertEquals(24.94, destination.longitude!!, 1e-9)
  }

  @Test
  fun `google navigation links with an address`() {
    assertEquals("Mannerheimintie 1, Helsinki", parse("google.navigation", "q=Mannerheimintie%201,%20Helsinki")!!.query)
  }

  @Test
  fun `other links and empty queries are ignored`() {
    assertNull(parse("https", "//example.com"))
    assertNull(parse("google.navigation", "mode=d"))
    assertNull(parse("google.navigation", "q="))
    assertNull(parse("geo", null))
  }

  @Test
  fun `links with coordinates drive there, labelled by the query`() {
    val request = parse("geo", "60.17,24.94?q=Home")!!.toRequest() as DestinationRequest.Go
    assertEquals(60.17, request.destination.coordinate.lat, 1e-9)
    assertEquals(24.94, request.destination.coordinate.lng, 1e-9)
    assertEquals("Home", request.destination.label)
  }

  @Test
  fun `spoken places without coordinates become a search`() {
    assertEquals(
        DestinationRequest.Search("Kamppi Helsinki"),
        parse("geo", "0,0?q=Kamppi%20Helsinki")!!.toRequest(),
    )
    assertEquals(
        DestinationRequest.Search("Mannerheimintie 1, Helsinki"),
        parse("google.navigation", "q=Mannerheimintie%201,%20Helsinki")!!.toRequest(),
    )
  }

  @Test
  fun `links with neither coordinates nor a query are ignored`() {
    assertNull(parse("geo", "0,0")?.toRequest())
  }

  private fun request(scheme: String, ssp: String) =
      parseDestinationRequestUri(scheme, ssp, decode = { URLDecoder.decode(it, "UTF-8") })

  @Test
  fun `the assistant's intent parameter says what to do with the place`() {
    assertEquals(
        DestinationRequest.Search("1600 Amphitheatre parkway", TripAction.ADD_STOP),
        request("geo", "0,0?q=1600+Amphitheatre+parkway&mode=b&intent=add_a_stop"),
    )
    assertEquals(
        TripAction.DIRECTIONS,
        request("geo", "60.17,24.94?q=Kamppi&intent=directions")!!.action,
    )
    assertEquals(TripAction.NAVIGATE, request("geo", "0,0?q=Kamppi&intent=navigation")!!.action)
    assertEquals(TripAction.NAVIGATE, request("geo", "0,0?q=Kamppi")!!.action)
    assertEquals(TripAction.NAVIGATE, request("geo", "0,0?q=Kamppi&intent=something_new")!!.action)
    assertEquals(TripAction.NAVIGATE, request("google.navigation", "q=60.17,24.94")!!.action)
  }

  @Test
  fun `during a trip, only an explicit add a stop skips the question`() {
    fun during(action: TripAction) =
        destinationOutcome(action, navigating = true, previewing = false, hasLocation = true)
    assertEquals(DestinationOutcome.ADD_TO_TRIP, during(TripAction.ADD_STOP))
    assertEquals(DestinationOutcome.ASK, during(TripAction.NAVIGATE))
    assertEquals(DestinationOutcome.ASK, during(TripAction.DIRECTIONS))
  }

  @Test
  fun `without a trip, navigate starts and directions previews`() {
    fun idle(action: TripAction, previewing: Boolean = false, hasLocation: Boolean = true) =
        destinationOutcome(action, navigating = false, previewing, hasLocation)
    assertEquals(DestinationOutcome.START, idle(TripAction.NAVIGATE))
    assertEquals(DestinationOutcome.PREVIEW, idle(TripAction.DIRECTIONS))
    // "Add a stop" with no trip simply goes there, or joins the routes being previewed.
    assertEquals(DestinationOutcome.START, idle(TripAction.ADD_STOP))
    assertEquals(DestinationOutcome.ADD_TO_PREVIEW, idle(TripAction.ADD_STOP, previewing = true))
    // Before the first location fix, the preview waits for it rather than failing.
    assertEquals(DestinationOutcome.PREVIEW, idle(TripAction.NAVIGATE, hasLocation = false))
  }

  @Test
  fun `links opened from other apps show the routes first`() {
    assertEquals(TripAction.DIRECTIONS, request("geo", "60.17,24.94")!!.viewed().action)
    assertEquals(
        DestinationRequest.Search("Kamppi", TripAction.DIRECTIONS),
        request("geo", "0,0?q=Kamppi")!!.viewed(),
    )
    // An explicit request is kept.
    assertEquals(
        TripAction.ADD_STOP,
        request("geo", "0,0?q=Kamppi&intent=add_a_stop")!!.viewed().action,
    )
  }
}
