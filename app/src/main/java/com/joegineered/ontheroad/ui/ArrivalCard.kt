package com.joegineered.ontheroad.ui

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.joegineered.ontheroad.R
import com.joegineered.ontheroad.config.AppConfig
import com.joegineered.ontheroad.nav.Arrival
import java.util.Locale

@Composable
fun ArrivalCard(
    arrival: Arrival,
    onDone: () -> Unit,
    onContinue: () -> Unit,
    modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  Card(modifier.fillMaxWidth()) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        Icon(
            painterResource(R.drawable.ic_flag),
            contentDescription = null,
            tint = Color(0xFF2E7D32),
            modifier = Modifier.size(32.dp),
        )
        Text(stringResource(R.string.arrived_title), style = MaterialTheme.typography.titleLarge)
      }
      arrival.destination?.label?.let {
        Text(it, style = MaterialTheme.typography.titleMedium)
      }
      arrival.destination?.subtitle?.let {
        Text(
            it,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      Text(
          arrivalSummary(context, arrival),
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      // After a stop on the way, carrying on is the likely next step.
      arrival.continueTo?.let { next ->
        Button(onClick = onContinue, modifier = Modifier.fillMaxWidth()) {
          Text(
              next.label?.let { stringResource(R.string.continue_to, it) }
                  ?: stringResource(R.string.continue_trip)
          )
        }
      }
      if (arrival.continueTo == null) {
        Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.done)) }
      } else {
        OutlinedButton(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.done)) }
      }
    }
  }
}

/** "12.4 km · 18 min", for the phone card and the car screen. */
fun arrivalSummary(context: Context, arrival: Arrival): String =
    context.getString(
        R.string.arrival_summary,
        formatTripDistance(arrival.distanceMetres),
        formatDuration(context, arrival.durationSeconds),
    )

fun formatTripDistance(metres: Double): String =
    if (AppConfig.usesMetricUnits) {
      if (metres < 1000) "${metres.toInt()} m" else String.format(Locale.getDefault(), "%.1f km", metres / 1000)
    } else {
      String.format(Locale.getDefault(), "%.1f mi", metres / 1609.344)
    }

fun formatDuration(context: Context, seconds: Long): String {
  val minutes = ((seconds + 30) / 60).coerceAtLeast(1)
  return if (minutes < 60) context.getString(R.string.duration_minutes, minutes)
  else context.getString(R.string.duration_hours_minutes, minutes / 60, minutes % 60)
}
