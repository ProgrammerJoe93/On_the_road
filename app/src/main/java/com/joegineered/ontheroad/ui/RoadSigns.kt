package com.joegineered.ontheroad.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.joegineered.ontheroad.R
import com.joegineered.ontheroad.traffic.TrafficEvent
import com.joegineered.ontheroad.traffic.labelRes
import com.stadiamaps.ferrostar.composeui.views.components.speedlimit.SpeedLimitView
import com.stadiamaps.ferrostar.core.measurement.MeasurementSpeed

/**
 * The speed limit sign, followed by warning signs while driving through roadworks or poor road
 * weather (slippery road, or a general danger sign for other poor conditions). The limit shown is
 * always the road's permanent one: temporary roadwork limits in the traffic data are too imprecise
 * (extent, hours, direction) to show as a limit, so the warning sign prompts the driver to follow
 * the signs on the road instead.
 */
@Composable
fun RoadSigns(
    speedLimit: MeasurementSpeed?,
    roadworks: Boolean,
    roadWeather: TrafficEvent?,
    modifier: Modifier = Modifier,
) {
  Row(
      modifier = modifier,
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    speedLimit?.let { SpeedLimitView(speedLimit = it, signageStyle = signageStyle()) }
    if (roadworks) {
      Image(
          painter = painterResource(R.drawable.sign_roadworks),
          contentDescription = stringResource(R.string.traffic_roadworks),
          modifier = Modifier.size(width = 56.dp, height = 50.dp),
      )
    }
    roadWeather?.let { weather ->
      Image(
          painter = painterResource(if (weather.slippery) R.drawable.sign_slippery else R.drawable.sign_danger),
          contentDescription = stringResource(weather.kind.labelRes()),
          modifier = Modifier.size(width = 56.dp, height = 50.dp),
      )
    }
  }
}
