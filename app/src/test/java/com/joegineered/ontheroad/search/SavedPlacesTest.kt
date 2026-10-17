package com.joegineered.ontheroad.search

import com.joegineered.ontheroad.search.SavedPlaceKind.FAVOURITE
import com.joegineered.ontheroad.search.SavedPlaceKind.HOME
import com.joegineered.ontheroad.search.SavedPlaceKind.WORK
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SavedPlacesTest {
  private val house = Place("Kotikatu 1", "00100 Helsinki", 60.1700, 24.9400)
  private val office = Place("Toimisto", "Tampere", 61.4980, 23.7600)
  private val cottage = Place("Mökki", "Mikkeli", 61.6880, 27.2720)
  private val sauna = Place("Sauna", "Kuopio", 62.8920, 27.6780)

  @Test
  fun `home and work come first, then favourites newest first`() {
    var saved = SavedPlaces.saving(emptyList(), FAVOURITE, cottage)
    saved = SavedPlaces.saving(saved, WORK, office)
    saved = SavedPlaces.saving(saved, FAVOURITE, sauna)
    saved = SavedPlaces.saving(saved, HOME, house)
    assertEquals(
        listOf(SavedPlace(HOME, house), SavedPlace(WORK, office), SavedPlace(FAVOURITE, sauna), SavedPlace(FAVOURITE, cottage)),
        saved,
    )
  }

  @Test
  fun `a new home replaces the old one`() {
    val saved = SavedPlaces.saving(listOf(SavedPlace(HOME, house)), HOME, cottage)
    assertEquals(listOf(SavedPlace(HOME, cottage)), saved)
  }

  @Test
  fun `a spot is saved once, under its latest kind`() {
    val moved = house.copy(title = "Home again", lat = house.lat + 0.0001)
    val saved = SavedPlaces.saving(listOf(SavedPlace(FAVOURITE, house)), HOME, moved)
    assertEquals(listOf(SavedPlace(HOME, moved)), saved)
  }

  @Test
  fun `removing and renaming match the spot`() {
    val saved = listOf(SavedPlace(HOME, house), SavedPlace(FAVOURITE, cottage))
    val renamedCopy = cottage.copy(title = "Something else")
    assertEquals(listOf(SavedPlace(HOME, house)), SavedPlaces.removing(saved, renamedCopy))
    assertEquals("Grandma's", SavedPlaces.renaming(saved, cottage, " Grandma's ")[1].place.title)
    assertEquals(saved, SavedPlaces.renaming(saved, cottage, "  "))
  }

  @Test
  fun `home is listed by its role with the address below`() {
    val shown = SavedPlace(HOME, house).display("Home", "Work")
    assertEquals("Home", shown.title)
    assertEquals("Kotikatu 1, 00100 Helsinki", shown.subtitle)
    assertEquals(cottage, SavedPlace(FAVOURITE, cottage).display("Home", "Work"))
  }

  @Test
  fun `quick places skip recents that are saved`() {
    val quick = quickPlaces(listOf(SavedPlace(WORK, office)), listOf(office, sauna), "Home", "Work")
    assertEquals(listOf(WORK, null), quick.map { it.kind })
    assertEquals(sauna, quick[1].place)
    assertNull(SavedPlaces.find(listOf(SavedPlace(WORK, office)), sauna))
  }
}
