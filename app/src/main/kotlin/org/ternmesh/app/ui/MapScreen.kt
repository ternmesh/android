// Where the people sharing their position with this node are, on a map and in a list, whom the
// node shares its own with, and where the node itself is: from its own satellite receiver, or from
// a phone that gave it one.
//
// A position is a cell, not a point: one shared at a town's size is drawn as the town-sized square
// it is somewhere in, with a dot at its centre, so the map claims no more than the sender gave.
package org.ternmesh.app.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PointF
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point
import org.maplibre.geojson.Polygon
import org.ternmesh.app.R
import org.ternmesh.app.node.LocationFeed
import org.ternmesh.app.node.NodeRepository
import org.ternmesh.app.node.NodeState
import org.ternmesh.app.node.Notifier
import org.ternmesh.companion.Body
import org.ternmesh.companion.Companion
import org.ternmesh.companion.Conversations
import org.ternmesh.companion.FixSource
import org.ternmesh.companion.Peer
import org.ternmesh.companion.ReceiverState

/** OpenFreeMap's style: free, with no key, over OpenStreetMap's data. */
private const val STYLE = "https://tiles.openfreemap.org/styles/liberty"

/** A cell this fine or finer is drawn as a dot alone: its square would be smaller than the dot. */
private const val DOT_FROM = 20

/** How close the map opens on a single point, such as the node's own fix or the phone: a few streets. */
private const val CLOSE = 14.0

/** One position the node holds, from a contact or from a routing id in a group. */
private data class Mark(
    val key: String,
    val name: String,
    val precision: Int,
    val lat: Double,
    val lon: Double,
    val altitude: Int,
    val accuracy: Int,
    /** How old the fix is now, in seconds. */
    val age: Long,
    /** The node's own fix, drawn apart from the positions shared with it. */
    val own: Boolean = false,
)

/** The node's own fix, if it has one and says so. */
private fun ownMark(state: NodeState, name: String, now: Long): Mark? {
    val f = state.records.fix?.takeIf { hasFix(state) && it.has } ?: return null
    val age = f.age + (now - (state.arrived[f] ?: now)) / 1000
    return Mark("self", name, Companion.PRECISION_MAX, f.lat / 1e7, f.lon / 1e7, f.altitude, f.accuracy, age, own = true)
}

/** What the node's receiver is doing, and where its fix came from: "Fixed · 9 satellites", "Searching for satellites", "From a phone". */
fun receiverText(context: Context, receiver: Int, source: Int, satellites: Int): String = when (receiver) {
    ReceiverState.FIXED -> listOf(
        context.getString(R.string.receiver_fixed),
        context.resources.getQuantityString(R.plurals.satellites, satellites, satellites),
    ).joinToString(" · ")
    ReceiverState.SEARCHING -> context.getString(R.string.receiver_searching)
    ReceiverState.OFF -> context.getString(R.string.receiver_off)
    else -> context.getString(if (source == FixSource.NONE) R.string.receiver_none else R.string.fix_from_phone)
}

private fun marks(state: NodeState, now: Long): List<Mark> {
    val r = state.records
    fun age(body: Body, given: Long) = given + (now - (state.arrived[body] ?: now)) / 1000
    val contacts = r.positions.values.map { p ->
        Mark(
            "c:${p.contact}", Conversations.name(r, Peer.Contact(p.contact)), p.precision, p.lat / 1e7, p.lon / 1e7,
            p.altitude, p.accuracy, age(p, p.age),
        )
    }
    // A group member's routing id is what it claimed: shown as the id, never as a name it might match.
    val members = r.groupPositions.values.map { p ->
        Mark(
            "g:${p.group}:${p.from}", "${Conversations.name(r, Peer.Group(p.group))} · ${"%08x".format(p.from)}",
            p.precision, p.lat / 1e7, p.lon / 1e7, p.altitude, p.accuracy, age(p, p.age),
        )
    }
    return (contacts + members).sortedBy { it.age }
}

