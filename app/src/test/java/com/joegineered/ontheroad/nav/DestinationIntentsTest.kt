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
}
