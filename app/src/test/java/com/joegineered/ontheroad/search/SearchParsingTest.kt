package com.joegineered.ontheroad.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SearchParsingTest {

  private fun feature(lng: Double, lat: Double, props: String) =
      """{"type":"Feature","geometry":{"type":"Point","coordinates":[$lng,$lat]},"properties":{$props}}"""

  private fun collection(vararg features: String) =
      """{"type":"FeatureCollection","features":[${features.joinToString(",")}]}"""

  @Test
  fun `named place shows its address underneath`() {
    val body =
        collection(
            feature(
                24.94, 60.17,
                """"name":"Marski by Scandic","street":"Mannerheimintie","housenumber":"10",
                   "postcode":"00100","city":"Helsinki","country":"Suomi","countrycode":"FI"""",
            )
        )

    val place = PhotonGeocoder.parse(body, homeCountry = "FI").single()

    assertEquals("Marski by Scandic", place.title)
    assertEquals("Mannerheimintie 10, 00100 Helsinki", place.subtitle)
    assertEquals(60.17, place.lat, 0.0)
    assertEquals(24.94, place.lng, 0.0)
  }

  @Test
  fun `plain address uses the street as title and adds foreign country`() {
    val body =
        collection(
            feature(
                18.07, 59.33,
                """"street":"Drottninggatan","housenumber":"5","postcode":"111 51",
                   "city":"Stockholm","country":"Sverige","countrycode":"SE"""",
            )
        )

    val place = PhotonGeocoder.parse(body, homeCountry = "FI").single()

    assertEquals("Drottninggatan 5", place.title)
    assertEquals("111 51 Stockholm, Sverige", place.subtitle)
  }

  @Test
  fun `city result has no redundant subtitle`() {
    val body = collection(feature(24.65, 60.2, """"name":"Espoo","city":"Espoo","countrycode":"FI""""))

    val place = PhotonGeocoder.parse(body, homeCountry = "FI").single()

    assertEquals("Espoo", place.title)
    assertNull(place.subtitle)
  }

  @Test
  fun `duplicates and unusable features are dropped`() {
    val named = """"name":"Kamppi","city":"Helsinki","countrycode":"FI""""
    val body =
        collection(
            feature(24.93, 60.169, named),
            feature(24.931, 60.1691, named),
            """{"type":"Feature","properties":{"name":"No geometry"}}""",
        )

    assertEquals(1, PhotonGeocoder.parse(body, homeCountry = "FI").size)
  }

  @Test
  fun `recents put the newest first and replace the same spot`() {
    val home = Place("Home", null, 60.17, 24.94)
    val work = Place("Work", null, 60.20, 24.66)
    val homeAgain = Place("Home (renamed)", null, 60.17005, 24.94005)

    val recents =
        listOf(home, work).let { RecentDestinations.addTo(it, homeAgain, maxEntries = 10) }

    assertEquals(listOf("Home (renamed)", "Work"), recents.map { it.title })
  }

  @Test
  fun `recents are capped`() {
    val places = (1..5).map { Place("P$it", null, 60.0 + it, 24.0) }

    val recents = RecentDestinations.addTo(places, Place("New", null, 50.0, 10.0), maxEntries = 3)

    assertEquals(listOf("New", "P1", "P2"), recents.map { it.title })
  }
}
