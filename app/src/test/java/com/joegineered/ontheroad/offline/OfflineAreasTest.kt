package com.joegineered.ontheroad.offline

import com.joegineered.ontheroad.cameras.LatLng
import com.joegineered.ontheroad.cameras.distanceMetres
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineAreasTest {
  private val tiles = "https://tiles.openfreemap.org/planet/20261011_080001_pt/{z}/{x}/{y}.pbf"
  private val newerTiles = "https://tiles.openfreemap.org/planet/20261018_080001_pt/{z}/{x}/{y}.pbf"

  private fun info(id: String = "a", generation: Int = 1, kind: OfflineAreaKind = OfflineAreaKind.AREA, created: Long = 0) =
      OfflineAreaInfo(id, "Helsinki", kind, created, generation, tiles)

  private fun done(bytes: Long = 100) = PackProgress(10, 10, bytes, complete = true)

  private fun snapshot(pack: String, info: OfflineAreaInfo, progress: PackProgress) = PackSnapshot(pack, info, progress)

  @Test
  fun `day and night packs make one area, sized by the larger pack`() {
    val area =
        summarize(listOf(snapshot("day", info(), done(300)), snapshot("night", info(), done(320))), tiles).single()

    assertEquals(OfflineAreaState.Ready, area.state)
    assertEquals(320, area.sizeBytes)
    assertFalse(area.outdated)
    assertEquals(listOf("day", "night"), area.packs)
  }

  @Test
  fun `progress combines both packs and is unknown until both sizes are known`() {
    val half = PackProgress(completedResources = 5, requiredResources = 10)
    val area = summarize(listOf(snapshot("day", info(), half), snapshot("night", info(), done())), null).single()
    assertEquals(OfflineAreaState.Downloading(0.75f), area.state)

    val unsized = summarize(listOf(snapshot("day", info(), PackProgress()), snapshot("night", info(), half)), null)
    assertEquals(OfflineAreaState.Downloading(null), unsized.single().state)
  }

  @Test
  fun `a complete area with older map data than the server is outdated`() {
    val area = summarize(listOf(snapshot("day", info(), done())), newerTiles).single()
    assertTrue(area.outdated)
    // Unknown server version: not outdated.
    assertFalse(summarize(listOf(snapshot("day", info(), done())), null).single().outdated)
  }

  @Test
  fun `an update in progress keeps the old generation until the new one is complete`() {
    val updating =
        listOf(
            snapshot("old", info(generation = 1), done()),
            snapshot("new", info(generation = 2).copy(tiles = newerTiles), PackProgress(2, 10)),
        )
    val area = summarize(updating, newerTiles).single()
    assertEquals(OfflineAreaState.Updating(0.2f), area.state)
    assertEquals(2, area.info.generation)
    assertTrue(obsoletePacks(area).isEmpty())

    val finished = updating.map { if (it.pack == "new") it.copy(progress = done()) else it }
    assertEquals(listOf("old"), obsoletePacks(summarize(finished, newerTiles).single()))
  }

  @Test
  fun `a download error shows as failed`() {
    val area = summarize(listOf(snapshot("day", info(), PackProgress(error = "Connection"))), null).single()
    assertEquals(OfflineAreaState.Failed("Connection"), area.state)
  }

  @Test
  fun `only the most recent routes are kept, and picked areas never pruned`() {
    val areas =
        summarize(
            listOf(
                snapshot("r1", info("r1", kind = OfflineAreaKind.ROUTE, created = 1), done()),
                snapshot("r2", info("r2", kind = OfflineAreaKind.ROUTE, created = 2), done()),
                snapshot("r3", info("r3", kind = OfflineAreaKind.ROUTE, created = 3), done()),
                snapshot("a", info("a", created = 0), done()),
            ),
            null,
        )
    assertEquals(listOf("r1"), excessRoutes(areas, keep = 2).map { it.info.id })
  }

  @Test
  fun `tile count sums every zoom level`() {
    // A point-sized box is one tile per zoom.
    val point = Bounds(60.17, 24.94, 60.17, 24.94)
    assertEquals(15, tileCount(point, 0, 14))
    // About 50 km square around Helsinki: a few thousand tiles.
    val helsinki = Bounds(60.0, 24.5, 60.45, 25.4)
    assertTrue(tileCount(helsinki, 0, 14) in 1_000..10_000)
  }

  @Test
  fun `route corridor covers the whole route with margin`() {
    // About 60 km north from Helsinki, a point every km.
    val route = (0..60).map { LatLng(60.17 + it * 0.009, 24.94) }
    val boxes = routeCorridor(route, stepMetres = 5_000.0, marginMetres = 1_500.0)

    assertTrue(boxes.size in 10..14)
    route.forEach { point ->
      assertTrue(boxes.any { point.lat in it.south..it.north && point.lng in it.west..it.east })
    }
    // Each box is about 3 km wide: the margin either side of a straight route.
    val box = boxes.first()
    val width = distanceMetres(LatLng(box.south, box.west), LatLng(box.south, box.east))
    assertEquals(3_000.0, width, 100.0)
  }

  @Test
  fun `a long straight segment is split into several boxes`() {
    val route = listOf(LatLng(60.0, 24.0), LatLng(60.0, 25.0)) // about 56 km east
    val boxes = routeCorridor(route, stepMetres = 5_000.0)
    assertTrue(boxes.size in 8..12)
    // At most a step plus one densified piece (6.25 km), plus the margin either side.
    boxes.forEach { assertTrue(distanceMetres(LatLng(it.south, it.west), LatLng(it.south, it.east)) < 9_300) }
  }

  @Test
  fun `metadata and tile templates parse`() {
    val decoded = OfflineMaps.decode("""{"id":"x","name":"Oulu","kind":"ROUTE","createdAtMillis":5,"extra":1}""".toByteArray())
    assertEquals(OfflineAreaInfo("x", "Oulu", OfflineAreaKind.ROUTE, 5), decoded)
    assertNull(OfflineMaps.decode("not json".toByteArray()))
    assertEquals(tiles, OfflineMaps.parseTileTemplate("""{"tilejson":"3.0.0","tiles":["$tiles"]}"""))
  }

  @Test
  fun `bounds grow by a distance on every side`() {
    val box = Bounds(60.0, 24.0, 60.1, 24.2).expandedBy(1_000.0)
    assertEquals(1_000.0, distanceMetres(LatLng(60.0, 24.1), LatLng(box.south, 24.1)), 5.0)
    assertEquals(1_000.0, distanceMetres(LatLng(60.05, 24.2), LatLng(60.05, box.east)), 5.0)
    assertEquals(1_000.0, distanceMetres(LatLng(60.05, 24.0), LatLng(60.05, box.west)), 5.0)
  }

  @Test
  fun `areas bigger than a region are too large to download`() {
    // Helsinki and around: fine. All of southern Finland: too many tiles.
    assertFalse(OfflineMaps.isTooLarge(Bounds(60.1, 24.7, 60.3, 25.2)))
    assertTrue(OfflineMaps.isTooLarge(Bounds(60.0, 21.0, 63.0, 30.0)))
  }
}
