package com.joegineered.ontheroad.cameras

import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Test

class OverpassCacheTest {
  private val dir: File = Files.createTempDirectory("overpass").toFile()

  // Nothing listens on port 9, so every query fails fast.
  private val failingClient =
      OverpassClient(OkHttpClient(), listOf("http://127.0.0.1:9/api/interpreter"), maxAttempts = 1, initialBackoff = 1.milliseconds)

  private fun ageCache(days: Int) =
      File(dir, "overpass").listFiles()!!.forEach { it.setLastModified(System.currentTimeMillis() - days.days.inWholeMilliseconds) }

  @Test
  fun `stale answers are used when the server can't be reached`() = runTest {
    val cache = OverpassCache(failingClient, dir)
    cache.store("q", "cached")
    ageCache(10)
    assertEquals("cached", cache.query("q"))
  }

  @Test
  fun `offline, stale answers are used without trying the network`() = runTest {
    val cache = OverpassCache(failingClient, dir, isOnline = { false })
    cache.store("q", "cached")
    ageCache(10)
    assertEquals("cached", cache.query("q"))
  }

  @Test(expected = IOException::class)
  fun `offline without a cached answer fails`() = runTest {
    OverpassCache(failingClient, dir, isOnline = { false }).query("q")
  }

  @Test
  fun `very old entries are pruned`() = runTest {
    OverpassCache(failingClient, dir).store("q", "cached")
    ageCache(90)
    OverpassCache(failingClient, dir)
    assertEquals(0, File(dir, "overpass").listFiles()!!.size)
  }
}
