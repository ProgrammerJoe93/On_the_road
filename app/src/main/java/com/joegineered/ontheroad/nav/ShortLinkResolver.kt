package com.joegineered.ontheroad.nav

import android.util.Log
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Follows a short map link (such as `maps.app.goo.gl/…` from the Google Maps share sheet) to the
 * full link with the place in it. Only the redirects are read, never the pages. [redirect]
 * returns where a link redirects to, or null if it doesn't.
 */
class ShortLinkResolver(
    private val redirect: suspend (HttpUrl) -> HttpUrl?,
    private val maxRedirects: Int = 5,
) {
  constructor(httpClient: OkHttpClient) : this(redirectsWith(httpClient))

  /** The place the link leads to, or null if it leads nowhere we can read or can't be reached. */
  suspend fun resolve(url: HttpUrl): DestinationRequest? {
    var current = url
    repeat(maxRedirects) {
      val next =
          try {
            redirect(current)
          } catch (e: CancellationException) {
            throw e
          } catch (e: IOException) {
            Log.w(TAG, "Could not follow $current", e)
            return null
          } ?: return null
      parseMapUrl(next)?.let { return it }
      current = next
    }
    return null
  }

  companion object {
    private const val TAG = "ShortLinkResolver"
    private const val USER_AGENT = "OnTheRoad/0.1 (Android navigation app)"

    private fun redirectsWith(httpClient: OkHttpClient): suspend (HttpUrl) -> HttpUrl? {
      val client = httpClient.newBuilder().followRedirects(false).followSslRedirects(false).build()
      return { url ->
        withContext(Dispatchers.IO) {
          val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
          client.newCall(request).execute().use { response ->
            if (!response.isRedirect) null
            else response.header("Location")?.let { url.resolve(it) }
          }
        }
      }
    }
  }
}
