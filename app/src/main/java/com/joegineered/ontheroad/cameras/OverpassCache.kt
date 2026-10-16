package com.joegineered.ontheroad.cameras

import android.util.Log
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Overpass responses cached on disk, so repeated queries don't hit the public servers again.
 * Answers older than [ttl] are refreshed, but are still used when the refresh fails or the device
 * is offline, so cameras and chargers keep working without a connection.
 */
class OverpassCache(
    private val client: OverpassClient,
    cacheRoot: File,
    private val ttl: Duration = 7.days,
    /** Entries not refreshed for this long are deleted. */
    maxAge: Duration = 60.days,
    private val isOnline: () -> Boolean = { true },
) {
  private val dir = File(cacheRoot, "overpass").apply { mkdirs() }

  /** Public Overpass servers reject bursts of parallel queries, so only a couple run at once. */
  private val inFlight = Semaphore(MAX_PARALLEL_QUERIES)

  init {
    val cutoff = System.currentTimeMillis() - maxAge.inWholeMilliseconds
    dir.listFiles()?.filter { it.lastModified() < cutoff }?.forEach { it.delete() }
  }

  suspend fun query(query: String): String {
    val file = fileFor(query)
    val exists = file.exists()
    val fresh = exists && System.currentTimeMillis() - file.lastModified() < ttl.inWholeMilliseconds
    if (fresh) return file.readText()
    // Offline, the client would spend its retries failing; answer from the cache straight away.
    if (!isOnline()) {
      if (exists) return file.readText()
      throw IOException("Offline, and no cached Overpass answer")
    }
    return try {
      inFlight.withPermit { client.execute(query) }.also { file.writeText(it) }
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      if (!exists) throw e
      Log.w(TAG, "Overpass query failed; using a cached answer", e)
      file.readText()
    }
  }

  /** Saves [body] as the answer to [query], e.g. per-tile slices of one larger query. */
  fun store(query: String, body: String) {
    fileFor(query).writeText(body)
  }

  private fun fileFor(query: String) = File(dir, sha256(query) + ".json")

  private companion object {
    const val TAG = "OverpassCache"
    const val MAX_PARALLEL_QUERIES = 2
  }

  private fun sha256(text: String): String =
      MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") {
        "%02x".format(it)
      }
}
