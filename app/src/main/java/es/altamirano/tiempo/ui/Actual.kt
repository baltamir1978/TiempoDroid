@file:OptIn(ExperimentalMaterial3Api::class)

package es.altamirano.tiempo.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Co2
import androidx.compose.material.icons.filled.Grain
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import es.altamirano.tiempo.app
import es.altamirano.tiempo.core.ChartData
import es.altamirano.tiempo.core.ChartPeriod
import es.altamirano.tiempo.core.ChartSource
import es.altamirano.tiempo.core.NetatmoCharts
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.math.roundToInt

private val Orange = Color(0xFFFF9500); private val Blue = Color(0xFF007AFF); private val Purple = Color(0xFFAF52DE)
private val Indigo = Color(0xFF5856D6); private val Gray = Color(0xFF8E8E93)

private data class Metric(val key: String, val label: String, val unit: String, val icon: ImageVector, val color: Color)

/** Las tarjetas, en el orden de iOS. La lluvia es la última hora, no `Rain` (sólo 5 min). */
private val METRICS = listOf(
    Metric("Temperature", "Temperatura", "°C", Icons.Filled.Thermostat, Orange),
    Metric("Humidity", "Humedad", "%", Icons.Filled.WaterDrop, Blue),
    Metric("Pressure", "Presión", " hPa", Icons.Filled.Speed, Green),
    Metric("CO2", "CO₂", " ppm", Icons.Filled.Co2, Purple),
    Metric("Noise", "Ruido", " dB", Icons.AutoMirrored.Filled.VolumeUp, Gray),
    Metric("sum_rain_1", "Lluvia 1h", " L/m²", Icons.Filled.Grain, Indigo),
)