@Composable
fun MapScreen(repository: NodeRepository, state: NodeState) {
    val context = LocalContext.current
    if (!hasPositions(state)) {
        Text(stringResource(R.string.map_old_node), Modifier.padding(24.dp))
        return
    }
    val now = rememberNow()
    val marks = remember(state.records, state.arrived, now) { marks(state, now) }
    val ownName = state.node?.shown ?: stringResource(R.string.map_your_node)
    val own = remember(state.records, state.arrived, now, ownName) { ownMark(state, ownName, now) }
    val fix = state.records.fix?.takeIf { hasFix(state) }
    var selected by remember { mutableStateOf<String?>(null) }
    var sharing by remember { mutableStateOf<Peer?>(null) }
    val shared = remember(state.records, state.arrived, now) {
        (state.records.sharing.keys.map { Peer.Contact(it) } + state.records.groupSharing.keys.map { Peer.Group(it) })
            .filter { sharedWith(state, it, now) != null }
    }

    Column(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxWidth().weight(1f)) {
            // Read at each composition: the user may allow it from the sharing dialog while the map is open.
            PositionMap(listOfNotNull(own) + marks, selected, LocationFeed.permitted(context)) { selected = it }
        }
        LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
            fix?.let { f ->
                item { SectionTitle(R.string.map_your_node) }
                item(key = "self") {
                    val where = own?.let { details(context, it) } ?: context.getString(R.string.fix_none)
                    ListItem(
                        headlineContent = { Text(ownName) },
                        supportingContent = { Text("${receiverText(context, f.receiver, f.source, f.satellites)}\n$where") },
                        colors = if (own != null && own.key == selected) {
                            ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                        } else {
                            ListItemDefaults.colors()
                        },
                        modifier = if (own != null) Modifier.clickable { selected = own.key } else Modifier,
                    )
                    HorizontalDivider()
                }
            }
            if (shared.isNotEmpty()) {
                item { SectionTitle(R.string.map_sharing_with) }
                items(shared, key = { "s:${Notifier.key(it)}" }) { peer ->
                    val s = sharedWith(state, peer, now) ?: return@items
                    ListItem(
                        headlineContent = { Text(Conversations.name(state.records, peer)) },
                        supportingContent = { Text("${precisionText(context, s.precision)} · ${leftText(context, state, s, now)}") },
                        modifier = Modifier.clickable { sharing = peer },
                    )
                }
            }
            item { SectionTitle(R.string.map_positions) }
            if (marks.isEmpty()) item { Text(stringResource(R.string.map_empty), Modifier.padding(16.dp)) }
            items(marks, key = { it.key }) { m ->
                ListItem(
                    headlineContent = { Text(m.name) },
                    supportingContent = { Text(details(context, m)) },
                    colors = if (m.key == selected) {
                        ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                    } else {
                        ListItemDefaults.colors()
                    },
                    modifier = Modifier.clickable { selected = m.key },
                )
                HorizontalDivider()
            }
        }
    }
    sharing?.let { peer -> ShareDialog(repository, state, peer) { sharing = null } }
}

@Composable
private fun SectionTitle(text: Int) {
    Text(
        stringResource(text),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

/** How exact, how old, and what else the position gave. The node's own fix is exact, and says so by its accuracy. */
private fun details(context: Context, m: Mark): String = buildList {
    if (!m.own) add(precisionText(context, m.precision))
    add(context.getString(R.string.ago, spanText(m.age)))
    if (m.altitude != Companion.NO_ALTITUDE) add(context.getString(R.string.altitude, m.altitude))
    if (m.accuracy != 0) add(context.getString(R.string.accuracy, m.accuracy))
}.joinToString(" · ")

/**
 * The map itself: MapLibre's view, kept through the screen's lifecycle. [selected] is centred on as
 * it changes, and a tap on a dot selects it. The phone is shown once [permitted].
 */
@Composable
private fun PositionMap(marks: List<Mark>, selected: String?, permitted: Boolean, select: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val accent = MaterialTheme.colorScheme.primary.toArgb()
    val ownColour = MaterialTheme.colorScheme.tertiary.toArgb()
    val view = remember {
        MapLibre.getInstance(context)
        MapView(context).also { it.onCreate(null) }
    }
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var style by remember { mutableStateOf<Style?>(null) }
    var framed by remember { mutableStateOf(false) }
    // Opened on the phone for want of anything else: the first position to show still frames the map.
    var phoneFramed by remember { mutableStateOf(false) }
    val openOnPhone = {
        if (!phoneFramed) {
            LocationFeed.lastKnown(context)?.let {
                phoneFramed = true
                map?.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(it.latitude, it.longitude), CLOSE))
            }
        }
    }

    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> view.onStart()
                Lifecycle.Event.ON_RESUME -> view.onResume()
                Lifecycle.Event.ON_PAUSE -> view.onPause()
                Lifecycle.Event.ON_STOP -> view.onStop()
                else -> {}
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            view.onDestroy()
        }
    }
    DisposableEffect(view) {
        view.getMapAsync { m ->
            map = m
            m.uiSettings.isRotateGesturesEnabled = false
            m.setStyle(Style.Builder().fromUri(STYLE)) { s ->
                addLayers(s, accent, ownColour)
                style = s
            }
        }
        onDispose {}
    }
    val currentMarks by rememberUpdatedState(marks)
    DisposableEffect(map) {
        val m = map ?: return@DisposableEffect onDispose {}
        val listener = MapLibreMap.OnMapClickListener { point ->
            val at: PointF = m.projection.toScreenLocation(point)
            val hit = m.queryRenderedFeatures(at, DOTS).firstOrNull()?.getStringProperty("key")
            if (hit != null && currentMarks.any { it.key == hit }) select(hit)
            hit != null
        }
        m.addOnMapClickListener(listener)
        onDispose { m.removeOnMapClickListener(listener) }
    }
    LaunchedEffect(style, marks) {
        val s = style ?: return@LaunchedEffect
        s.getSourceAs<GeoJsonSource>(DOTS)?.setGeoJson(FeatureCollection.fromFeatures(marks.map(::dot)))
        s.getSourceAs<GeoJsonSource>(CELLS)?.setGeoJson(FeatureCollection.fromFeatures(marks.filter { it.precision < DOT_FROM }.map(::cell)))
        // The first time there is something to show, all of it; with nothing yet, where the phone
        // last was, rather than the whole world.
        if (!framed && marks.isNotEmpty()) {
            framed = true
            map?.moveCamera(frame(marks))
        } else if (!framed) {
            openOnPhone()
        }
    }
    LaunchedEffect(style, permitted) {
        val s = style ?: return@LaunchedEffect
        map?.let { showMe(context, it, s) }
        if (!framed && marks.isEmpty()) openOnPhone()
    }
    LaunchedEffect(map, selected) {
        val m = map ?: return@LaunchedEffect
        val mark = marks.firstOrNull { it.key == selected } ?: return@LaunchedEffect
        m.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(mark.lat, mark.lon), if (mark.own) CLOSE else zoomFor(mark.precision)))
    }

    AndroidView(factory = { view }, modifier = Modifier.fillMaxSize())
}

