package com.joegineered.ontheroad.search

import kotlinx.serialization.Serializable

/** A searchable or recently used destination. */
@Serializable
data class Place(
    val title: String,
    /** Address details shown under the title, e.g. "Mannerheimintie 10, 00100 Helsinki". */
    val subtitle: String? = null,
    val lat: Double,
    val lng: Double,
)
