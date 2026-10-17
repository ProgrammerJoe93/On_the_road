package com.joegineered.ontheroad.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import com.joegineered.ontheroad.traffic.SlowTraffic
import com.joegineered.ontheroad.traffic.TrafficEvent
import com.joegineered.ontheroad.traffic.TrafficEventKind
import com.joegineered.ontheroad.traffic.TrafficWarning
import com.joegineered.ontheroad.traffic.labelRes
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.math.roundToInt
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.dsl.image
import org.maplibre.compose.expressions.value.IconPitchAlignment
import org.maplibre.compose.expressions.value.LineCap
import org.maplibre.compose.layers.CircleLayer
import org.maplibre.compose.layers.LineLayer
import org.maplibre.compose.layers.SymbolLayer
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.rememberGeoJsonSource
import org.maplibre.compose.util.ClickResult
import org.maplibre.compose.util.FeaturesClickHandler
import org.maplibre.compose.util.MaplibreComposable
import org.maplibre.spatialk.geojson.Feature
import org.maplibre.spatialk.geojson.FeatureCollection
import org.maplibre.spatialk.geojson.MultiLineString
import org.maplibre.spatialk.geojson.Point
import org.maplibre.spatialk.geojson.Position

val TrafficEventKind.color: Color
  get() =
      when (this) {
        TrafficEventKind.ACCIDENT, TrafficEventKind.ROAD_CLOSED -> Color(0xFFD32F2F)
        TrafficEventKind.CONGESTION -> Color(0xFFE65100)
        TrafficEventKind.ROADWORKS -> Color(0xFFF57C00)
        TrafficEventKind.POOR_CONDITIONS -> Color(0xFF1E88E5)
        TrafficEventKind.VERY_POOR_CONDITIONS -> Color(0xFF6A1B9A)
        TrafficEventKind.OTHER -> Color(0xFFFBC02D)
      }

private fun slowColor(percent: Double?) =
    if ((percent ?: 100.0) < 50.0) Color(0xFFD32F2F) else Color(0xFFF57C00)

/**
 * Live traffic on the map: affected roads coloured by kind, an icon per event, and measuring
 * stations with slow traffic. [onClick] receives the id of the tapped item.
 */
@Composable
@MaplibreComposable
fun TrafficLayer(
    events: List<TrafficEvent>,
    slowTraffic: List<SlowTraffic>,
    onClick: ((String) -> Unit)? = null,
) {
  val click: FeaturesClickHandler? =
      onClick?.let { handler ->
        { features ->
          features.firstOrNull()?.properties?.get("id")?.jsonPrimitive?.contentOrNull?.let(handler)
          ClickResult.Consume
        }
      }

  // Affected roads, one layer per colour.
  events.filter { it.lines.any { line -> line.size >= 2 } }.groupBy { it.kind }.forEach { (kind, group) ->
    val lines =
        FeatureCollection(
            group.map { event ->
              Feature(
                  geometry =
                      MultiLineString(
                          event.lines.filter { it.size >= 2 }.map { line -> line.map { Position(it.lng, it.lat) } }
                      ),
                  properties = buildJsonObject { put("id", event.id) },
              )
            }
        )
    LineLayer(
        id = "otr-traffic-lines-${kind.name}",
        source = rememberGeoJsonSource(GeoJsonData.Features(lines)),
        color = const(kind.color),
        width = const(5.dp),
        opacity = const(0.8f),
        cap = const(LineCap.Round),
        onClick = click,
    )
  }

  // Icons: closures, incidents, roadworks, road weather.
  val iconFor =
      mapOf(
          R.drawable.map_closure to events.filter { it.kind == TrafficEventKind.ROAD_CLOSED },
          R.drawable.map_incident to
              events.filter { it.kind == TrafficEventKind.ACCIDENT || it.kind == TrafficEventKind.CONGESTION },
          R.drawable.map_roadworks to
              events.filter { it.kind == TrafficEventKind.ROADWORKS || it.kind == TrafficEventKind.OTHER },
          R.drawable.map_road_weather to events.filter { it.isRoadWeather },
      )
  iconFor.forEach { (iconRes, group) ->
    val points = group.mapNotNull { event -> event.anchor?.let { event to it } }
    if (points.isEmpty()) return@forEach
    val features =
        FeatureCollection(
            points.map { (event, at) ->
              Feature(
                  geometry = Point(longitude = at.lng, latitude = at.lat),
                  properties = buildJsonObject { put("id", event.id) },
              )
            }
        )
    SymbolLayer(
        id = "otr-traffic-icons-$iconRes",
        source = rememberGeoJsonSource(GeoJsonData.Features(features)),
        minZoom = 8f,
        iconImage = image(painterResource(iconRes)),
        iconPitchAlignment = const(IconPitchAlignment.Viewport),
        iconAllowOverlap = const(true),
        onClick = click,
    )
  }

  // Slow traffic at measuring stations.
  slowTraffic.groupBy { slowColor(it.worstPercent) }.forEach { (color, group) ->
    val features =
        FeatureCollection(
            group.map {
              Feature(
                  geometry = Point(longitude = it.position.lng, latitude = it.position.lat),
                  properties = buildJsonObject { put("id", "tms-${it.stationId}") },
              )
            }
        )
    CircleLayer(
        id = "otr-slow-traffic-${color.value}",
        source = rememberGeoJsonSource(GeoJsonData.Features(features)),
        minZoom = 8f,
        color = const(color),
        radius = const(7.dp),
        strokeColor = const(Color.White),
        strokeWidth = const(2.dp),
        onClick = click,
    )
  }
}

