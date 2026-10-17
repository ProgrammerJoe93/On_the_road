package com.joegineered.ontheroad.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.joegineered.ontheroad.R
import com.joegineered.ontheroad.cameras.MatchedCamera
import com.joegineered.ontheroad.chargers.ChargerController
import com.joegineered.ontheroad.chargers.ChargingStation
import com.joegineered.ontheroad.nav.RouteOption
import com.joegineered.ontheroad.nav.RoutePreview
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.maplibre.compose.util.ClickResult
import org.maplibre.compose.util.FeaturesClickHandler
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.dsl.image
import org.maplibre.compose.expressions.value.IconPitchAlignment
import org.maplibre.compose.expressions.value.LineCap
import org.maplibre.compose.expressions.value.LineJoin
import org.maplibre.compose.layers.CircleLayer
import org.maplibre.compose.layers.FillLayer
import org.maplibre.compose.layers.LineLayer
import org.maplibre.compose.offline.OfflinePack
import org.maplibre.compose.offline.rememberOfflinePacksSource
import org.maplibre.compose.layers.SymbolLayer
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.GeoJsonSource
import org.maplibre.compose.sources.rememberGeoJsonSource
import org.maplibre.compose.util.MaplibreComposable
import org.maplibre.spatialk.geojson.Feature
import org.maplibre.spatialk.geojson.FeatureCollection
import org.maplibre.spatialk.geojson.LineString
import org.maplibre.spatialk.geojson.Position
import org.maplibre.spatialk.geojson.Point
import uniffi.ferrostar.GeographicCoordinate

/** Map layers shared by the phone and car map views. */
@Composable
@MaplibreComposable
fun SpeedCameraLayer(cameras: List<MatchedCamera>) {
  if (cameras.isEmpty()) return
  val features =
      FeatureCollection(
          cameras.map {
            Feature(
                geometry =
                    Point(longitude = it.camera.position.lng, latitude = it.camera.position.lat),
                properties = buildJsonObject {},
            )
          }
      )
  val source = rememberGeoJsonSource(GeoJsonData.Features(features))
  val icon = image(painterResource(R.drawable.map_speed_camera))
  SymbolLayer(
      id = "otr-speed-cameras",
      source = source,
      iconImage = icon,
      // Upright and the same size at any distance, so cameras far ahead on a tilted map stay
      // visible; always drawn, even where it overlaps labels.
      iconPitchAlignment = const(IconPitchAlignment.Viewport),
      iconAllowOverlap = const(true),
      iconIgnorePlacement = const(true),
  )
}

@Composable
@MaplibreComposable
fun DestinationPinLayer(destination: GeographicCoordinate?) {
  destination ?: return
  val source =
      rememberGeoJsonSource(
          GeoJsonData.Features(
              FeatureCollection(
                  Feature(
                      geometry = Point(longitude = destination.lng, latitude = destination.lat),
                      properties = buildJsonObject {},
                  )
              )
          )
      )
  CircleLayer(
      id = "otr-destination-pin",
      source = source,
      color = const(Color(0xFF1E5AA8)),
      radius = const(10.dp),
      strokeColor = const(Color.White),
      strokeWidth = const(3.dp),
  )
}

/**
 * EV chargers: fast (DC) chargers full size, AC chargers smaller. Tapping one calls [onClick]
 * with its id.
 */
@Composable
@MaplibreComposable
fun ChargerLayer(chargers: List<ChargingStation>, onClick: ((String) -> Unit)? = null) {
  if (chargers.isEmpty()) return
  val icon = image(painterResource(R.drawable.map_charger))
  val (fast, ac) = chargers.partition { it.isFast }
  listOf("otr-chargers-ac" to (ac to 0.75f), "otr-chargers-fast" to (fast to 1f)).forEach {
      (id, group) ->
    val (stations, size) = group
    if (stations.isEmpty()) return@forEach
    val source =
        rememberGeoJsonSource(
            GeoJsonData.Features(
                FeatureCollection(
                    stations.map {
                      Feature(
                          geometry = Point(longitude = it.position.lng, latitude = it.position.lat),
                          properties = buildJsonObject { put("id", it.id) },
                      )
                    }
                )
            )
        )
    SymbolLayer(
        id = id,
        source = source,
        minZoom = ChargerController.MIN_ZOOM.toFloat(),
        iconImage = icon,
        iconSize = const(size),
        iconPitchAlignment = const(IconPitchAlignment.Viewport),
        iconAllowOverlap = const(true),
        onClick =
            onClick?.let { handler ->
              { features ->
                features.firstOrNull()?.properties?.get("id")?.jsonPrimitive?.contentOrNull?.let(handler)
                ClickResult.Consume
              }
            },
    )
  }
}

/** Outlines of saved offline areas, shown while picking a new one. */
@Composable
@MaplibreComposable
fun OfflineAreasLayer(packs: Set<OfflinePack>) {
  if (packs.isEmpty()) return
  val source = rememberOfflinePacksSource(packs)
  FillLayer(
      id = "otr-offline-areas-fill",
      source = source,
      color = const(Color(0xFF1E5AA8)),
      opacity = const(0.12f),
  )
  LineLayer(
      id = "otr-offline-areas-line",
      source = source,
      color = const(Color(0xFF1E5AA8)),
      width = const(2.dp),
  )
}

/**
 * Routes offered before starting: the alternatives in grey under the chosen route in blue.
 * Tapping an alternative calls [onSelect] with its index.
 */
@Composable
@MaplibreComposable
fun RoutePreviewLayer(preview: RoutePreview?, onSelect: ((Int) -> Unit)? = null) {
  preview ?: return
  val (chosen, alternatives) = preview.options.withIndex().partition { it.index == preview.selectedIndex }
  fun features(options: List<IndexedValue<RouteOption>>) =
      FeatureCollection(
          options.map { (index, option) ->
            Feature(
                geometry = LineString(option.route.geometry.map { Position(longitude = it.lng, latitude = it.lat) }),
                properties = buildJsonObject { put("index", index) },
            )
          }
      )

  if (alternatives.isNotEmpty()) {
    val source = rememberGeoJsonSource(GeoJsonData.Features(features(alternatives)))
    RouteLine("otr-route-alternatives", source, Color(0xFF8E99AB), onClick = onSelect)
  }
  val source = rememberGeoJsonSource(GeoJsonData.Features(features(chosen)))
  RouteLine("otr-route-chosen", source, Color(0xFF1E5AA8), onClick = null)
}

/** A route line with a white casing. */
@Composable
@MaplibreComposable
private fun RouteLine(
    id: String,
    source: GeoJsonSource,
    color: Color,
    onClick: ((Int) -> Unit)?,
) {
  val handler: FeaturesClickHandler? =
      onClick?.let { select ->
        { features ->
          features.firstOrNull()?.properties?.get("index")?.jsonPrimitive?.intOrNull?.let(select)
          ClickResult.Consume
        }
      }
  LineLayer(
      id = "$id-casing",
      source = source,
      color = const(Color.White),
      width = const(9.dp),
      cap = const(LineCap.Round),
      join = const(LineJoin.Round),
      onClick = handler,
  )
  LineLayer(
      id = id,
      source = source,
      color = const(color),
      width = const(6.dp),
      cap = const(LineCap.Round),
      join = const(LineJoin.Round),
      onClick = handler,
  )
}
