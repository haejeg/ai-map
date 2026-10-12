package com.opticalcenter.spatialnav.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.opticalcenter.spatialnav.data.tomtom.TomTomDataSource
import com.opticalcenter.spatialnav.navigation.IncidentCategory
import com.opticalcenter.spatialnav.navigation.Route
import com.opticalcenter.spatialnav.spatial.Direction
import com.opticalcenter.spatialnav.spatial.GeoPoint
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.BackgroundLayer
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.RasterLayer
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.android.style.sources.RasterSource
import org.maplibre.android.style.sources.TileSet
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

private const val STYLE_URL = "https://tiles.openfreemap.org/styles/dark"

val DIRECTION_COLORS = mapOf(
    Direction.AHEAD to 0xFF51CF66.toInt(),
    Direction.RIGHT to 0xFF4DABF7.toInt(),
    Direction.BEHIND to 0xFFADB5BD.toInt(),
    Direction.LEFT to 0xFFFF922B.toInt(),
)
private const val ROUTE_COLOR = 0xFF4C8DFF.toInt()
private const val ROUTE_CASING_COLOR = 0xFF0F2E66.toInt()
private const val LABEL_COLOR = 0xFFE6E8EB.toInt()
private const val LABEL_HALO = 0xFF111315.toInt()

/** Traffic delay magnitude (0 unknown … 4 closure) → line colour. */
private val TRAFFIC_COLORS = listOf(0xFFF59F00, 0xFFF59F00, 0xFFF76707, 0xFFE03131, 0xFF862E9C).map { it.toInt() }

/** Imperative bridge between Compose state and the MapLibre map. */
class MapController {
    private var map: MapLibreMap? = null
    private var style: Style? = null
    private var latest: NavUiState? = null
    private var followUser = true
    private var hasCentered = false
    private var insets = 0 to 0
    private var density = 1f

    // Last-rendered inputs, so unchanged sources aren't re-uploaded on every telemetry tick.
    private var renderedRoute: Route? = null
    private var renderedMarkers: Any? = null
    private var renderedIncidents: Any? = null
    private var renderedTraffic: Boolean? = null
    private var renderedTrafficKey: String? = null
    private var wasNavigating = false
    private var lastCamera: Triple<LatLng, Double, Boolean>? = null

