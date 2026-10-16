package com.joegineered.ontheroad.chargers

import com.joegineered.ontheroad.cameras.LatLng
import com.joegineered.ontheroad.cameras.OverpassCache
import com.joegineered.ontheroad.cameras.OverpassClient
import java.nio.file.Files
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChargerPrefetchTest {
  private val helsinki = Tile.containing(60.17, 24.94, ChargerRepository.TILE_ZOOM)
  private val east = helsinki.copy(x = helsinki.x + 1)

  @Test
  fun `neighbouring tiles in a row share one box`() {
    val farAway = helsinki.copy(x = helsinki.x + 5)
    val query = ChargerRepository.unionQuery(listOf(east, helsinki, farAway))
    assertEquals(2, Regex("nwr\\[").findAll(query).count())
  }

  @Test
  fun `prefetched chargers work offline, per tile and nearby`() = runTest {
    // Offline: only cached answers are available.
    val overpass =
        OverpassCache(
            OverpassClient(OkHttpClient(), listOf("http://127.0.0.1:9/"), maxAttempts = 1, initialBackoff = 1.milliseconds),
            Files.createTempDirectory("overpass").toFile(),
            isOnline = { false },
        )
    val inHelsinki = node(1, (helsinki.south + helsinki.north) / 2, (helsinki.west + helsinki.east) / 2)
    val inEast = node(2, (east.south + east.north) / 2, (east.west + east.east) / 2)
    // As if the large query had just been answered.
    overpass.store(ChargerRepository.unionQuery(listOf(helsinki, east)), """{"elements":[$inHelsinki,$inEast]}""")

    val repository = ChargerRepository(overpass)
    repository.prefetch(listOf(helsinki, east))

    val inTile = repository.inBounds(helsinki.south, helsinki.west, helsinki.north, helsinki.east)!!
    assertEquals(listOf("node/1"), inTile.map { it.id })
    val nearby = repository.nearest(LatLng((helsinki.south + helsinki.north) / 2, (helsinki.west + helsinki.east) / 2))
    assertTrue(nearby.any { it.first.id == "node/1" })
  }

  private fun node(id: Long, lat: Double, lon: Double) =
      """{"type":"node","id":$id,"lat":$lat,"lon":$lon,"tags":{"amenity":"charging_station","socket:type2":"2"}}"""
}
