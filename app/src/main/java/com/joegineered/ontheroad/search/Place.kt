package com.joegineered.ontheroad.search

import kotlin.math.abs
import kotlinx.serialization.Serializable

/** A searchable, saved or recently used destination. */
@Serializable
data class Place(
    val title: String,
    /** Address details shown under the title, e.g. "Mannerheimintie 10, 00100 Helsinki". */
    val subtitle: String? = null,
    val lat: Double,
    val lng: Double,
) {
  /** Whether [other] is the same spot (within ~20 m), whatever it is called. */
  fun isSameSpot(other: Place) = abs(lat - other.lat) < 0.0002 && abs(lng - other.lng) < 0.0004
}
