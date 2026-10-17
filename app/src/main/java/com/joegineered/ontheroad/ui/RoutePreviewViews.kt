package com.joegineered.ontheroad.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.TextButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.joegineered.ontheroad.AppGraph
import com.joegineered.ontheroad.R
import com.joegineered.ontheroad.nav.Destination
import com.joegineered.ontheroad.nav.RouteAvoidance
import com.joegineered.ontheroad.nav.RouteOption
import com.joegineered.ontheroad.nav.RoutePreview
import com.joegineered.ontheroad.nav.RoutingStatus
import com.joegineered.ontheroad.search.SavedPlaceKind
import com.joegineered.ontheroad.search.SavedPlaces
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.min
import kotlin.math.tan
import kotlinx.coroutines.launch

/**
 * The chosen destination with its stops on the way and the routes to it: tap a route to choose it
 * (or tap its line on the map), then Start. While routes load, a spinner; if planning fails, the
 * reason.
 */
@Composable
fun DestinationCard(
    destination: Destination,
    stops: List<Destination>,
    preview: RoutePreview?,
    routingStatus: RoutingStatus,
    hasLocation: Boolean,
    onSelectRoute: (Int) -> Unit,
    onAddStop: () -> Unit,
    onRemoveStop: (Destination) -> Unit,
    onStart: () -> Unit,
    onCancel: () -> Unit,
) {
  Card(Modifier.fillMaxWidth()) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
          Text(
              destination.label ?: stringResource(R.string.selected_destination),
              style = MaterialTheme.typography.titleMedium,
          )
          Text(
              destination.subtitle ?: destination.coordinateText(),
              style = MaterialTheme.typography.bodySmall,
          )
        }
        SavePlaceButton(destination)
      }
      if (stops.isNotEmpty()) {
        Text(
            stringResource(R.string.trip_stops),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        stops.forEachIndexed { index, stop ->
          TripStopRow(index + 1, stop, onRemove = { onRemoveStop(stop) })
        }
      }
      TextButton(onClick = onAddStop, contentPadding = ButtonDefaults.TextButtonWithIconContentPadding) {
        Icon(painterResource(R.drawable.ic_add), contentDescription = null, Modifier.size(18.dp))
        Text(stringResource(R.string.add_stop), Modifier.padding(start = 8.dp))
      }
      preview?.takeIf { it.destination == destination && it.stops == stops }?.let { routes ->
        routes.options.forEachIndexed { index, option ->
          RouteOptionRow(option, selected = index == routes.selectedIndex, onClick = { onSelectRoute(index) })
        }
      }
      if (routingStatus == RoutingStatus.Loading && preview == null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          CircularProgressIndicator(Modifier.padding(end = 8.dp).size(20.dp), strokeWidth = 2.dp)
          Text(stringResource(R.string.finding_routes), style = MaterialTheme.typography.bodyMedium)
        }
      }
      AvoidChips()
      when (routingStatus) {
        is RoutingStatus.Failed ->
            Text(
                stringResource(R.string.route_error, routingStatus.message),
                color = MaterialTheme.colorScheme.error,
            )
        RoutingStatus.NoRoute ->
            Text(stringResource(R.string.no_route), color = MaterialTheme.colorScheme.error)
        RoutingStatus.Offline ->
            Text(stringResource(R.string.route_offline), color = MaterialTheme.colorScheme.error)
        else -> Unit
      }
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = onStart, enabled = routingStatus != RoutingStatus.Loading && hasLocation) {
          Text(stringResource(R.string.start_navigation))
        }
        OutlinedButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) }
      }
    }
  }
}

/** A stop on the way, numbered in driving order, with a button to remove it. */
@Composable
fun TripStopRow(number: Int, stop: Destination, onRemove: () -> Unit, removeLabel: String = stringResource(R.string.remove_stop)) {
  Row(verticalAlignment = Alignment.CenterVertically) {
    Surface(shape = CircleShape, color = StopPinColor, modifier = Modifier.size(24.dp)) {
      Box(contentAlignment = Alignment.Center) {
        Text("$number", style = MaterialTheme.typography.labelMedium, color = Color.White)
      }
    }
    Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
      Text(
          stop.label ?: stop.coordinateText(),
          style = MaterialTheme.typography.bodyLarge,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
      )
      stop.subtitle?.let {
        Text(
            it,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
      }
    }
    IconButton(onClick = onRemove) { Icon(painterResource(R.drawable.ic_close), removeLabel) }
  }
}

