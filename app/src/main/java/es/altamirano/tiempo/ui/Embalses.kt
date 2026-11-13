@file:OptIn(ExperimentalMaterial3Api::class)

package es.altamirano.tiempo.ui

import android.annotation.SuppressLint
import android.graphics.Paint
import android.view.MotionEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import es.altamirano.tiempo.BuildConfig
import es.altamirano.tiempo.app
import es.altamirano.tiempo.core.Embalses
import es.altamirano.tiempo.core.I18n
import es.altamirano.tiempo.core.Places
import es.altamirano.tiempo.core.Reservoir
import es.altamirano.tiempo.core.Week
import es.altamirano.tiempo.core.tr
import kotlinx.coroutines.launch
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.Projection
import org.osmdroid.views.overlay.CopyrightOverlay
import org.osmdroid.views.overlay.Overlay
import java.io.File
import java.time.format.DateTimeFormatter
import kotlin.math.sqrt

private fun hm3(v: Double) = String.format(I18n.locale(), "%,.0f", v)
private fun pct(v: Double) = String.format(I18n.locale(), "%.1f", v)
private fun signed(v: Double) = (if (v > 0) "+" else "") + hm3(v)
private val dayMonth get() = DateTimeFormatter.ofPattern("d MMM yyyy", I18n.locale())

// --- La pestaña ----------------------------------------------------------------------------

