package com.joegineered.ontheroad.ui

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.joegineered.ontheroad.R
import com.joegineered.ontheroad.nav.Destination
import com.joegineered.ontheroad.stops.StopAhead
import com.joegineered.ontheroad.stops.StopCategory
import com.joegineered.ontheroad.stops.StopSearch
import kotlinx.serialization.json.buildJsonObject
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.layers.CircleLayer
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.rememberGeoJsonSource
import org.maplibre.compose.util.MaplibreComposable
import org.maplibre.spatialk.geojson.Feature
import org.maplibre.spatialk.geojson.FeatureCollection
import org.maplibre.spatialk.geojson.Point
import uniffi.ferrostar.GeographicCoordinate

/**
 * The trip's stops, which can be skipped, and places to stop at ahead on the route: pick a kind
 * (fuel, chargers, food, rest areas), then a place to add it to the trip.
 */
@Composable
fun StopsAlongRouteCard(
    search: StopSearch,
    tripStops: List<Destination>,
    onSkipStop: (Destination) -> Unit,
    onSearch: (StopCategory) -> Unit,
    onSelect: (StopAhead) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    maxListHeight: Dp = 240.dp,
) {
  val selected =
      when (search) {
        StopSearch.Idle -> null
        is StopSearch.Loading -> search.category
        is StopSearch.Results -> search.category
        is StopSearch.Failed -> search.category
      }
  Card(modifier.fillMaxWidth()) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            stringResource(R.string.stops_on_the_way),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onClose) {
          Icon(painterResource(R.drawable.ic_close), stringResource(R.string.close))
        }
      }
      // The stops already on the trip come first, so one can be skipped.
      tripStops.forEachIndexed { index, stop ->
        TripStopRow(
            index + 1,
            stop,
            onRemove = { onSkipStop(stop) },
            removeLabel = stringResource(R.string.skip_stop),
        )
      }
      Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        StopCategory.entries.forEach { category ->
          FilterChip(
              selected = category == selected,
              onClick = { onSearch(category) },
              label = { Text(stringResource(category.labelRes())) },
              leadingIcon = { Icon(painterResource(category.iconRes()), null, Modifier.size(18.dp)) },
          )
        }
      }
      when (search) {
        StopSearch.Idle -> Message(stringResource(R.string.stops_pick_category))
        is StopSearch.Loading ->
            Row(verticalAlignment = Alignment.CenterVertically) {
              CircularProgressIndicator(Modifier.padding(end = 8.dp).size(20.dp), strokeWidth = 2.dp)
              Text(stringResource(R.string.stops_searching), style = MaterialTheme.typography.bodyMedium)
            }
        is StopSearch.Failed ->
            Message(
                stringResource(if (search.offline) R.string.stops_offline else R.string.stops_failed),
                isError = true,
            )
        is StopSearch.Results ->
            if (search.stops.isEmpty()) Message(stringResource(R.string.stops_none))
            else {
              LazyColumn(Modifier.heightIn(max = maxListHeight)) {
                items(search.stops, key = { it.stop.id }) { stop -> StopRow(stop, onClick = { onSelect(stop) }) }
              }
            }
      }
    }
  }
}

@Composable
private fun StopRow(stop: StopAhead, onClick: () -> Unit) {
  val context = LocalContext.current
  Row(
      Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(16.dp),
  ) {
    Icon(painterResource(stop.stop.category.iconRes()), null, tint = stop.stop.category.color())
    Column {
      Text(
          stopTitle(context, stop),
          style = MaterialTheme.typography.bodyLarge,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
      )
      Text(
          stopSummary(context, stop),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
      )
    }
  }
}

@Composable
private fun Message(text: String, isError: Boolean = false) {
  Text(
      text,
      style = MaterialTheme.typography.bodyMedium,
      color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
  )
}

/** Stops found ahead, as dots in each kind's colour. */
@Composable
@MaplibreComposable
fun StopsLayer(search: StopSearch) {
  val stops = (search as? StopSearch.Results)?.stops.orEmpty()
  if (stops.isEmpty()) return
  val source =
      rememberGeoJsonSource(
          GeoJsonData.Features(
              FeatureCollection(
                  stops.map {
                    Feature(
                        geometry = Point(longitude = it.stop.position.lng, latitude = it.stop.position.lat),
                        properties = buildJsonObject {},
                    )
                  }
              )
          )
      )
  CircleLayer(
      id = "otr-stops",
      source = source,
      color = const(stops.first().stop.category.color()),
      radius = const(7.dp),
      strokeColor = const(Color.White),
      strokeWidth = const(2.dp),
  )
}

fun StopCategory.labelRes(): Int =
    when (this) {
      StopCategory.FUEL -> R.string.stop_fuel
      StopCategory.CHARGING -> R.string.stop_charging
      StopCategory.FOOD -> R.string.stop_food
      StopCategory.REST -> R.string.stop_rest
    }

fun StopCategory.iconRes(): Int =
    when (this) {
      StopCategory.FUEL -> R.drawable.ic_fuel
      StopCategory.CHARGING -> R.drawable.ic_bolt
      StopCategory.FOOD -> R.drawable.ic_restaurant
      StopCategory.REST -> R.drawable.ic_rest_area
    }

private fun StopCategory.color(): Color =
    when (this) {
      StopCategory.FUEL -> Color(0xFFD84315)
      StopCategory.CHARGING -> Color(0xFF2E7D32)
      StopCategory.FOOD -> Color(0xFF6A1B9A)
      StopCategory.REST -> Color(0xFF1565C0)
    }

/** The stop's name, or what it is when OSM has no name. */
fun stopTitle(context: Context, stop: StopAhead): String =
    stop.stop.name
        ?: context.getString(
            when (stop.stop.category) {
              StopCategory.FUEL -> R.string.stop_fuel_default
              StopCategory.CHARGING -> R.string.charger_default_title
              StopCategory.FOOD -> R.string.stop_food_default
              StopCategory.REST -> R.string.stop_rest_default
            }
        )

/** "12.4 km ahead · 300 m off route · CCS2 150 kW"; places beside the road leave out the offset. */
fun stopSummary(context: Context, stop: StopAhead): String =
    listOfNotNull(
            context.getString(R.string.stop_ahead, formatTripDistance(stop.aheadMetres)),
            stop.offRouteMetres
                .takeIf { it >= ON_ROUTE_METRES }
                ?.let { context.getString(R.string.stop_off_route, formatTripDistance(it)) },
            stop.stop.charger?.headline,
        )
        .joinToString(" · ")

fun StopAhead.toDestination(context: Context): Destination =
    Destination(
        GeographicCoordinate(stop.position.lat, stop.position.lng),
        label = stopTitle(context, this),
        subtitle = stop.charger?.headline,
    )

/** Closer than this, a place counts as beside the road. */
private const val ON_ROUTE_METRES = 75.0
