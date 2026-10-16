package com.joegineered.ontheroad.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.joegineered.ontheroad.R
import com.joegineered.ontheroad.cameras.MatchedCamera
import kotlinx.serialization.json.buildJsonObject
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.dsl.image
import org.maplibre.compose.expressions.value.IconPitchAlignment
import org.maplibre.compose.layers.CircleLayer
import org.maplibre.compose.layers.SymbolLayer
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.rememberGeoJsonSource
import org.maplibre.compose.util.MaplibreComposable
import org.maplibre.spatialk.geojson.Feature
import org.maplibre.spatialk.geojson.FeatureCollection
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