@Composable
fun EmbalsesTab() {
    val app = LocalContext.current.app
    val state by app.embalses.state.collectAsStateWithLifecycle()
    val favorites by app.embalses.favorites.collectAsStateWithLifecycle()
    val current by app.locations.current.collectAsStateWithLifecycle()
    val saved by app.locations.saved.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var openId by rememberSaveable { mutableStateOf<Int?>(null) }
    var map by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(Unit) { app.embalses.load() }

    val byId = remember(state.list) { state.list.associateBy { it.id } }
    openId?.let { id -> byId[id]?.let { r -> BackHandler { openId = null }; ReservoirDetail(r, id in favorites, { app.embalses.toggleFavorite(id) }) { openId = null }; return } }
    if (map) { BackHandler { map = false }; ReservoirMap(state.list, { map = false }) { openId = it }; return }

    // Los más cercanos a la ubicación del GPS (o a la primera ciudad que sigues).
    val here = current ?: saved.firstOrNull()
    val nearest = remember(state.list, here) {
        here?.let { h -> state.list.map { it to Places.distanceKm(h.lat, h.lon, it.lat, it.lon) }.sortedBy { it.second }.take(5) } ?: emptyList()
    }
    val found = remember(state.list, query) {
        val q = query.trim().lowercase(); if (q.length < 2) emptyList() else state.list.filter { fold(it.name).contains(fold(q)) }.take(20)
    }

    LazyColumn(Modifier.fillMaxSize().statusBarsPadding(), contentPadding = PaddingValues(horizontal = gutter(), vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Embalses", Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                IconButton(onClick = { scope.launch { app.embalses.load(force = true) } }) { Icon(Icons.Filled.Refresh, tr("Actualizar"), tint = MaterialTheme.colorScheme.primary) }
                IconButton(onClick = { map = true }, enabled = state.list.isNotEmpty()) { Icon(Icons.Filled.Map, tr("Mapa"), tint = MaterialTheme.colorScheme.primary) }
            }
        }
        state.error?.let { e -> item { Text(if (state.list.isEmpty()) "No se pudo cargar: $e" else "Sin conexión: datos guardados", fontSize = 13.sp, color = Color(0xFFFF9500)) } }
        if (state.list.isEmpty()) { if (state.error == null) item { Text("Cargando…", color = MaterialTheme.colorScheme.onSurfaceVariant) }; return@LazyColumn }

        item { SpainCard(state.list) }
        item {
            OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), singleLine = true, shape = RoundedCornerShape(12.dp),
                placeholder = { Text("Buscar embalse") }, leadingIcon = { Icon(Icons.Filled.Search, null) })
        }
        if (query.trim().length >= 2) {
            item {
                Card {
                    Column {
                        if (found.isEmpty()) Text("Ninguno se llama así", Modifier.padding(16.dp), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        found.forEachIndexed { i, r -> if (i > 0) Divider(); ReservoirRow(r, null, r.id in favorites) { openId = r.id } }
                    }
                }
            }
        }
        item {
            Card {
                Column {
                    CardTitle("Favoritos")
                    val favs = favorites.mapNotNull { byId[it] }
                    if (favs.isEmpty()) Text("Toca la estrella de un embalse para tenerlo aquí.", Modifier.padding(16.dp, 0.dp).padding(bottom = 14.dp),
                        fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    favs.forEachIndexed { i, r -> if (i > 0) Divider(); ReservoirRow(r, here?.let { Places.distanceKm(it.lat, it.lon, r.lat, r.lon) }, true) { openId = r.id } }
                }
            }
        }
        if (nearest.isNotEmpty()) item {
            Card {
                Column {
                    CardTitle("Más cercanos", here?.name)
                    nearest.forEachIndexed { i, (r, d) -> if (i > 0) Divider(); ReservoirRow(r, d, r.id in favorites) { openId = r.id } }
                }
            }
        }
        item { BasinsCard(state.list) }
        item {
            Text("Boletín hidrológico semanal de MITECO (embalses peninsulares de más de 5 hm³). Sale los martes.",
                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Para buscar sin tildes: «alcantara» encuentra «Alcántara». */
private fun fold(s: String) = java.text.Normalizer.normalize(s.lowercase(), java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}"), "")

@Composable private fun Divider() = HorizontalDivider(Modifier.padding(start = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)

/** La reserva de toda España, en grande, como la tarjeta principal del tiempo. */
@Composable
private fun SpainCard(list: List<Reservoir>) {
    val t = Embalses.total(list)
    val date = list.maxOf { it.date }
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(HeroGradient).padding(18.dp)) {
        Column {
            Text("RESERVA HÍDRICA · ESPAÑA", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White.copy(alpha = 0.85f), letterSpacing = 1.sp)
            Row(verticalAlignment = Alignment.Bottom) {
                Text(pct(t.percent) + " %", fontSize = 48.sp, fontWeight = FontWeight.Light, color = Color.White)
                Spacer(Modifier.width(12.dp))
                Text("${hm3(t.current)} de ${hm3(t.capacity)} hm³",
                    Modifier.padding(bottom = 10.dp), fontSize = 14.sp, color = Color.White)
            }
            FillBar(t.percent, Color.White, Color.White.copy(alpha = 0.25f))
            Spacer(Modifier.height(8.dp))
            Text("${signed(t.weekChange)} hm³ en la última semana", fontSize = 13.sp, color = Color.White)
            Text("Boletín del ${date.format(dayMonth)}", fontSize = 12.sp, color = Color.White.copy(alpha = 0.8f))
        }
    }
}

@Composable
private fun FillBar(percent: Double, fill: Color, track: Color, height: Int = 8) {
    Box(Modifier.fillMaxWidth().height(height.dp).clip(RoundedCornerShape(height.dp)).background(track)) {
        Box(Modifier.fillMaxWidth((percent / 100).toFloat().coerceIn(0f, 1f)).fillMaxHeight().clip(RoundedCornerShape(height.dp)).background(fill))
    }
}

@Composable
private fun ReservoirRow(r: Reservoir, distanceKm: Double?, favorite: Boolean, onClick: () -> Unit) {
    val color = Color(Embalses.color(r.percent))
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(16.dp, 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(color))
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(r.name, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 1)
                if (favorite) Icon(Icons.Filled.Star, null, Modifier.padding(start = 4.dp).size(14.dp), tint = Sun)
            }
            Text(listOfNotNull(r.basin, distanceKm?.let { "a ${km(it)} km" }, "${hm3(r.current)}/${hm3(r.capacity)} hm³").joinToString(" · "),
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            Spacer(Modifier.height(5.dp))
            FillBar(r.percent, color, MaterialTheme.colorScheme.surfaceVariant, 5)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(pct(r.percent) + " %", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            if (r.weekChange != 0.0) Text(signed(r.weekChange), fontSize = 11.sp, color = if (r.weekChange > 0) Color(0xFF1F78D1) else Color(0xFFD92E2E))
        }
    }
}

/** Cada cuenca con su %, de la más vacía a la más llena. */
@Composable
private fun BasinsCard(list: List<Reservoir>) {
    val basins = remember(list) { list.groupBy { it.basin }.map { (b, rs) -> b to Embalses.total(rs) }.sortedBy { it.second.percent } }
    Card {
        Column(Modifier.padding(bottom = 10.dp)) {
            CardTitle("Por cuencas")
            basins.forEach { (b, t) ->
                Row(Modifier.fillMaxWidth().padding(16.dp, 5.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(b, Modifier.width(130.dp), fontSize = 13.sp, maxLines = 1)
                    Box(Modifier.weight(1f)) { FillBar(t.percent, Color(Embalses.color(t.percent)), MaterialTheme.colorScheme.surfaceVariant, 6) }
                    Text(pct(t.percent) + " %", Modifier.width(64.dp), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, textAlign = androidx.compose.ui.text.style.TextAlign.End)
                }
            }
        }
    }
}

// --- La ficha de un embalse ----------------------------------------------------------------

@Composable
private fun ReservoirDetail(r: Reservoir, favorite: Boolean, onFavorite: () -> Unit, onBack: () -> Unit) {
    val app = LocalContext.current.app
    var history by remember(r.id) { mutableStateOf<List<Week>?>(null) }
    var error by remember(r.id) { mutableStateOf<String?>(null) }
    LaunchedEffect(r.id) { try { history = app.embalses.history(r.id) } catch (e: Exception) { error = e.message ?: "Sin conexión"; history = emptyList() } }
    val color = Color(Embalses.color(r.percent))

    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Text(r.name, fontWeight = FontWeight.Bold, maxLines = 1) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, tr("Volver")) } },
            actions = { IconButton(onClick = onFavorite) { Icon(if (favorite) Icons.Filled.Star else Icons.Filled.StarBorder, tr("Favorito"), tint = if (favorite) Sun else MaterialTheme.colorScheme.primary) } },
            expandedHeight = 48.dp, colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background))
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = gutter(), vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item {
                Card {
                    Column(Modifier.padding(16.dp)) {
                        Text("${r.basin}${if (r.hydro) " · hidroeléctrico" else ""}", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text(pct(r.percent) + " %", fontSize = 44.sp, fontWeight = FontWeight.Light, color = color)
                            Spacer(Modifier.width(12.dp))
                            Text("${hm3(r.current)} de ${hm3(r.capacity)} hm³", Modifier.padding(bottom = 9.dp), fontSize = 14.sp)
                        }
                        FillBar(r.percent, color, MaterialTheme.colorScheme.surfaceVariant)
                        Spacer(Modifier.height(8.dp))
                        Text("${signed(r.weekChange)} hm³ en la última semana", fontSize = 13.sp)
                        Text("Boletín del ${r.date.format(dayMonth)}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            item { Comparisons(r, history) }
            item { YearCard(r, history, error) }
            history?.takeIf { it.size > 1 }?.let { h -> item { LastWeeks(h, r.capacity) } }
        }
    }
}

/** La misma semana del año pasado y la media de diez años, como embalses.net. */
@Composable
private fun Comparisons(r: Reservoir, history: List<Week>?) {
    Card {
        Column(Modifier.padding(bottom = 8.dp)) {
            CardTitle("Comparado con")
            if (history == null) { Text("Cargando…", Modifier.padding(16.dp, 0.dp).padding(bottom = 8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant); return@Column }
            val last = Embalses.sameWeek(history, r.date)?.hm3
            val mean = Embalses.tenYearMean(history, r.date)
            CompareRow("Misma semana de ${r.date.year - 1}", last, r)
            CompareRow("Media de 10 años", mean, r)
        }
    }
}

@Composable
private fun CompareRow(label: String, value: Double?, r: Reservoir) {
    Row(Modifier.fillMaxWidth().padding(16.dp, 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, fontSize = 14.sp)
            if (value != null) Text("${hm3(value)} hm³ · ${pct(100 * value / r.capacity)} %", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            else Text("Sin datos", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (value != null) {
            val diff = r.current - value
            Text("${signed(diff)} hm³", fontWeight = FontWeight.Bold, fontSize = 15.sp, color = if (diff >= 0) Color(0xFF1F78D1) else Color(0xFFD92E2E))
        }
    }
}

private val ThisYear = Color(0xFF1F78D1); private val LastYear = Color(0xFF9A9AA0)

/** El año hidrológico (de octubre a septiembre): este, el anterior y la media de diez. */
@Composable
private fun YearCard(r: Reservoir, history: List<Week>?, error: String?) {
    Card {
        Column(Modifier.padding(bottom = 12.dp)) {
            val start = Embalses.hydroYearStart(r.date)
            CardTitle("Año hidrológico", "${start.year}–${(start.year + 1) % 100}")
            when {
                history == null -> Text("Cargando…", Modifier.padding(16.dp, 0.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                history.isEmpty() -> Text(error?.let { "No se pudo cargar: $it" } ?: "Sin datos", Modifier.padding(16.dp, 0.dp), fontSize = 13.sp, color = Color(0xFFFF9500))
                else -> {
                    YearChart(Embalses.yearCurves(history, r.date), r.capacity)
                    Row(Modifier.padding(16.dp, 8.dp, 16.dp, 0.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        Legend(ThisYear, "Este año"); Legend(LastYear, "Año pasado"); Legend(Green, "Media 10 años", dashed = true)
                    }
                }
            }
        }
    }
}

@Composable
private fun Legend(color: Color, label: String, dashed: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.size(18.dp, 8.dp)) {
            drawLine(color, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 2.5.dp.toPx(),
                pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(6f, 4f)) else null)
        }
        Text(" $label", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun YearChart(c: es.altamirano.tiempo.core.YearCurves, capacity: Double) {
    val grid = MaterialTheme.colorScheme.outlineVariant
    val label = MaterialTheme.colorScheme.onSurfaceVariant.toArgb()
    val density = LocalDensity.current
    val months = remember { (0..11).map { c.start.plusMonths(it.toLong()).format(DateTimeFormatter.ofPattern("MMMMM", I18n.locale())).uppercase(I18n.locale()) } }
    Canvas(Modifier.fillMaxWidth().height(190.dp).padding(start = 40.dp, end = 16.dp, top = 6.dp, bottom = 18.dp)) {
        val w = size.width; val h = size.height
        fun x(day: Int) = w * day / 365f
        fun y(v: Double) = h * (1 - (v / capacity).toFloat().coerceIn(0f, 1.02f))
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = label; textSize = with(density) { 10.sp.toPx() } }
        listOf(0, 25, 50, 75, 100).forEach { p ->
            val yy = h * (1 - p / 100f)
            drawLine(grid, Offset(0f, yy), Offset(w, yy), 1f)
            text.textAlign = Paint.Align.RIGHT
            drawContext.canvas.nativeCanvas.drawText("$p %", -6.dp.toPx(), yy + 4.dp.toPx(), text)
        }
        text.textAlign = Paint.Align.CENTER
        months.forEachIndexed { i, m ->
            val day = java.time.temporal.ChronoUnit.DAYS.between(c.start, c.start.plusMonths(i.toLong())).toInt()
            drawContext.canvas.nativeCanvas.drawText(m, x(day + 15), h + 14.dp.toPx(), text)
        }
        fun curve(points: List<Pair<Int, Double>>, color: Color, width: Float, dashed: Boolean = false) {
            if (points.size < 2) return
            val path = Path().apply { points.forEachIndexed { i, (d, v) -> if (i == 0) moveTo(x(d), y(v)) else lineTo(x(d), y(v)) } }
            drawPath(path, color, style = Stroke(width, pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(10f, 7f)) else null))
        }
        curve(c.mean, Green, 2.dp.toPx(), dashed = true)
        curve(c.lastYear, LastYear, 2.dp.toPx())
        curve(c.thisYear, ThisYear, 3.dp.toPx())
        c.thisYear.lastOrNull()?.let { (d, v) -> drawCircle(ThisYear, 4.dp.toPx(), Offset(x(d), y(v))) }
    }
}

/** Las últimas semanas, con lo que subió o bajó cada una. */
@Composable
private fun LastWeeks(h: List<Week>, capacity: Double) {
    Card {
        Column(Modifier.padding(bottom = 8.dp)) {
            CardTitle("Últimas semanas")
            val last = h.takeLast(9)
            last.zipWithNext().reversed().forEach { (prev, w) ->
                val diff = w.hm3 - prev.hm3
                Row(Modifier.fillMaxWidth().padding(16.dp, 4.dp)) {
                    Text(w.date.format(dayMonth), Modifier.weight(1f), fontSize = 13.sp)
                    Text("${hm3(w.hm3)} hm³", Modifier.width(90.dp), fontSize = 13.sp, textAlign = androidx.compose.ui.text.style.TextAlign.End)
                    Text(pct(100 * w.hm3 / capacity) + " %", Modifier.width(64.dp), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, textAlign = androidx.compose.ui.text.style.TextAlign.End)
                    Text(signed(diff), Modifier.width(56.dp), fontSize = 12.sp, textAlign = androidx.compose.ui.text.style.TextAlign.End,
                        color = when { diff > 0 -> Color(0xFF1F78D1); diff < 0 -> Color(0xFFD92E2E); else -> MaterialTheme.colorScheme.onSurfaceVariant })
                }
            }
        }
    }
}

// --- El mapa -------------------------------------------------------------------------------

/**
 * Los embalses sobre OpenStreetMap (osmdroid, sin Google Play): un círculo por embalse, más
 * grande cuanto más cabe y del color de su % lleno. Tocar uno lo enseña abajo; tocar la tarjeta
 * abre su ficha. Las teselas, con nuestro User-Agent y la atribución de OSM, como pide su política.
 */
@Composable
private fun ReservoirMap(list: List<Reservoir>, onBack: () -> Unit, onOpen: (Int) -> Unit) {
    val context = LocalContext.current
    var selected by rememberSaveable { mutableStateOf<Int?>(null) }
    val sel = list.firstOrNull { it.id == selected }
    val mapView = remember {
        Configuration.getInstance().apply {
            userAgentValue = BuildConfig.APPLICATION_ID + "/" + BuildConfig.VERSION_NAME
            osmdroidBasePath = File(context.cacheDir, "osmdroid")
            osmdroidTileCache = File(context.cacheDir, "osmdroid/tiles")
        }
        MapView(context).apply {
            setTileSourceFactoryForOsm()
            setMultiTouchControls(true)
            zoomController.setVisibility(org.osmdroid.views.CustomZoomButtonsController.Visibility.NEVER)
            minZoomLevel = 5.0; maxZoomLevel = 14.0
            controller.setZoom(6.0)
            controller.setCenter(GeoPoint(40.0, -3.7))
            overlays += CopyrightOverlay(context)
        }
    }
    val overlay = remember(list) { ReservoirOverlay(list, context.resources.displayMetrics.density) { selected = it } }
    DisposableEffect(overlay) {
        mapView.overlays.add(overlay); mapView.invalidate()
        onDispose { mapView.overlays.remove(overlay) }
    }
    DisposableEffect(Unit) { mapView.onResume(); onDispose { mapView.onPause(); mapView.onDetach() } }
    overlay.selected = selected

    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Text("Mapa de embalses", fontWeight = FontWeight.Bold) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, tr("Volver")) } },
            expandedHeight = 48.dp, modifier = Modifier.statusBarsPadding(), windowInsets = androidx.compose.foundation.layout.WindowInsets(0),
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background))
        Box(Modifier.fillMaxSize()) {
            AndroidView({ mapView }, Modifier.fillMaxSize(), update = { it.invalidate() })
            // La leyenda, arriba.
            Row(Modifier.align(Alignment.TopCenter).padding(8.dp).clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.92f)).padding(10.dp, 6.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Embalses.LEGEND.forEach { (label, p) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(10.dp).clip(CircleShape).background(Color(Embalses.color(p))))
                        Text(" $label", fontSize = 11.sp)
                    }
                }
            }
            sel?.let { r ->
                Box(Modifier.align(Alignment.BottomCenter).padding(12.dp).padding(bottom = 16.dp)) {
                    Card { Column { ReservoirRow(r, null, false) { onOpen(r.id) }; TextButton(onClick = { onOpen(r.id) }, Modifier.padding(start = 6.dp)) { Text("Ver ficha") } } }
                }
            }
        }
    }
}

