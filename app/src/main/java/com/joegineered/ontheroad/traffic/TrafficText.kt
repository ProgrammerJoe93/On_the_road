package com.joegineered.ontheroad.traffic

import android.content.Context
import com.joegineered.ontheroad.R

fun TrafficEventKind.labelRes(): Int =
    when (this) {
      TrafficEventKind.ACCIDENT -> R.string.traffic_accident
      TrafficEventKind.ROAD_CLOSED -> R.string.traffic_road_closed
      TrafficEventKind.CONGESTION -> R.string.traffic_congestion
      TrafficEventKind.ROADWORKS -> R.string.traffic_roadworks
      TrafficEventKind.POOR_CONDITIONS -> R.string.traffic_poor_conditions
      TrafficEventKind.VERY_POOR_CONDITIONS -> R.string.traffic_very_poor_conditions
      TrafficEventKind.SLIPPERY_ROAD -> R.string.traffic_slippery_road
      TrafficEventKind.POOR_VISIBILITY -> R.string.traffic_poor_visibility
      TrafficEventKind.OTHER -> R.string.traffic_notice
    }

/** The spoken warning for an event on the route, e.g. "Roadworks ahead." */
fun trafficAnnouncement(context: Context, warning: TrafficWarning): String =
    context.getString(
        when (warning.event.kind) {
          TrafficEventKind.ACCIDENT -> R.string.voice_accident_ahead
          TrafficEventKind.ROAD_CLOSED -> R.string.voice_road_closed_ahead
          TrafficEventKind.CONGESTION -> R.string.voice_congestion_ahead
          TrafficEventKind.ROADWORKS -> R.string.voice_roadworks_ahead
          TrafficEventKind.POOR_CONDITIONS -> R.string.voice_poor_conditions_ahead
          TrafficEventKind.VERY_POOR_CONDITIONS -> R.string.voice_very_poor_conditions_ahead
          TrafficEventKind.SLIPPERY_ROAD -> R.string.voice_slippery_road_ahead
          TrafficEventKind.POOR_VISIBILITY -> R.string.voice_poor_visibility_ahead
          TrafficEventKind.OTHER -> R.string.voice_traffic_notice_ahead
        }
    )
