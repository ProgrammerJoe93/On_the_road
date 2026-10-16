package com.joegineered.ontheroad.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.joegineered.ontheroad.AppGraph
import com.joegineered.ontheroad.R
import com.joegineered.ontheroad.cameras.LatLng
import com.joegineered.ontheroad.offline.Bounds
import com.joegineered.ontheroad.offline.OfflineMaps
import com.stadiamaps.ferrostar.maplibreui.runtime.NavigationCameraMode
import com.stadiamaps.ferrostar.maplibreui.runtime.NavigationMapState
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filterNotNull
import org.maplibre.compose.camera.CameraProjection

/** A small "Offline" chip, shown only while there's no internet connection. */
@Composable
fun OfflineIndicator(modifier: Modifier = Modifier) {
  val status by AppGraph.network.status.collectAsState()
  if (status.online) return
  Surface(
      modifier = modifier,
      shape = RoundedCornerShape(50),
      color = MaterialTheme.colorScheme.inverseSurface,
      contentColor = MaterialTheme.colorScheme.inverseOnSurface,
      shadowElevation = 2.dp,
  ) {
    Row(
        Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
      Icon(painterResource(R.drawable.ic_cloud_off), contentDescription = null, modifier = Modifier.size(18.dp))
      Text(stringResource(R.string.offline), style = MaterialTheme.typography.labelLarge)
    }
  }
}

/**
 * Frames an area of the map to download for offline use. The driver moves and zooms the map
 * under the frame; [onClose] is called with true once a download has started.
 */
@OptIn(FlowPreview::class)
@Composable
fun OfflineAreaPicker(modifier: Modifier, mapState: NavigationMapState, onClose: (Boolean) -> Unit) {
  BackHandler { onClose(false) }
  val density = LocalDensity.current
  val landscape = isLandscape()
  val network by AppGraph.network.status.collectAsState()
  // The frame in root coordinates, which match the full-screen map's.
  var frame by remember { mutableStateOf<Rect?>(null) }
  var bounds by remember { mutableStateOf<Bounds?>(null) }
  var cardHeight by remember { mutableStateOf(0.dp) }
  var name by remember { mutableStateOf("") }
  var nameEdited by remember { mutableStateOf(false) }
  val defaultName = stringResource(R.string.offline_area_default_name)
  val tooLarge = remember(bounds) { bounds?.let(OfflineMaps::isTooLarge) == true }

  LaunchedEffect(mapState) {
    // Stop following the driver's position while picking.
    mapState.cameraMode = NavigationCameraMode.FREE
    snapshotFlow {
          mapState.cameraState.position // re-run when the camera moves
          val projection = mapState.cameraState.projection
          val rect = frame
          if (projection == null || rect == null) null else frameBounds(projection, rect, density)
        }
        .filterNotNull()
        .debounce(200)
        .collectLatest { framed ->
          bounds = framed
          if (nameEdited) return@collectLatest
          // Suggest the name of the place in the middle of the frame.
          delay(600)
          try {
            AppGraph.geocoder
                .locality((framed.south + framed.north) / 2, (framed.west + framed.east) / 2)
                ?.let { if (!nameEdited) name = it }
          } catch (e: CancellationException) {
            throw e
          } catch (_: Exception) {
            // Naming is a convenience; the driver can type a name.
          }
        }
  }

  Box(modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
    val framePadding =
        if (landscape) PaddingValues(start = LandscapePanelWidth + 32.dp, top = 24.dp, end = 88.dp, bottom = 24.dp)
        else PaddingValues(start = 24.dp, top = 24.dp, end = 24.dp, bottom = cardHeight + 32.dp)
    Box(
        Modifier.fillMaxSize()
            .padding(framePadding)
            .border(3.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(12.dp))
            .onGloballyPositioned { frame = it.boundsInRoot() }
    )

    val cardModifier =
        if (landscape) Modifier.align(Alignment.TopStart).padding(16.dp).width(LandscapePanelWidth)
        else Modifier.align(Alignment.BottomCenter).padding(16.dp).fillMaxWidth()
    Card(cardModifier.onGloballyPositioned { cardHeight = with(density) { it.size.height.toDp() } }) {
      Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.offline_pick_title), style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = name,
            onValueChange = {
              name = it
              nameEdited = true
            },
            label = { Text(stringResource(R.string.offline_area_name)) },
            placeholder = { Text(defaultName) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        val (message, isError) =
            when {
              !network.online -> R.string.offline_pick_needs_connection to true
              tooLarge -> R.string.offline_pick_too_large to true
              else -> R.string.offline_pick_hint to false
            }
        Text(
            stringResource(message),
            style = MaterialTheme.typography.bodySmall,
            color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          Button(
              onClick = {
                val area = bounds ?: return@Button
                AppGraph.offlineMaps.downloadArea(name.trim().ifEmpty { defaultName }, area)
                onClose(true)
              },
              enabled = bounds != null && !tooLarge && network.online,
          ) {
            Text(stringResource(R.string.download))
          }
          OutlinedButton(onClick = { onClose(false) }) { Text(stringResource(R.string.cancel)) }
        }
      }
    }
  }
}

/** The map area under [frame], whose corners may be rotated or tilted relative to north. */
private fun frameBounds(projection: CameraProjection, frame: Rect, density: Density): Bounds {
  val corners =
      listOf(frame.topLeft, frame.topRight, frame.bottomLeft, frame.bottomRight).map { corner ->
        val position =
            with(density) { projection.positionFromScreenLocation(DpOffset(corner.x.toDp(), corner.y.toDp())) }
        LatLng(position.latitude, position.longitude)
      }
  return Bounds.around(corners)
}