private const val DOTS = "tern-dots"
private const val CELLS = "tern-cells"

private fun addLayers(s: Style, accent: Int, own: Int) {
    s.addSource(GeoJsonSource(CELLS))
    s.addSource(GeoJsonSource(DOTS))
    s.addLayer(FillLayer("$CELLS-fill", CELLS).withProperties(PropertyFactory.fillColor(accent), PropertyFactory.fillOpacity(0.15f)))
    s.addLayer(LineLayer("$CELLS-line", CELLS).withProperties(PropertyFactory.lineColor(accent), PropertyFactory.lineWidth(1.5f)))
    s.addLayer(
        CircleLayer(DOTS, DOTS).withProperties(
            PropertyFactory.circleColor(
                Expression.switchCase(Expression.get("own"), Expression.color(own), Expression.color(accent)),
            ),
            PropertyFactory.circleRadius(7f),
            PropertyFactory.circleStrokeColor(android.graphics.Color.WHITE),
            PropertyFactory.circleStrokeWidth(2f),
        ),
    )
    s.addLayer(
        SymbolLayer("$DOTS-names", DOTS).withProperties(
            PropertyFactory.textField(Expression.get("name")),
            PropertyFactory.textFont(arrayOf("Noto Sans Regular")),
            PropertyFactory.textSize(13f),
            PropertyFactory.textOffset(arrayOf(0f, 1.4f)),
            PropertyFactory.textAnchor("top"),
            PropertyFactory.textHaloColor(android.graphics.Color.WHITE),
            PropertyFactory.textHaloWidth(1.5f),
        ),
    )
}

/** The phone's own position, as Android draws it, if the user allowed the app it. */
@SuppressLint("MissingPermission") // permitted() is asked first
private fun showMe(context: Context, m: MapLibreMap, s: Style) {
    if (!LocationFeed.permitted(context)) return
    runCatching {
        m.locationComponent.activateLocationComponent(LocationComponentActivationOptions.builder(context, s).build())
        m.locationComponent.isLocationComponentEnabled = true
    }
}

private fun dot(m: Mark): Feature = Feature.fromGeometry(Point.fromLngLat(m.lon, m.lat)).also {
    it.addStringProperty("key", m.key)
    it.addStringProperty("name", m.name)
    it.addBooleanProperty("own", m.own)
}

/** The cell a position is somewhere in: `360 / 2^precision` degrees each way, about its centre. */
private fun cell(m: Mark): Feature {
    val half = 180.0 / (1L shl m.precision)
    val south = (m.lat - half).coerceAtLeast(-90.0)
    val north = (m.lat + half).coerceAtMost(90.0)
    val corners = listOf(
        Point.fromLngLat(m.lon - half, south),
        Point.fromLngLat(m.lon + half, south),
        Point.fromLngLat(m.lon + half, north),
        Point.fromLngLat(m.lon - half, north),
        Point.fromLngLat(m.lon - half, south),
    )
    return Feature.fromGeometry(Polygon.fromLngLats(listOf(corners)))
}

/** Close enough that a cell at [precision] fills a good part of the screen. */
private fun zoomFor(precision: Int): Double = (precision - 1).toDouble().coerceIn(2.0, 16.0)

private fun frame(marks: List<Mark>) = if (marks.distinctBy { it.lat to it.lon }.size == 1) {
    val zoom = if (marks.all { it.own }) CLOSE else zoomFor(marks.filterNot { it.own }.minOf { it.precision })
    CameraUpdateFactory.newLatLngZoom(LatLng(marks[0].lat, marks[0].lon), zoom)
} else {
    val bounds = LatLngBounds.Builder().apply { marks.forEach { include(LatLng(it.lat, it.lon)) } }.build()
    CameraUpdateFactory.newLatLngBounds(bounds, 96)
}
