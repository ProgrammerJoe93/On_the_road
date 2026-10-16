package com.joegineered.ontheroad.traffic

import android.content.Context
import com.joegineered.ontheroad.R

fun TrafficEventKind.labelRes(): Int =
    when (this) {
      TrafficEventKind.ACCIDENT -> R.string.traffic_accident
      TrafficEventKind.ROAD_CLOSED -> R.string.traffic_road_closed
      TrafficEventKind.CONGESTION -> R.string.traffic_congestion
      TrafficEventKind.ROADWORKS -> R.string.traffic_roadworks
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
          TrafficEventKind.OTHER -> R.string.voice_traffic_notice_ahead
        }
    )
