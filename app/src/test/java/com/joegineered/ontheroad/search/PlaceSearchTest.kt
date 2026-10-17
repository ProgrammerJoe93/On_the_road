package com.joegineered.ontheroad.search

import java.io.IOException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlaceSearchTest {
  private val queries = mutableListOf<String>()
  private var serverUp = true
  @Volatile private var online = true
  /** The connection drops while a request is out. */
  private var dropConnection = false

  /** Photon answering every query with one place named after it, without a network. */
  private val geocoder =
      PhotonGeocoder(
          OkHttpClient.Builder()
              .addInterceptor { chain ->
                val request = chain.request()
                val q = request.url.queryParameter("q").orEmpty()
                synchronized(queries) { queries += q }
                if (dropConnection) {
                  online = false
                  throw IOException("connection reset")
                }
                if (!serverUp) throw IOException("connection reset")
                val body =
                    """{"features":[{"geometry":{"coordinates":[24.9,60.2]},"properties":{"name":"$q"}}]}"""
                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(body.toResponseBody("application/json".toMediaType()))
                    .build()
              }
              .build(),
          endpoint = "http://photon.test/api/",
      )

  private val saved =
      listOf(
          Place("Jyväskylän matkakeskus", "Hannikaisenkatu 20, Jyväskylä", 62.24, 25.75),
          Place("Kamppi", "00100 Helsinki", 60.17, 24.93),
      )

  /**
   * Lets the debounce pass and runs the search. [kotlinx.coroutines.test.advanceUntilIdle] would
   * skip it, as it runs in the background scope.
   */
  private fun TestScope.settle() {
    advanceTimeBy(1_000)
    runCurrent()
  }

  private fun TestScope.search() =
      PlaceSearch(
          geocoder = geocoder,
          scope = backgroundScope,
          near = { null },
          isOnline = { online },
          savedPlaces = { saved },
      )

  @Test
  fun `short queries don't search`() = runTest {
    val search = search()
    search.onQueryChanged("ka")
    settle()
    assertEquals(SearchState.Idle, search.state.value)
    assertTrue(queries.isEmpty())
  }

  @Test
  fun `typing is debounced so only the last query is sent`() = runTest {
    val search = search()
    search.onQueryChanged("kam")
    search.onQueryChanged("kamp")
    search.onQueryChanged("kamppi")
    val results = search.state.first { it is SearchState.Results } as SearchState.Results
    assertEquals(listOf("kamppi"), results.places.map { it.title })
    assertEquals(listOf("kamppi"), queries)
  }

  @Test
  fun `offline, saved places are searched instead`() = runTest {
    online = false
    val search = search()
    search.onQueryChanged("jyvaskyla")
    val state = search.state.first { it !is SearchState.Idle }
    assertEquals(SearchState.Offline(listOf(saved[0])), state)
    assertTrue(queries.isEmpty())
  }

  @Test
  fun `a failed search shows the error while online`() = runTest {
    serverUp = false
    val search = search()
    search.onQueryChanged("kamppi")
    val state = search.state.first { it is SearchState.Failed }
    assertEquals(SearchState.Failed("connection reset"), state)
  }

  @Test
  fun `a search that fails because the connection dropped falls back to saved places`() = runTest {
    dropConnection = true
    val search = search()
    search.onQueryChanged("kamppi")
    val state = search.state.first { it is SearchState.Offline || it is SearchState.Failed }
    assertEquals(listOf("kamppi"), queries)
    assertEquals(SearchState.Offline(listOf(saved[1])), state)
  }

  @Test
  fun `clearing cancels a pending search`() = runTest {
    val search = search()
    search.onQueryChanged("kamppi")
    search.clear()
    settle()
    assertEquals(SearchState.Idle, search.state.value)
    assertTrue(queries.isEmpty())
  }
}
