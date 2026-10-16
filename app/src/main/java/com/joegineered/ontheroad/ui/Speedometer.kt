package com.joegineered.ontheroad.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.joegineered.ontheroad.R
import com.stadiamaps.ferrostar.core.measurement.MeasurementSpeed
import com.stadiamaps.ferrostar.core.measurement.MeasurementSpeedUnit
import kotlin.math.roundToInt

/** What the speedometer shows: the current speed, and whether it's over the limit. */
data class SpeedReading(val speed: Int?, val overLimit: Boolean)

/**
 * GPS speed in the display unit, compared with the road's speed limit in the same unit. Over the
 * limit means the speed shown is higher than the limit shown on the sign.
 */
fun speedReading(
    speedMetresPerSecond: Double?,
    speedLimit: MeasurementSpeed?,
    metric: Boolean,
): SpeedReading {
  val unit = if (metric) MeasurementSpeedUnit.KilometersPerHour else MeasurementSpeedUnit.MilesPerHour
  val speed =
      speedMetresPerSecond
          ?.takeIf { it >= 0 }
          ?.let { MeasurementSpeed(it, MeasurementSpeedUnit.MetersPerSecond).value(unit).roundToInt() }
  val limit = speedLimit?.value(unit)?.roundToInt()
  return SpeedReading(speed, overLimit = speed != null && limit != null && speed > limit)
}

/** Current speed in a rounded box; red with white text when over the speed limit. */
@Composable
fun Speedometer(reading: SpeedReading, metric: Boolean, modifier: Modifier = Modifier) {
  val background by
      animateColorAsState(
          if (reading.overLimit) Color(0xFFD32F2F) else MaterialTheme.colorScheme.surface,
          label = "speedometerBackground",
      )
  val content = if (reading.overLimit) Color.White else MaterialTheme.colorScheme.onSurface
  val unit = stringResource(if (metric) R.string.unit_kmh else R.string.unit_mph)
  val speedText = reading.speed?.toString() ?: "–"

  Column(
      modifier =
          modifier
              .shadow(6.dp, RoundedCornerShape(16.dp))
              .background(background, RoundedCornerShape(16.dp))
              .border(
                  width = 2.dp,
                  color = if (reading.overLimit) Color.White else Color.Transparent,
                  shape = RoundedCornerShape(16.dp),
              )
              .sizeIn(minWidth = 72.dp)
              .padding(horizontal = 12.dp, vertical = 6.dp)
              .semantics { contentDescription = "$speedText $unit" },
      horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Text(speedText, color = content, fontSize = 30.sp, fontWeight = FontWeight.Bold)
    Text(unit, color = content, style = MaterialTheme.typography.labelSmall)
  }
}
