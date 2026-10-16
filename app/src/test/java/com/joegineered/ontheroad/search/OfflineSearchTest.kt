package com.joegineered.ontheroad.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OfflineSearchTest {
  private val places =
      listOf(
          Place("Jyväskylän matkakeskus", "Hannikaisenkatu 20, 40100 Jyväskylä", 62.24, 25.75),
          Place("Kamppi", "00100 Helsinki", 60.17, 24.93),
      )

  @Test
  fun `matches every word, ignoring case and accents`() {
    assertEquals(listOf(places[0]), PlaceSearch.matching(places, "jyvaskyla matka"))
    assertEquals(listOf(places[1]), PlaceSearch.matching(places, "HELSINKI"))
    assertEquals(emptyList<Place>(), PlaceSearch.matching(places, "kamppi tampere"))
  }

  @Test
  fun `locality prefers the settlement name`() {
    val body =
        """{"features":[{"type":"Feature","properties":{"name":"Mannerheimintie 1","city":"Helsinki","state":"Uusimaa"}}]}"""
    assertEquals("Helsinki", PhotonGeocoder.parseLocality(body))
    val rural = """{"features":[{"type":"Feature","properties":{"county":"Inari","state":"Lappi"}}]}"""
    assertEquals("Inari", PhotonGeocoder.parseLocality(rural))
    assertNull(PhotonGeocoder.parseLocality("""{"features":[]}"""))
  }
}
