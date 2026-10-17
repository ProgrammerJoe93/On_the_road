package com.joegineered.ontheroad.chargers

import com.joegineered.ontheroad.cameras.OverpassCache
import com.joegineered.ontheroad.cameras.OverpassClient
import java.nio.file.Files
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChargerControllerTest {
  // Helsinki city centre, inside one zoom-10 tile.
  private val south = 60.16
  private val west = 24.93
  private val north = 60.17
  private val east = 24.95

  // Nothing listens on port 9: only cached answers can be used.
  private val cache =
      OverpassCache(
          OverpassClient(OkHttpClient(), listOf("http://127.0.0.1:9/api/interpreter"), maxAttempts = 1, initialBackoff = 1.milliseconds),
          Files.createTempDirectory("chargers").toFile(),
      )

  init {
    val tile = Tile.covering(south, west, north, east, ChargerRepository.TILE_ZOOM).single()
    cache.store(
        ChargerRepository.query(tile),
        """{"elements":[
          {"type":"node","id":1,"lat":60.165,"lon":24.94,"tags":{"amenity":"charging_station","name":"In view"}},
          {"type":"node","id":2,"lat":60.30,"lon":24.94,"tags":{"amenity":"charging_station","name":"Out of view"}}
        ]}""",
    )
  }

  /** Lets the camera-settling delay pass and the load run (it runs in the background scope). */
  private fun TestScope.settle() {
    advanceTimeBy(1_000)
    runCurrent()
  }

  @Test
  fun `chargers in view load once the map settles`() = runTest {
    val chargers = ChargerController(ChargerRepository(cache), backgroundScope)
    chargers.onViewportChanged(south, west, north, east, zoom = 14.0)
    settle()
    assertEquals(listOf("In view"), chargers.visible.value.map { it.name })
  }

  @Test
  fun `zoomed out, chargers are hidden`() = runTest {
    val chargers = ChargerController(ChargerRepository(cache), backgroundScope)
    chargers.onViewportChanged(south, west, north, east, zoom = 14.0)
    settle()
    chargers.onViewportChanged(south, west, north, east, zoom = ChargerController.MIN_ZOOM - 1)
    settle()
    assertTrue(chargers.visible.value.isEmpty())
  }

  @Test
  fun `a charger tapped on the map is found by its id`() = runTest {
    val chargers = ChargerController(ChargerRepository(cache), backgroundScope)
    chargers.onViewportChanged(south, west, north, east, zoom = 14.0)
    settle()
    chargers.select("node/1")
    assertEquals("In view", chargers.selected.value?.name)
    chargers.clearSelection()
    assertNull(chargers.selected.value)
    // Unknown ids select nothing.
    chargers.select("node/999")
    assertNull(chargers.selected.value)
  }
}