/** La pestaña Actual: la estación Netatmo, fuera y dentro (`ActualView`). */
@Composable
fun ActualTab() {
    val app = LocalContext.current.app
    val st by app.netatmo.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var chart by rememberSaveable { mutableStateOf<Pair<String, String>?>(null) }   // (fuente, tipo)
    LaunchedEffect(Unit) { app.netatmo.load()?.let { app.snapshots.requestUpdate() } }

    chart?.let { (src, type) ->
        BackHandler { chart = null }
        ChartScreen(ChartSource.valueOf(src), type) { chart = null }
        return
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Text("Estación meteorológica", fontWeight = FontWeight.Bold) },
            actions = {
                if (st.loading) CircularProgressIndicator(Modifier.size(22.dp).padding(end = 4.dp), strokeWidth = 2.dp)
                else IconButton(onClick = { scope.launch { app.netatmo.load(force = true); app.snapshots.requestUpdate() } }) { Icon(Icons.Filled.Refresh, "Actualizar") }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background, actionIconContentColor = MaterialTheme.colorScheme.primary))
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            item { Header(st.station?.stationName ?: app.netatmo.stationLocation, app.netatmo.stationLocation, st.station?.reachable == true) }
            st.error?.let { item { Text(it, Modifier.padding(16.dp), fontSize = 12.sp, color = Color(0xFFFF3B30)) } }
            val s = st.station ?: return@LazyColumn
            val exterior = buildMap<String, Double> {
                st.exterior?.data?.let { putAll(it) }
                st.rain?.data?.let { putAll(it); if ("sum_rain_1" !in it) it["Rain"]?.let { r -> put("sum_rain_1", r) } }
                s.data["Pressure"]?.let { put("Pressure", it) }
            }
            val interior = s.data - "Pressure"
            val batteries = buildMap { st.exterior?.batteryPercent?.let { put("Temperature", it) }; st.rain?.batteryPercent?.let { put("sum_rain_1", it) } }
            val stamp = s.lastStatusStore?.let { Instant.ofEpochSecond(it).atZone(ZoneId.systemDefault())
                .format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).withLocale(es.altamirano.tiempo.core.I18n.locale())) }
            item { SectionHeader("Exterior", stamp) }
            item { Grid(exterior, batteries, ChartSource.EXTERIOR) { src, type -> chart = src.name to type } }
            val note = when {
                app.netatmo.moduleRain.isEmpty() -> "No hay pluviómetro detectado. Pulsa «Detectar de nuevo» en Ajustes."
                st.rain == null -> "El pluviómetro configurado ya no está en la estación."
                st.rain?.reachable == false || st.rain?.data.isNullOrEmpty() -> "El pluviómetro no envía datos (sin cobertura o sin pilas)."
                else -> null
            }
            note?.let { item {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Warning, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("  $it", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } }
            item { SectionHeader("Interior", stamp) }
            item { Grid(interior, emptyMap(), ChartSource.INTERIOR) { src, type -> chart = src.name to type } }
            item {
                Text("Netatmo se consulta como mucho cada 10 minutos: es lo que tarda la estación en medir.",
                    Modifier.padding(16.dp), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun Header(name: String, location: String, reachable: Boolean) {
    Row(Modifier.fillMaxWidth().background(HeroGradient).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            if (name.isNotEmpty()) Text(name, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
            if (location.isNotEmpty() && location != name) Text(location, color = Color.White.copy(alpha = 0.8f), fontSize = 12.sp)
        }
        Row(Modifier.clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.15f)).padding(12.dp, 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(if (reachable) Color(0xFF34C759) else Color(0xFFFF3B30)))
            Text(if (reachable) "  Online" else "  Offline", color = Color.White, fontSize = 12.sp)
        }
    }
}

@Composable
private fun SectionHeader(title: String, stamp: String?) {
    Row(Modifier.padding(16.dp, 18.dp, 16.dp, 8.dp)) {
        Text(title.uppercase(), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant, letterSpacing = 1.5.sp)
        stamp?.let { Text("  · $it", fontSize = 11.sp, color = Color(0xFF94ABBF)) }
    }
}

/** Rejilla de dos columnas; tocar una tarjeta abre su gráfica. */
@Composable
private fun Grid(values: Map<String, Double>, batteries: Map<String, Int>, source: ChartSource, onChart: (ChartSource, String) -> Unit) {
    val shown = METRICS.filter { it.key in values }
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        shown.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { m ->
                    // La presión se mide en la base y la lluvia guarda su historia como `Rain`.
                    val target = when (m.key) { "Pressure" -> ChartSource.INTERIOR to "Pressure"; "sum_rain_1" -> ChartSource.EXTERIOR to "Rain"; else -> source to m.key }
                    MetricCard(m, values[m.key], batteries[m.key], if (m.key == "sum_rain_1") values["sum_rain_24"] else null,
                        Modifier.weight(1f).clickable { onChart(target.first, target.second) })
                }
                if (row.size == 1) Box(Modifier.weight(1f))
            }
        }
    }
}

private fun rainText(v: Double) = String.format(Locale.getDefault(), "%.1f", v)

