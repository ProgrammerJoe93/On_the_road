package com.joegineered.ontheroad.cameras

import java.io.File
import java.security.MessageDigest
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** Overpass responses cached on disk, so repeated queries don't hit the public servers again. */
class OverpassCache(
    private val client: OverpassClient,
    cacheRoot: File,
    private val ttl: Duration = 7.days,
) {
  private val dir = File(cacheRoot, "overpass").apply { mkdirs() }

  /** Public Overpass servers reject bursts of parallel queries, so only a couple run at once. */
  private val inFlight = Semaphore(MAX_PARALLEL_QUERIES)

  suspend fun query(query: String): String {
    val file = File(dir, sha256(query) + ".json")
    val fresh = file.exists() && System.currentTimeMillis() - file.lastModified() < ttl.inWholeMilliseconds
    if (fresh) return file.readText()
    return inFlight.withPermit { client.execute(query) }.also { file.writeText(it) }
  }

  private companion object {
    const val MAX_PARALLEL_QUERIES = 2
  }

  private fun sha256(text: String): String =
      MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") {
        "%02x".format(it)
      }
}
