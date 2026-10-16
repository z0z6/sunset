package pl.sunsetcheck

import android.view.MotionEvent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline

@Composable
fun MapPicker(
    lat: Double,
    lon: Double,
    azimuth: Double?,
    recenter: Int,
    onPick: (Double, Double) -> Unit,
    modifier: Modifier = Modifier
) {
    val ctx = LocalContext.current
    val onPickState = rememberUpdatedState(onPick)

    val mapView = remember {
        MapView(ctx).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            minZoomLevel = 4.0
            controller.setZoom(6.5)
            controller.setCenter(GeoPoint(lat, lon))
            overlays.add(MapEventsOverlay(object : MapEventsReceiver {
                override fun singleTapConfirmedHelper(p: GeoPoint): Boolean {
                    onPickState.value(p.latitude, p.longitude)
                    return true
                }
                override fun longPressHelper(p: GeoPoint): Boolean = false
            }))
            setOnTouchListener { v, ev ->
                if (ev.action == MotionEvent.ACTION_DOWN) v.parent?.requestDisallowInterceptTouchEvent(true)
                false
            }
        }
    }

    DisposableEffect(Unit) { onDispose { mapView.onDetach() } }

    LaunchedEffect(recenter) {
        if (recenter > 0) {
            mapView.controller.setZoom(10.0)
            mapView.controller.setCenter(GeoPoint(lat, lon))
        }
    }

    AndroidView(
        factory = { mapView },
        modifier = modifier,
        update = { map ->
            map.overlays.removeAll { it is Marker || it is Polyline }
            if (azimuth != null) {
                val end = Geo.destination(lat, lon, azimuth, 300.0)
                val line = Polyline().apply {
                    setPoints(listOf(GeoPoint(lat, lon), GeoPoint(end.first, end.second)))
                    outlinePaint.color = android.graphics.Color.parseColor("#FFB300")
                    outlinePaint.strokeWidth = 8f
                }
                map.overlays.add(line)
            }
            val marker = Marker(map).apply {
                position = GeoPoint(lat, lon)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                setOnMarkerClickListener { _, _ -> true }
            }
            map.overlays.add(marker)
            map.invalidate()
        }
    )
}