@Composable
private fun MetricCard(m: Metric, value: Double?, battery: Int?, today: Double?, modifier: Modifier) {
    val isRain = m.key.startsWith("sum_rain") || m.key == "Rain"
    val text = when {
        value == null -> "—"
        isRain -> rainText(value)
        value == Math.rint(value) && m.key != "Temperature" -> value.toInt().toString()
        else -> String.format(Locale.getDefault(), "%.1f", value)
    }
    Box(modifier.clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceContainer)
        .border(1.dp, Green.copy(alpha = 0.18f), RoundedCornerShape(14.dp))) {
        Column(Modifier.fillMaxWidth().padding(10.dp, 11.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            val icon = if (m.key == "Pressure" && value != null) null else m.icon
            Box(Modifier.size(42.dp).clip(CircleShape).background(m.color.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
                if (icon != null) Icon(icon, null, tint = m.color) else Text(pressureEmoji(value!!), fontSize = 20.sp)
            }
            Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(top = 5.dp)) {
                Text(text, fontSize = 25.sp, fontWeight = FontWeight.Black, color = m.color)
                Text(m.unit, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 4.dp, start = 2.dp))
            }
            Text(m.label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        today?.let { Text("hoy ${rainText(it)}", Modifier.align(Alignment.TopStart).padding(8.dp, 7.dp), fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        battery?.let { BatteryBars(it, Modifier.align(Alignment.TopEnd).padding(8.dp)) }
    }
}

/** El tiempo que anuncia la presión, como el icono de iOS. */
private fun pressureEmoji(p: Double) = when { p >= 1023 -> "☀️"; p >= 1010 -> "🌤️"; p >= 1000 -> "☁️"; p >= 990 -> "🌦️"; else -> "⛈️" }

/** Cuatro barras, una por cuarto de carga: se lee sin distinguir colores. */
@Composable
private fun BatteryBars(pct: Int, modifier: Modifier) {
    val bars = when { pct >= 76 -> 4; pct >= 51 -> 3; pct >= 26 -> 2; else -> 1 }
    val color = when (bars) { 4, 3 -> Green; 2 -> Sun; else -> Color(0xFFFF3B30) }
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(1.5.dp)) {
        repeat(4) { i -> Box(Modifier.size(2.5.dp, 9.dp).clip(RoundedCornerShape(0.5.dp)).background(if (i < bars) color else MaterialTheme.colorScheme.surfaceVariant)) }
    }
}

// --- Gráfica de una medida (`MetricChartView`) ---------------------------------------------

@Composable
private fun ChartScreen(source: ChartSource, type: String, onBack: () -> Unit) {
    val app = LocalContext.current.app
    var period by rememberSaveable { mutableStateOf(ChartPeriod.WEEK) }
    var result by remember { mutableStateOf<Pair<ChartData?, String?>?>(null) }
    LaunchedEffect(period) { result = null; result = app.netatmo.chart(source, type, period, ZoneId.systemDefault()) }
    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Text(NetatmoCharts.title(source, type), fontWeight = FontWeight.Bold) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Volver") } },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background))
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                Text("PERIODO", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant, letterSpacing = 1.5.sp)
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ChartPeriod.entries.forEach { p ->
                        val sel = p == period
                        Text(p.label, Modifier.clip(RoundedCornerShape(8.dp)).background(if (sel) Green else MaterialTheme.colorScheme.surfaceContainer)
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp)).clickable { period = p }
                            .padding(14.dp, 7.dp), color = if (sel) Color.White else MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                    }
                }
            }
            item { ChartCard(source, type, result) }
        }
    }
}

private fun typeColor(type: String) = when (type) { "Temperature" -> Orange; "Humidity" -> Blue; "Pressure" -> Green; "CO2" -> Purple; "Noise" -> Gray; "Rain" -> Indigo; else -> Green }