/** One route: its time, then distance, main road and traffic events on it. */
@Composable
private fun RouteOptionRow(option: RouteOption, selected: Boolean, onClick: () -> Unit) {
  val context = LocalContext.current
  val colors = MaterialTheme.colorScheme
  Surface(
      shape = RoundedCornerShape(12.dp),
      color = if (selected) colors.secondaryContainer else colors.surface,
      border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) colors.primary else colors.outlineVariant),
      modifier =
          Modifier.fillMaxWidth().selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
  ) {
    Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
      Text(
          formatDuration(context, option.durationSeconds.toLong()),
          style = MaterialTheme.typography.titleMedium,
          fontWeight = FontWeight.Bold,
      )
      Text(
          listOfNotNull(
                  formatTripDistance(option.route.distance),
                  option.via?.let { stringResource(R.string.route_via, it) },
              )
              .joinToString(" · "),
          style = MaterialTheme.typography.bodyMedium,
      )
      if (option.trafficEvents > 0) {
        Text(
            pluralStringResource(R.plurals.route_traffic_events, option.trafficEvents, option.trafficEvents),
            style = MaterialTheme.typography.bodySmall,
            color = colors.error,
        )
      }
    }
  }
}

/**
 * A star that saves the destination as home, work or a favourite, or removes it; filled when the
 * place is saved.
 */
@Composable
private fun SavePlaceButton(destination: Destination) {
  val savedPlaces = AppGraph.savedPlaces
  val scope = rememberCoroutineScope()
  val saved by savedPlaces.places.collectAsState(initial = emptyList())
  val place = destination.toPlace()
  val entry = SavedPlaces.find(saved, place)
  var open by remember { mutableStateOf(false) }

  fun act(change: suspend () -> Unit) {
    open = false
    scope.launch { change() }
  }

  Box {
    IconButton(onClick = { open = true }) {
      Icon(
          painterResource(if (entry != null) R.drawable.ic_star else R.drawable.ic_star_border),
          contentDescription = stringResource(R.string.save_place),
          tint = if (entry != null) MaterialTheme.colorScheme.primary else LocalContentColor.current,
      )
    }
    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
      listOf(
              Triple(SavedPlaceKind.HOME, R.string.save_as_home, R.drawable.ic_home),
              Triple(SavedPlaceKind.WORK, R.string.save_as_work, R.drawable.ic_work),
              Triple(SavedPlaceKind.FAVOURITE, R.string.save_as_favourite, R.drawable.ic_star),
          )
          .filter { (kind) -> kind != entry?.kind }
          .forEach { (kind, label, icon) ->
            DropdownMenuItem(
                text = { Text(stringResource(label)) },
                leadingIcon = { Icon(painterResource(icon), contentDescription = null) },
                // Keep the saved name: a saved home is shown as "Home", not by its address.
                onClick = { act { savedPlaces.save(kind, entry?.place ?: place) } },
            )
          }
      if (entry != null) {
        DropdownMenuItem(
            text = { Text(stringResource(R.string.remove_saved_place)) },
            leadingIcon = { Icon(painterResource(R.drawable.ic_delete), contentDescription = null) },
            onClick = { act { savedPlaces.remove(place) } },
        )
      }
    }
  }
}

/** Quick toggles for the roads to avoid; changing one plans the routes again. */
@Composable
private fun AvoidChips() {
  val settings = AppGraph.settings
  val scope = rememberCoroutineScope()
  val avoid by settings.routeAvoidance.collectAsState(initial = RouteAvoidance())
  val chips =
      listOf(
          Triple(R.string.avoid_tolls, avoid.tolls) { on: Boolean -> avoid.copy(tolls = on) },
          Triple(R.string.avoid_motorways, avoid.motorways) { on: Boolean -> avoid.copy(motorways = on) },
          Triple(R.string.avoid_ferries, avoid.ferries) { on: Boolean -> avoid.copy(ferries = on) },
      )
  Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    chips.forEach { (label, selected, update) ->
      FilterChip(
          selected = selected,
          onClick = { scope.launch { settings.setRouteAvoidance(update(!selected)) } },
          label = { Text(stringResource(label)) },
      )
    }
  }
}

/** Where to point the map so a bounding box fills a viewport. */
data class CameraFit(val lat: Double, val lng: Double, val zoom: Double)

/**
 * The centre and zoom that fit the box [south]–[north] × [west]–[east] in a viewport of
 * [widthDp] × [heightDp], in Web Mercator. MapLibre's world is 512 dp wide at zoom 0.
 */
fun fitBounds(
    south: Double,
    west: Double,
    north: Double,
    east: Double,
    widthDp: Double,
    heightDp: Double,
    maxZoom: Double = 16.0,
): CameraFit {
  fun mercatorY(lat: Double) = ln(tan(PI / 4 + Math.toRadians(lat) / 2)) / (2 * PI)
  fun latitude(y: Double) = Math.toDegrees(2 * atan(exp(y * 2 * PI)) - PI / 2)
  val xSpan = (east - west) / 360.0
  val ySpan = mercatorY(north) - mercatorY(south)
  val zoomX = if (xSpan > 0) log2(widthDp / (512 * xSpan)) else maxZoom
  val zoomY = if (ySpan > 0) log2(heightDp / (512 * ySpan)) else maxZoom
  return CameraFit(
      lat = latitude((mercatorY(north) + mercatorY(south)) / 2),
      lng = (west + east) / 2,
      zoom = min(zoomX, zoomY).coerceIn(0.0, maxZoom),
  )
}