private fun MapView.setTileSourceFactoryForOsm() {
    setTileSource(TileSourceFactory.MAPNIK)
    isTilesScaledToDpi = true
}

/** Los círculos, pintados a mano: 375 marcadores de osmdroid irían a tirones. */
private class ReservoirOverlay(private val list: List<Reservoir>, private val density: Float, private val onTap: (Int?) -> Unit) : Overlay() {
    var selected: Int? = null
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = android.graphics.Color.WHITE }
    private val point = android.graphics.Point()

    /** Más grande cuanto más cabe (por área): de 4 dp los pequeños a 20 dp Alcántara o La Serena. */
    private fun radius(r: Reservoir, zoom: Double) = density * (3.5f + sqrt(r.capacity).toFloat() / 3.8f).coerceAtMost(20f) * (if (zoom >= 8) 1.4f else 1f)

    override fun draw(c: android.graphics.Canvas, mapView: MapView, shadow: Boolean) {
        if (shadow) return
        val proj: Projection = mapView.projection
        val zoom = mapView.zoomLevelDouble
        ring.strokeWidth = 1.2f * density
        // Los grandes primero, para que los pequeños queden encima y se puedan tocar.
        list.sortedByDescending { it.capacity }.forEach { r ->
            proj.toPixels(GeoPoint(r.lat, r.lon), point)
            val rad = radius(r, zoom)
            fill.color = Embalses.color(r.percent); fill.alpha = 225
            c.drawCircle(point.x.toFloat(), point.y.toFloat(), rad, fill)
            ring.color = if (r.id == selected) android.graphics.Color.BLACK else android.graphics.Color.WHITE
            ring.strokeWidth = (if (r.id == selected) 3f else 1.2f) * density
            c.drawCircle(point.x.toFloat(), point.y.toFloat(), rad, ring)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onSingleTapConfirmed(e: MotionEvent, mapView: MapView): Boolean {
        val proj = mapView.projection
        val zoom = mapView.zoomLevelDouble
        val hit = list.mapNotNull { r ->
            proj.toPixels(GeoPoint(r.lat, r.lon), point)
            val dx = point.x - e.x; val dy = point.y - e.y
            val d2 = dx * dx + dy * dy
            val rad = maxOf(radius(r, zoom), 14 * density)
            if (d2 <= rad * rad) r to d2 else null
        }.minByOrNull { it.second }?.first
        onTap(hit?.id)
        mapView.invalidate()
        return true
    }
}