/** Details for a tapped traffic event or slow-traffic station. */
@Composable
fun TrafficCard(item: Any, onClose: () -> Unit, modifier: Modifier = Modifier) {
  Card(modifier.fillMaxWidth()) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
      val (title, color) =
          when (item) {
            is TrafficEvent -> stringResource(item.kind.labelRes()) to item.kind.color
            else -> stringResource(R.string.slow_traffic) to slowColor((item as SlowTraffic).worstPercent)
          }
      Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(painterResource(R.drawable.ic_traffic), null, tint = color, modifier = Modifier.size(26.dp))
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f).padding(start = 8.dp),
        )
        IconButton(onClick = onClose) { Icon(painterResource(R.drawable.ic_close), stringResource(R.string.close)) }
      }

      when (item) {
        is TrafficEvent -> {
          if (item.restrictions.isNotEmpty()) {
            Text(item.restrictions.joinToString(" · "), style = MaterialTheme.typography.bodyMedium)
          }
          // Digitraffic publishes these in Finnish.
          Text(item.title, style = MaterialTheme.typography.bodyMedium)
          item.description?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
          }
          item.end?.let { end ->
            val formatted =
                DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).withZone(ZoneId.systemDefault()).format(end)
            Text(stringResource(R.string.traffic_until, formatted), style = MaterialTheme.typography.bodySmall)
          }
        }
        is SlowTraffic -> {
          listOf(1 to (item.direction1Percent to item.direction1Kmh), 2 to (item.direction2Percent to item.direction2Kmh))
              .forEach { (direction, values) ->
                val (percent, kmh) = values
                if (percent != null && kmh != null) {
                  Text(stringResource(R.string.slow_traffic_direction, direction, percent.roundToInt(), kmh.roundToInt()))
                }
              }
        }
      }
      Text(
          stringResource(R.string.traffic_attribution),
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}

/** "Roadworks in 1.2 km" while approaching an event on the route. */
@Composable
fun TrafficWarningBadge(warning: TrafficWarning, modifier: Modifier = Modifier) {
  Row(
      modifier =
          modifier
              .background(warning.event.kind.color, RoundedCornerShape(12.dp))
              .padding(horizontal = 12.dp, vertical = 8.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    Icon(painterResource(R.drawable.ic_traffic), null, tint = Color.White, modifier = Modifier.size(22.dp))
    Text(
        stringResource(
            R.string.traffic_ahead_in,
            stringResource(warning.event.kind.labelRes()),
            formatDistance(warning.distanceMetres),
        ),
        color = Color.White,
        style = MaterialTheme.typography.titleMedium,
    )
  }
}
