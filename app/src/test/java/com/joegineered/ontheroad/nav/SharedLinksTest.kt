package com.joegineered.ontheroad.nav

import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SharedLinksTest {
  private fun shared(text: String) = parseSharedText(text)

  private fun goTo(text: String): Destination =
      (shared(text)!!.request as DestinationRequest.Go).destination

  private fun assertAt(lat: Double, lng: Double, destination: Destination) {
    assertEquals(lat, destination.coordinate.lat, 1e-6)
    assertEquals(lng, destination.coordinate.lng, 1e-6)
  }

  @Test
  fun `google place links use the place, not where the map was centred`() {
    val destination =
        goTo(
            "https://www.google.com/maps/place/Kamppi+Center/@60.1685,24.9312,17z/" +
                "data=!3m1!4b1!4m6!3m5!1s0x46920bcb:0x1!8m2!3d60.1690291!4d24.9325718!16s%2Fg%2F1"
        )
    assertAt(60.1690291, 24.9325718, destination)
    assertEquals("Kamppi Center", destination.label)
  }

  @Test
  fun `google links with a query or destination`() {
    assertAt(60.17, 24.94, goTo("https://www.google.com/maps/search/?api=1&query=60.17,24.94"))
    assertAt(60.17, 24.94, goTo("https://www.google.fi/maps/dir/?api=1&destination=60.17%2C24.94"))
    assertAt(60.17, 24.94, goTo("https://maps.google.com/?q=60.17,24.94"))
    assertEquals(
        DestinationRequest.Search("Kamppi Helsinki", TripAction.DIRECTIONS),
        shared("https://maps.google.com/maps?q=Kamppi+Helsinki")!!.request,
    )
  }

  @Test
  fun `google links with only a name search for it`() {
    assertEquals(
        DestinationRequest.Search("Kamppi Center", TripAction.DIRECTIONS),
        shared("https://www.google.com/maps/place/Kamppi+Center/@60.1685,24.9312,17z")!!.request,
    )
  }

  @Test
  fun `a google map view is used when there is nothing else`() {
    assertAt(60.1685, 24.9312, goTo("https://www.google.com/maps/@60.1685,24.9312,15z"))
  }

  @Test
  fun `openstreetmap links prefer the marker`() {
    assertAt(60.17, 24.94, goTo("https://www.openstreetmap.org/?mlat=60.17&mlon=24.94#map=17/60.1/24.9"))
    assertAt(60.1, 24.9, goTo("https://www.openstreetmap.org/#map=17/60.1/24.9"))
  }

  @Test
  fun `apple maps links`() {
    val destination = goTo("https://maps.apple.com/?ll=60.17,24.94&q=Kamppi")
    assertAt(60.17, 24.94, destination)
    assertEquals("Kamppi", destination.label)
    assertAt(60.17, 24.94, goTo("https://maps.apple.com/place?coordinate=60.17,24.94&name=Kamppi"))
    assertEquals(
        DestinationRequest.Search("Urho Kekkosen katu 1, Helsinki", TripAction.DIRECTIONS),
        shared("https://maps.apple.com/?address=Urho%20Kekkosen%20katu%201,%20Helsinki")!!.request,
    )
  }

  @Test
  fun `coordinates and geo links in the text`() {
    assertAt(60.1699, 24.9384, goTo("Meet here: 60.1699, 24.9384"))
    assertAt(60.17, 24.94, goTo("geo:60.17,24.94?q=Home"))
  }

  @Test
  fun `an address is searched for, and house numbers aren't coordinates`() {
    assertEquals(
        DestinationRequest.Search("Kamppi Center, Urho Kekkosen katu 1, 00100 Helsinki", TripAction.DIRECTIONS),
        shared("Kamppi Center\nUrho Kekkosen katu 1, 00100 Helsinki\n")!!.request,
    )
  }

  @Test
  fun `short links are resolved, with the text to search for if that fails`() {
    val result = shared("Kamppi Center\nhttps://maps.app.goo.gl/AbC123")!!
    assertEquals("https://maps.app.goo.gl/AbC123".toHttpUrl(), result.shortLink)
    assertEquals(DestinationRequest.Search("Kamppi Center", TripAction.DIRECTIONS), result.request)
    assertNull(shared("https://maps.app.goo.gl/AbC123")!!.request)
  }

  @Test
  fun `text with no place is ignored`() {
    assertNull(shared("https://example.com/article"))
    assertNull(shared("ok"))
  }

  @Test
  fun `the resolver follows redirects, through google's consent page`() = runTest {
    val redirects =
        mapOf(
            "https://maps.app.goo.gl/AbC123" to
                "https://consent.google.com/m?continue=https://www.google.com/maps/place/Kamppi/data%3D!3d60.169!4d24.932",
        )
    val resolver = ShortLinkResolver(redirect = { url: HttpUrl -> redirects[url.toString()]?.toHttpUrl() })
    val destination =
        (resolver.resolve("https://maps.app.goo.gl/AbC123".toHttpUrl()) as DestinationRequest.Go).destination
    assertAt(60.169, 24.932, destination)
  }

  @Test
  fun `the resolver gives up on links that lead nowhere`() = runTest {
    var calls = 0
    val loop = ShortLinkResolver(redirect = { url: HttpUrl -> calls++; url.newBuilder().addPathSegment("x").build() })
    assertNull(loop.resolve("https://maps.app.goo.gl/AbC123".toHttpUrl()))
    assertEquals(5, calls)
    assertNull(ShortLinkResolver(redirect = { null }).resolve("https://maps.app.goo.gl/AbC123".toHttpUrl()))
  }
}
