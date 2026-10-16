package com.joegineered.ontheroad.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class NetworkStatus(
    /** A network with working internet access (validated by Android). */
    val online: Boolean,
    /** Unmetered, typically Wi-Fi: large downloads are fine. */
    val unmetered: Boolean,
) {
  companion object {
    val OFFLINE = NetworkStatus(online = false, unmetered = false)
  }
}

/** Tracks the default network, so the app can tell the driver it's offline and fail fast. */
class NetworkMonitor(context: Context) {
  private val connectivity = context.getSystemService(ConnectivityManager::class.java)

  private val _status = MutableStateFlow(currentStatus())
  val status: StateFlow<NetworkStatus> = _status.asStateFlow()

  val isOnline: Boolean
    get() = _status.value.online

  init {
    connectivity.registerDefaultNetworkCallback(
        object : ConnectivityManager.NetworkCallback() {
          override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            _status.value = capabilities.toStatus()
          }

          override fun onLost(network: Network) {
            _status.value = NetworkStatus.OFFLINE
          }
        }
    )
  }

  private fun currentStatus(): NetworkStatus =
      connectivity.activeNetwork?.let { connectivity.getNetworkCapabilities(it) }?.toStatus()
          ?: NetworkStatus.OFFLINE

  private fun NetworkCapabilities.toStatus() =
      NetworkStatus(
          online =
              hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                  hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
          unmetered = hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
      )
}