@Composable
private fun ChartCard(source: ChartSource, type: String, result: Pair<ChartData?, String?>?) {
    val unit = NetatmoCharts.unit(type)
    val color = typeColor(type)
    Card {
        Column {
            CardTitle(if (source == ChartSource.EXTERIOR) "Exterior" else "Interior")
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            val data = result?.first
            when {
                result == null -> Box(Modifier.fillMaxWidth().height(280.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Green) }
                data == null -> Column(Modifier.fillMaxWidth().height(280.dp).padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Text("Sin datos", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    result.second?.let { Text(it, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center) }
                }
                else -> {
                    Plot(data, type == "Rain", color, unit)
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Row(Modifier.fillMaxWidth().heightIn(min = 60.dp).height(androidx.compose.foundation.layout.IntrinsicSize.Min)) {
                        fun f(v: Double?) = v?.let { String.format(Locale.getDefault(), "%.1f %s", it, unit) } ?: "—"
                        val fem = NetatmoCharts.feminine(type)
                        if (type == "Rain") {
                            Stat("Total", f(data.total), null); VerticalDivider(); Stat("Máx. período", f(data.max), data.maxLabel)
                            VerticalDivider(); Stat("Días lluvia", data.rainyDays?.toString() ?: "—", null)
                        } else {
                            Stat(if (fem) "Mínima" else "Mínimo", f(data.min), data.minLabel); VerticalDivider()
                            Stat("Promedio", f(data.average), null); VerticalDivider()
                            Stat(if (fem) "Máxima" else "Máximo", f(data.max), data.maxLabel)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.Stat(label: String, value: String, date: String?) {
    Column(Modifier.weight(1f).padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label.uppercase(), fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        date?.let { Text(it, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

/** Línea con su área (o barras, la lluvia), el eje Y con tres valores y las fechas debajo. */
@Composable
private fun Plot(d: ChartData, bars: Boolean, color: Color, unit: String) {
    val values = d.values
    val lo0 = if (bars) 0.0 else values.min(); val hi0 = values.max()
    val pad = if (bars) 0.0 else ((hi0 - lo0) * 0.1).coerceAtLeast(0.5)
    val lo = lo0 - pad; val hi = (hi0 + pad).coerceAtLeast(lo + 1)
    val grid = MaterialTheme.colorScheme.outlineVariant
    val empty = MaterialTheme.colorScheme.surfaceVariant
    Column(Modifier.padding(16.dp, 12.dp)) {
        Row {
            Canvas(Modifier.weight(1f).height(250.dp)) {
                val n = values.size
                for (k in 0..2) { val y = size.height * k / 2f; drawLine(grid, Offset(0f, y), Offset(size.width, y), 1f) }
                fun y(v: Double) = (size.height - (v - lo) / (hi - lo) * size.height).toFloat()
                if (bars) {
                    val w = size.width / n
                    values.forEachIndexed { i, v ->
                        val top = if (v > 0) y(v) else size.height - 2f
                        drawRect(if (v > 0) color.copy(alpha = 0.8f) else empty, Offset(i * w + w * 0.15f, top), Size(w * 0.7f, size.height - top))
                    }
                } else {
                    val step = if (n > 1) size.width / (n - 1) else 0f
                    val line = Path(); val area = Path()
                    values.forEachIndexed { i, v ->
                        val x = i * step; val yy = y(v)
                        if (i == 0) { line.moveTo(x, yy); area.moveTo(x, size.height); area.lineTo(x, yy) } else { line.lineTo(x, yy); area.lineTo(x, yy) }
                    }
                    area.lineTo((n - 1) * step, size.height); area.close()
                    drawPath(area, color.copy(alpha = 0.1f))
                    drawPath(line, color, style = Stroke(2.dp.toPx()))
                }
            }
            Column(Modifier.height(250.dp).padding(start = 6.dp), verticalArrangement = Arrangement.SpaceBetween) {
                listOf(hi, (hi + lo) / 2, lo).forEach { Text(if (unit == "ppm" || unit == "hPa") "${it.roundToInt()}" else String.format(Locale.getDefault(), "%.1f", it),
                    fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
        // Las fechas, cada una bajo su punto.
        Box(Modifier.fillMaxWidth().padding(end = 30.dp).height(16.dp)) {
            val n = values.size
            d.ticks.forEach { (i, label) ->
                val frac = if (n > 1) i.toFloat() / (n - 1) else 0f
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopStart) {
                    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth()) {
                        Text(label, Modifier.padding(start = (maxWidth * frac - 18.dp).coerceAtLeast(0.dp)).width(48.dp), fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    }
                }
            }
        }
    }
}

// --- Ajustes de Netatmo (`netatmoSection` y el selector de estación de iOS) -----------------

@Composable
fun NetatmoSettings() {
    val context = LocalContext.current
    val app = context.app
    val account by app.netatmo.api.account.collectAsStateWithLifecycle()
    val stations by app.netatmo.stations.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var id by remember(account.clientId) { mutableStateOf(account.clientId) }
    var secret by remember(account.clientSecret) { mutableStateOf(account.clientSecret) }
    var refresh by remember(account.refreshToken) { mutableStateOf(account.refreshToken) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val sharedOk = remember { app.netatmo.api.shared.available() }
    var selected by remember { mutableStateOf(app.netatmo.deviceId) }

    fun detect() = scope.launch {
        busy = true; message = null
        app.netatmo.recheck()
        app.netatmo.load(force = true)
        message = app.netatmo.state.value.error ?: "Estaciones: ${app.netatmo.stations.value.size}"
        selected = app.netatmo.deviceId
        app.snapshots.requestUpdate()
        busy = false
    }

    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Netatmo", fontWeight = FontWeight.SemiBold)
            Text(if (sharedOk) "✓ Usa la sesión de Calefacción (no hace falta nada más si allí has iniciado sesión con permiso de estaciones)."
                else "Calefacción no está instalada: Tiempo necesita su propia sesión.",
                fontSize = 12.sp, color = if (sharedOk) Green else MaterialTheme.colorScheme.onSurfaceVariant)
            androidx.compose.material3.OutlinedTextField(id, { id = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Client ID") })
            androidx.compose.material3.OutlinedTextField(secret, { secret = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Client secret") },
                visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation())
            androidx.compose.material3.OutlinedTextField(refresh, { refresh = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Refresh token") },
                visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation())
            Row {
                androidx.compose.material3.TextButton(enabled = !busy && (id != account.clientId || secret != account.clientSecret || refresh != account.refreshToken),
                    onClick = { app.netatmo.api.saveCredentials(id, secret, refresh); detect() }) { Text("Guardar") }
                androidx.compose.material3.TextButton(enabled = id.isNotBlank() && secret.isNotBlank(), onClick = {
                    app.netatmo.api.saveCredentials(id, secret, refresh)
                    runCatching {
                        androidx.browser.customtabs.CustomTabsIntent.Builder().build().launchUrl(context, app.netatmo.api.authorizationUri())
                    }.onFailure { message = it.message }
                }) { Text("Iniciar sesión") }
                if (account.refreshToken.isNotBlank()) androidx.compose.material3.TextButton(onClick = { app.netatmo.api.logout(); refresh = ""; app.netatmo.recheck() }) { Text("Cerrar sesión") }
            }
            Text("Las credenciales salen de dev.netatmo.com. Para iniciar sesión desde aquí, añade allí la Redirect URI " +
                "«${es.altamirano.tiempo.net.Netatmo.REDIRECT_URI}»; si no, pega un refresh token (como en iOS).",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Estación", Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else androidx.compose.material3.TextButton(onClick = { detect() }) { Text("Detectar de nuevo") }
            }
            stations.forEach { d ->
                Row(Modifier.fillMaxWidth().clickable { app.netatmo.apply(d); selected = d.id; scope.launch { app.netatmo.load(); app.snapshots.requestUpdate() } },
                    verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.RadioButton(selected == d.id, { app.netatmo.apply(d); selected = d.id; scope.launch { app.netatmo.load(); app.snapshots.requestUpdate() } })
                    Column {
                        Text(d.stationName ?: d.id, fontSize = 14.sp)
                        Text(d.label + " · " + listOfNotNull(d.modules.firstOrNull { it.type == "NAModule1" }?.let { es.altamirano.tiempo.core.tr("exterior") },
                            d.modules.firstOrNull { it.type == "NAModule3" }?.let { es.altamirano.tiempo.core.tr("pluviómetro") }).joinToString(", ").ifEmpty { es.altamirano.tiempo.core.tr("sin módulos") },
                            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Text("Los módulos se detectan solos desde tu cuenta Netatmo. Netatmo limita las consultas periódicas: la estación se pide como mucho cada 10 minutos.",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            message?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}