    fun attach(map: MapLibreMap, context: Context) {
        this.map = map
        density = context.resources.displayMetrics.density
        applyInsets(map)
        map.uiSettings.isCompassEnabled = true
        map.uiSettings.isAttributionEnabled = true
        map.addOnCameraMoveStartedListener { reason ->
            if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) followUser = false
        }
        map.setStyle(Style.Builder().fromUri(STYLE_URL)) { loaded ->
            applyDarkGrayPalette(loaded)
            loaded.addImage(USER_ICON, userArrowBitmap(context))
            loaded.addSource(GeoJsonSource(ROUTE_SOURCE))
            loaded.addSource(GeoJsonSource(ROUTE_TRAFFIC_SOURCE))
            loaded.addSource(GeoJsonSource(INCIDENT_SOURCE))
            loaded.addSource(GeoJsonSource(MARKER_SOURCE))
            loaded.addSource(GeoJsonSource(USER_SOURCE))
            loaded.addLayer(
                LineLayer("route-casing", ROUTE_SOURCE).withProperties(
                    PropertyFactory.lineColor(ROUTE_CASING_COLOR),
                    PropertyFactory.lineWidth(10f),
                    PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                    PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                ),
            )
            loaded.addLayer(
                LineLayer("route-line", ROUTE_SOURCE).withProperties(
                    PropertyFactory.lineColor(ROUTE_COLOR),
                    PropertyFactory.lineWidth(7f),
                    PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                    PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                ),
            )
            loaded.addLayer(
                LineLayer("route-traffic", ROUTE_TRAFFIC_SOURCE).withProperties(
                    PropertyFactory.lineColor(
                        Expression.match(
                            Expression.get("magnitude"),
                            Expression.color(TRAFFIC_COLORS[0]),
                            *TRAFFIC_COLORS.mapIndexed { i, c -> Expression.stop(i, Expression.color(c)) }.toTypedArray(),
                        ),
                    ),
                    PropertyFactory.lineWidth(7f),
                    PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                    PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                ),
            )
            loaded.addLayer(
                CircleLayer("incident-circles", INCIDENT_SOURCE).withProperties(
                    PropertyFactory.circleColor(
                        Expression.switchCase(
                            Expression.get("condition"), Expression.color(0xFF9775FA.toInt()),
                            Expression.color(0xFFFF6B6B.toInt()),
                        ),
                    ),
                    PropertyFactory.circleRadius(9f),
                    PropertyFactory.circleStrokeColor(LABEL_HALO),
                    PropertyFactory.circleStrokeWidth(2.5f),
                ),
            )
            loaded.addLayer(
                SymbolLayer("incident-labels", INCIDENT_SOURCE).withProperties(
                    PropertyFactory.textField(Expression.get("label")),
                    PropertyFactory.textFont(arrayOf("Noto Sans Regular")),
                    PropertyFactory.textSize(11f),
                    PropertyFactory.textAnchor(Property.TEXT_ANCHOR_TOP),
                    PropertyFactory.textOffset(arrayOf(0f, 1.1f)),
                    PropertyFactory.textColor(0xFFFF8A80.toInt()),
                    PropertyFactory.textHaloColor(LABEL_HALO),
                    PropertyFactory.textHaloWidth(1.5f),
                ),
            )
            loaded.addLayer(
                CircleLayer("marker-circles", MARKER_SOURCE).withProperties(
                    PropertyFactory.circleColor(
                        Expression.match(
                            Expression.get("direction"),
                            Expression.color(0xFF868E96.toInt()),
                            *DIRECTION_COLORS.map { (d, c) -> Expression.stop(d.name, Expression.color(c)) }.toTypedArray(),
                        ),
                    ),
                    PropertyFactory.circleRadius(
                        Expression.switchCase(Expression.get("highlighted"), Expression.literal(10f), Expression.literal(7f)),
                    ),
                    PropertyFactory.circleStrokeColor(LABEL_HALO),
                    PropertyFactory.circleStrokeWidth(2f),
                ),
            )
            loaded.addLayer(
                SymbolLayer("marker-labels", MARKER_SOURCE).withProperties(
                    PropertyFactory.textField(Expression.get("name")),
                    PropertyFactory.textFont(arrayOf("Noto Sans Regular")),
                    PropertyFactory.textSize(12f),
                    PropertyFactory.textAnchor(Property.TEXT_ANCHOR_TOP),
                    PropertyFactory.textOffset(arrayOf(0f, 1f)),
                    PropertyFactory.textColor(LABEL_COLOR),
                    PropertyFactory.textHaloColor(LABEL_HALO),
                    PropertyFactory.textHaloWidth(1.5f),
                ),
            )
            loaded.addLayer(
                SymbolLayer("user-arrow", USER_SOURCE).withProperties(
                    PropertyFactory.iconImage(USER_ICON),
                    PropertyFactory.iconRotate(Expression.get("heading")),
                    PropertyFactory.iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP),
                    PropertyFactory.iconPitchAlignment(Property.ICON_PITCH_ALIGNMENT_MAP),
                    PropertyFactory.iconAllowOverlap(true),
                    PropertyFactory.iconIgnorePlacement(true),
                ),
            )
            style = loaded
            latest?.let(::render)
        }
    }

    fun render(state: NavUiState) {
        latest = state
        val map = map ?: return
        val pose = state.telemetry.pose
        val here = LatLng(pose.lat, pose.lon)
        val navigating = state.trip != null && !state.trip.arrived
        // Follow the snapped position while navigating so the arrow sits on the road.
        val focus = state.trip?.progress?.snapped?.let { LatLng(it.lat, it.lon) } ?: here

        if (navigating != wasNavigating) {
            followUser = true
            lastCamera = null
            wasNavigating = navigating
        }
        if (!hasCentered) {
            map.cameraPosition = CameraPosition.Builder().target(here).zoom(16.0).build()
            hasCentered = true
        } else if (followUser && cameraNeedsMove(focus, if (navigating) pose.heading else 0.0, navigating)) {
            val camera = if (navigating) {
                CameraPosition.Builder().target(focus).bearing(pose.heading).tilt(50.0).zoom(16.5).build()
            } else {
                CameraPosition.Builder().target(here).bearing(0.0).tilt(0.0).zoom(map.cameraPosition.zoom).build()
            }
            map.animateCamera(CameraUpdateFactory.newCameraPosition(camera), 500)
        }

        val style = style ?: return

        val userFeature = Feature.fromGeometry(Point.fromLngLat(focus.longitude, focus.latitude)).apply {
            addNumberProperty("heading", pose.heading)
        }
        style.getSourceAs<GeoJsonSource>(USER_SOURCE)?.setGeoJson(userFeature)

        if (renderedTrafficKey != state.trafficTileKey) {
            renderedTrafficKey = state.trafficTileKey
            setTrafficSource(style, state.trafficTileKey)
            renderedTraffic = null // re-apply visibility to the new layer
        }
        if (renderedTraffic != state.showTraffic) {
            renderedTraffic = state.showTraffic
            style.getLayer(TRAFFIC_LAYER)?.setProperties(
                PropertyFactory.visibility(if (state.showTraffic) Property.VISIBLE else Property.NONE),
            )
        }

        val route = state.trip?.route
        if (route !== renderedRoute) {
            renderedRoute = route
            renderRoute(style, route)
            if (route != null) followUser = true
        }

        val markerKey = state.markers to state.highlighted
        if (markerKey != renderedMarkers) {
            renderedMarkers = markerKey
            val features = state.markers.map { r ->
                Feature.fromGeometry(Point.fromLngLat(r.place.location.lon, r.place.location.lat)).apply {
                    addStringProperty("name", r.place.name)
                    addStringProperty("direction", r.direction.name)
                    addBooleanProperty("highlighted", r.place.id in state.highlighted)
                }
            }
            style.getSourceAs<GeoJsonSource>(MARKER_SOURCE)?.setGeoJson(FeatureCollection.fromFeatures(features))
        }

        val incidents = state.trip?.incidentsAhead.orEmpty()
        if (incidents != renderedIncidents) {
            renderedIncidents = incidents
            val features = incidents.mapNotNull { item ->
                val p = item.incident.geometry.firstOrNull() ?: return@mapNotNull null
                Feature.fromGeometry(Point.fromLngLat(p.lon, p.lat)).apply {
                    addStringProperty("label", shortLabel(item.incident.category))
                    addBooleanProperty("condition", item.incident.category.isRoadCondition)
                }
            }
            style.getSourceAs<GeoJsonSource>(INCIDENT_SOURCE)?.setGeoJson(FeatureCollection.fromFeatures(features))
        }
    }

    /**
     * Recolour the OpenFreeMap "dark" base style (near-black, roads barely visible) into the app's
     * dark-gray-and-blue look: gray land, readable gray roads that brighten with importance, and
     * deep-blue water. Unknown layers are left alone, so style updates degrade gracefully.
     */
    private fun applyDarkGrayPalette(style: Style) {
        for (layer in style.layers) {
            val id = layer.id
            when (layer) {
                is BackgroundLayer -> layer.setProperties(PropertyFactory.backgroundColor(0xFF1B1E22.toInt()))
                is FillLayer -> when {
                    id == "water" -> layer.setProperties(PropertyFactory.fillColor(0xFF0E2A4A.toInt()))
                    id.startsWith("building") -> layer.setProperties(PropertyFactory.fillColor(0xFF25292F.toInt()))
                    id == "landuse_park" || id == "landcover_wood" ->
                        layer.setProperties(PropertyFactory.fillColor(0xFF1E2622.toInt()))
                    id == "landuse_residential" -> layer.setProperties(PropertyFactory.fillColor(0xFF1F2226.toInt()))
                }
                is LineLayer -> when {
                    id == "waterway" -> layer.setProperties(PropertyFactory.lineColor(0xFF15385F.toInt()))
                    id.contains("casing") -> layer.setProperties(PropertyFactory.lineColor(0xFF15181B.toInt()))
                    id.startsWith("highway_motorway") -> layer.setProperties(PropertyFactory.lineColor(0xFF66707D.toInt()))
                    id.startsWith("highway_major") -> layer.setProperties(PropertyFactory.lineColor(0xFF555D68.toInt()))
                    id == "highway_minor" -> layer.setProperties(PropertyFactory.lineColor(0xFF3E444C.toInt()))
                    id == "highway_path" -> layer.setProperties(PropertyFactory.lineColor(0xFF34393F.toInt()))
                    id.startsWith("railway") -> layer.setProperties(PropertyFactory.lineColor(0xFF3A3F46.toInt()))
                }
                is SymbolLayer -> layer.setProperties(
                    PropertyFactory.textColor(0xFF9AA3AD.toInt()),
                    PropertyFactory.textHaloColor(0xFF111315.toInt()),
                )
            }
        }
    }

    /** (Re)create the TomTom traffic-flow raster layer for [key], beneath the route; remove it when null. */
    private fun setTrafficSource(style: Style, key: String?) {
        style.getLayer(TRAFFIC_LAYER)?.let { style.removeLayer(it) }
        style.getSource(TRAFFIC_SOURCE)?.let { style.removeSource(it) }
        if (key.isNullOrBlank()) return
        style.addSource(RasterSource(TRAFFIC_SOURCE, TileSet("2.2.0", TomTomDataSource.trafficFlowTileUrl(key)), 256))
        style.addLayerBelow(
            RasterLayer(TRAFFIC_LAYER, TRAFFIC_SOURCE).withProperties(PropertyFactory.rasterOpacity(0.75f)),
            "route-casing",
        )
    }

    /** Skip camera moves for sub-metre / sub-3° changes so sensor noise doesn't restart animations. */
    private fun cameraNeedsMove(target: LatLng, bearing: Double, navigating: Boolean): Boolean {
        val last = lastCamera
        val moved = last == null || last.third != navigating ||
            last.first.distanceTo(target) > 1.0 ||
            Math.abs(((bearing - last.second + 540) % 360) - 180) > 3.0
        if (moved) lastCamera = Triple(target, bearing, navigating)
        return moved
    }

    private fun renderRoute(style: Style, route: Route?) {
        val routeFeatures = route?.let { listOf(Feature.fromGeometry(lineOf(it.points))) }.orEmpty()
        style.getSourceAs<GeoJsonSource>(ROUTE_SOURCE)?.setGeoJson(FeatureCollection.fromFeatures(routeFeatures))

        val trafficFeatures = route?.trafficSections.orEmpty().mapNotNull { section ->
            val pts = route!!.points.subList(
                section.startIndex.coerceIn(0, route.points.size - 1),
                (section.endIndex + 1).coerceIn(0, route.points.size),
            )
            if (pts.size < 2) return@mapNotNull null
            Feature.fromGeometry(lineOf(pts)).apply { addNumberProperty("magnitude", section.magnitude) }
        }
        style.getSourceAs<GeoJsonSource>(ROUTE_TRAFFIC_SOURCE)?.setGeoJson(FeatureCollection.fromFeatures(trafficFeatures))
    }

    private fun lineOf(points: List<GeoPoint>) = LineString.fromLngLats(points.map { Point.fromLngLat(it.lon, it.lat) })

    private fun shortLabel(category: IncidentCategory) = when (category) {
        IncidentCategory.ACCIDENT -> "Accident"
        IncidentCategory.JAM -> "Jam"
        IncidentCategory.ROAD_CLOSED -> "Closed"
        IncidentCategory.LANE_CLOSED -> "Lane closed"
        IncidentCategory.ROAD_WORKS -> "Road works"
        IncidentCategory.BROKEN_DOWN_VEHICLE -> "Breakdown"
        IncidentCategory.FOG -> "Fog"
        IncidentCategory.ICE -> "Ice"
        IncidentCategory.RAIN -> "Rain"
        IncidentCategory.WIND -> "Wind"
        IncidentCategory.FLOODING -> "Flooding"
        IncidentCategory.DANGEROUS_CONDITIONS -> "Hazard"
        IncidentCategory.UNKNOWN -> "Incident"
    }

    /** Keep the camera's focal point between the top HUD and the bottom panel. */
    fun setInsets(topPx: Int, bottomPx: Int) {
        insets = topPx to bottomPx
        map?.let(::applyInsets)
    }

    private fun applyInsets(map: MapLibreMap) {
        val (top, bottom) = insets
        map.moveCamera(CameraUpdateFactory.paddingTo(0.0, top.toDouble(), 0.0, bottom.toDouble()))
        // Keep the MapLibre logo and OpenStreetMap attribution visible just above the bottom bar.
        fun dp(v: Int) = (v * density).toInt()
        map.uiSettings.setLogoMargins(dp(12), 0, 0, bottom + dp(4))
        map.uiSettings.setAttributionMargins(dp(12 + LOGO_WIDTH_DP), 0, 0, bottom + dp(4))
    }

    fun recenter() {
        followUser = true
        lastCamera = null
        latest?.let(::render)
    }

    private companion object {
        const val USER_SOURCE = "user"
        const val MARKER_SOURCE = "markers"
        const val ROUTE_SOURCE = "route"
        const val ROUTE_TRAFFIC_SOURCE = "route-traffic"
        const val INCIDENT_SOURCE = "incidents"
        const val TRAFFIC_SOURCE = "traffic-flow"
        const val TRAFFIC_LAYER = "traffic-flow-layer"
        const val USER_ICON = "user-arrow"
        /** Approximate width of the MapLibre logo, so the attribution "i" sits beside it. */
        const val LOGO_WIDTH_DP = 96
    }
}

/** Blue location dot with a cone pointing "up" (north before rotation). */
private fun userArrowBitmap(context: Context): Bitmap {
    val density = context.resources.displayMetrics.density
    val size = (56 * density).toInt()
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val c = size / 2f
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    paint.color = 0x664C8DFF
    canvas.drawPath(
        Path().apply {
            moveTo(c, 0f)
            lineTo(c + size * 0.28f, c)
            lineTo(c - size * 0.28f, c)
            close()
        },
        paint,
    )
    paint.color = 0xFFFFFFFF.toInt()
    canvas.drawCircle(c, c, 11 * density, paint)
    paint.color = 0xFF4C8DFF.toInt()
    canvas.drawCircle(c, c, 8 * density, paint)
    return bitmap
}

@Composable
fun NavMap(state: NavUiState, controller: MapController, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val mapView = remember {
        MapView(context).apply {
            onCreate(null)
            getMapAsync { map -> controller.attach(map, context) }
        }
    }

    DisposableEffect(lifecycle, mapView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            mapView.onDestroy()
        }
    }

    AndroidView(factory = { mapView }, modifier = modifier, update = { controller.render(state) })
}
