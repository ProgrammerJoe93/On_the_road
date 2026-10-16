package com.joegineered.ontheroad.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.joegineered.ontheroad.R
import com.joegineered.ontheroad.cameras.CameraWarning
import com.joegineered.ontheroad.cameras.displayedSpeedLimit
import com.joegineered.ontheroad.config.AppConfig
import kotlin.math.roundToInt

@Composable
fun CameraWarningBadge(warning: CameraWarning, modifier: Modifier = Modifier) {
  Row(
      modifier =
          modifier
              .background(Color(0xFFD32F2F), RoundedCornerShape(12.dp))
              .padding(horizontal = 12.dp, vertical = 8.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    Icon(
        painter = painterResource(R.drawable.ic_speed_camera),
        contentDescription = null,
        tint = Color.White,
        modifier = Modifier.size(24.dp),
    )
    Text(
        text = stringResource(R.string.speed_camera_in, formatDistance(warning.distanceMetres)),
        color = Color.White,
        style = MaterialTheme.typography.titleMedium,
    )
    warning.camera.maxSpeedKmh?.let { limit ->
      Text(
          text = displayedSpeedLimit(limit, AppConfig.usesMetricUnits).toString(),
          color = Color.Black,
          style = MaterialTheme.typography.titleMedium,
          modifier =
              Modifier.background(Color.White, RoundedCornerShape(50))
                  .padding(horizontal = 8.dp, vertical = 2.dp),
      )
    }
  }
}

/** Rounds to friendly steps: 50 m below 1 km, then 0.1 km (or miles/feet in imperial regions). */
fun formatDistance(metres: Double): String =
    if (AppConfig.usesMetricUnits) {
      if (metres < 1000) "${(metres / 50).roundToInt() * 50} m"
      else "%.1f km".format(metres / 1000)
    } else {
      val feet = metres * 3.28084
      if (feet < 1000) "${(feet / 100).roundToInt() * 100} ft"
      else "%.1f mi".format(metres / 1609.344)
    }
