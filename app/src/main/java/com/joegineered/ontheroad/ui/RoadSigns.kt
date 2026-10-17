package com.joegineered.ontheroad.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.joegineered.ontheroad.R
import com.joegineered.ontheroad.traffic.TrafficEvent
import com.joegineered.ontheroad.traffic.VariableSign
import com.joegineered.ontheroad.traffic.labelRes
import com.stadiamaps.ferrostar.composeui.views.components.speedlimit.SpeedLimitView
import com.stadiamaps.ferrostar.core.measurement.MeasurementSpeed
import com.stadiamaps.ferrostar.core.measurement.MeasurementSpeedUnit
import kotlinx.coroutines.delay

/** The limit to show: a variable speed limit sign passed on the route, else the mapped limit. */
fun effectiveSpeedLimit(mapped: MeasurementSpeed?, variableKmh: Int?): MeasurementSpeed? =
    variableKmh?.let { MeasurementSpeed(it.toDouble(), MeasurementSpeedUnit.KilometersPerHour) } ?: mapped

/**
 * The speed limit sign, followed by warning signs while driving through roadworks or poor road
 * weather (slippery road, or a general danger sign for other poor conditions), and after passing a
 * variable warning sign. The limit shown is the mapped one, or a variable speed limit from the
 * signs on the road (see [effectiveSpeedLimit]). Temporary roadwork limits in the traffic data are
 * too imprecise (extent, hours, direction) to show as a limit, so the roadworks sign prompts the
 * driver to follow the signs on the road instead.
 */
@Composable
fun RoadSigns(
    speedLimit: MeasurementSpeed?,
    roadworks: Boolean,
    roadWeather: TrafficEvent?,
    warningCodes: List<String> = emptyList(),
    modifier: Modifier = Modifier,
) {
  // Variable warning signs repeat what the traffic data already shows only once.
  val warnings =
      warningCodes.filterNot {
        (it == ROADWORKS_CODE && roadworks) || (it == SLIPPERY_CODE && roadWeather?.slippery == true)
      }
  Row(
      modifier = modifier,
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    speedLimit?.let { SpeedLimitView(speedLimit = it, signageStyle = signageStyle()) }
    if (roadworks) WarningSign(R.drawable.sign_roadworks, R.string.traffic_roadworks)
    roadWeather?.let { weather ->
      WarningSign(if (weather.slippery) R.drawable.sign_slippery else R.drawable.sign_danger, weather.kind.labelRes())
    }
    warnings.forEach { code -> WarningSign(warningSignDrawable(code), warningSignLabel(code)) }
  }
}

@Composable
private fun WarningSign(drawable: Int, label: Int) {
  Image(
      painter = painterResource(drawable),
      contentDescription = stringResource(label),
      modifier = Modifier.size(width = 56.dp, height = 50.dp),
  )
}

/**
 * A variable message sign's text, drawn like the sign itself: amber capitals on black. Signs with
 * more than one screen alternate them, as on the road.
 */
@Composable
fun VariableMessageSign(sign: VariableSign, modifier: Modifier = Modifier) {
  var screen by remember(sign.id) { mutableIntStateOf(0) }
  LaunchedEffect(sign.id, sign.screens.size) {
    while (sign.screens.size > 1) {
      delay(SCREEN_MILLIS)
      screen = (screen + 1) % sign.screens.size
    }
  }
  val rows = sign.screens.getOrNull(screen) ?: sign.screens.firstOrNull() ?: return
  Column(modifier.widthIn(max = 240.dp), horizontalAlignment = Alignment.CenterHorizontally) {
    Row(
        modifier =
            Modifier.shadow(6.dp, RoundedCornerShape(8.dp))
                .background(Color.Black, RoundedCornerShape(8.dp))
                .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      sign.warningCode?.let { code ->
        Image(
            painter = painterResource(warningSignDrawable(code)),
            contentDescription = stringResource(warningSignLabel(code)),
            modifier = Modifier.size(width = 34.dp, height = 30.dp),
        )
      }
      Column(horizontalAlignment = Alignment.CenterHorizontally) {
        rows.forEach { row ->
          Text(
              row,
              color = SIGN_AMBER,
              fontFamily = FontFamily.Monospace,
              fontWeight = FontWeight.Bold,
              fontSize = 14.sp,
              textAlign = TextAlign.Center,
          )
        }
      }
    }
    Text(
        stringResource(R.string.variable_sign_attribution),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
}

/** The warning sign shown for a Finnish traffic sign code; signs without their own art use "!". */
private fun warningSignDrawable(code: String): Int =
    when (code) {
      ROADWORKS_CODE -> R.drawable.sign_roadworks
      SLIPPERY_CODE -> R.drawable.sign_slippery
      else -> R.drawable.sign_danger
    }

private fun warningSignLabel(code: String): Int =
    when (code) {
      "133" -> R.string.warning_sign_congestion
      "141" -> R.string.warning_sign_uneven_road
      ROADWORKS_CODE -> R.string.traffic_roadworks
      "143" -> R.string.warning_sign_loose_gravel
      SLIPPERY_CODE -> R.string.warning_sign_slippery
      "147" -> R.string.warning_sign_dangerous_shoulders
      "154" -> R.string.warning_sign_ski_track
      "155" -> R.string.warning_sign_elk
      "156" -> R.string.warning_sign_reindeer
      "165" -> R.string.warning_sign_traffic_lights
      "183" -> R.string.warning_sign_crosswind
      else -> R.string.warning_sign_danger
    }

private const val ROADWORKS_CODE = "142"
private const val SLIPPERY_CODE = "144"
private const val SCREEN_MILLIS = 3_000L
private val SIGN_AMBER = Color(0xFFFFB300)
