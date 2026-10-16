package com.joegineered.ontheroad.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.joegineered.ontheroad.AppGraph
import com.joegineered.ontheroad.R
import com.joegineered.ontheroad.cameras.LatLng
import com.joegineered.ontheroad.cameras.distanceMetres
import com.joegineered.ontheroad.chargers.ChargingStation
import com.joegineered.ontheroad.chargers.SocketType
import com.joegineered.ontheroad.chargers.formatKw

private val ChargerGreen = Color(0xFF2E7D32)

/** Details of a charger with a Navigate button. */
@Composable
fun ChargerCard(
    station: ChargingStation,
    distanceMetres: Double?,
    onNavigate: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
  Card(modifier.fillMaxWidth()) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(painterResource(R.drawable.ic_bolt), null, tint = ChargerGreen, modifier = Modifier.size(28.dp))
        Column(Modifier.weight(1f).padding(start = 8.dp)) {
          Text(
              station.title ?: stringResource(R.string.charger_default_title),
              style = MaterialTheme.typography.titleMedium,
          )
          station.operator?.takeIf { it != station.title }?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
          }
        }
        IconButton(onClick = onClose) {
          Icon(painterResource(R.drawable.ic_close), stringResource(R.string.close))
        }
      }

      station.sockets
          .sortedWith(compareByDescending<com.joegineered.ontheroad.chargers.Socket> { it.type.dc }
              .thenByDescending { it.outputKw ?: 0.0 })
          .forEach { socket ->
            Text(
                listOfNotNull(
                        socket.type.label,
                        socket.count?.let { "× $it" },
                        socket.outputKw?.let { formatKw(it) },
                    )
                    .joinToString("  "),
                // Your car's fast-charging plug stands out.
                fontWeight = if (socket.type == SocketType.CCS) FontWeight.Bold else FontWeight.Normal,
                color = if (socket.type == SocketType.CCS) ChargerGreen else Color.Unspecified,
            )
          }

      val details =
          listOfNotNull(
              distanceMetres?.let { formatTripDistance(it) },
              station.capacity?.let { stringResource(R.string.charger_spots, it) },
              station.fee?.let { stringResource(if (it) R.string.charger_paid else R.string.charger_free) },
              station.openingHours,
          )
      if (details.isNotEmpty()) {
        Text(
            details.joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }

      Button(onClick = onNavigate, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.navigate))
      }
    }
  }
}

/** The nearest chargers to [position], with a fast-charging filter. */
@Composable
fun NearbyChargersCard(
    position: LatLng?,
    onSelect: (ChargingStation) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
  var fastOnly by remember { mutableStateOf(true) }
  var results by remember { mutableStateOf<List<Pair<ChargingStation, Double>>?>(null) }
  var failed by remember { mutableStateOf(false) }
  var attempt by remember { mutableStateOf(0) }

  LaunchedEffect(position == null, fastOnly, attempt) {
    position ?: return@LaunchedEffect
    results = null
    failed = false
    results =
        runCatching { AppGraph.chargers.nearest(position, fastOnly) }
            .onFailure { failed = true }
            .getOrDefault(emptyList())
  }

  Card(modifier.fillMaxWidth()) {
    Column {
      Row(
          Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 4.dp),
          verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(
            stringResource(R.string.chargers_nearby),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f),
        )
        FilterChip(
            selected = fastOnly,
            onClick = { fastOnly = !fastOnly },
            label = { Text(stringResource(R.string.fast_only)) },
        )
        IconButton(onClick = onClose) {
          Icon(painterResource(R.drawable.ic_close), stringResource(R.string.close))
        }
      }
      HorizontalDivider()
      val list = results
      when {
        failed ->
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
              Text(
                  stringResource(R.string.chargers_failed),
                  color = MaterialTheme.colorScheme.error,
                  modifier = Modifier.weight(1f),
              )
              OutlinedButton(onClick = { attempt++ }) { Text(stringResource(R.string.retry)) }
            }
        position == null -> Text(stringResource(R.string.no_location_yet), Modifier.padding(16.dp))
        list == null -> Text(stringResource(R.string.loading), Modifier.padding(16.dp))
        list.isEmpty() -> Text(stringResource(R.string.no_chargers_found), Modifier.padding(16.dp))
        else ->
            LazyColumn(Modifier.heightIn(max = 340.dp)) {
              items(list, key = { it.first.id }) { (station, distance) ->
                Row(
                    Modifier.fillMaxWidth()
                        .clickable { onSelect(station) }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                  Icon(
                      painterResource(R.drawable.ic_bolt),
                      null,
                      tint = if (station.isFast) ChargerGreen else MaterialTheme.colorScheme.onSurfaceVariant,
                  )
                  Column(Modifier.weight(1f)) {
                    Text(
                        station.title ?: stringResource(R.string.charger_default_title),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    station.headline?.let {
                      Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                  }
                  Text(formatTripDistance(distance), style = MaterialTheme.typography.bodyMedium)
                }
              }
            }
      }
    }
  }
}

fun LatLng.distanceTo(station: ChargingStation) = distanceMetres(this, station.position)
