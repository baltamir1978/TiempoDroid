package es.altamirano.tiempo.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import es.altamirano.tiempo.app
import es.altamirano.tiempo.core.ObsRecord
import es.altamirano.tiempo.data.WeatherRepo
import kotlinx.coroutines.launch
import androidx.compose.foundation.background
import androidx.compose.ui.text.drawText
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import es.altamirano.tiempo.core.DailyDay
import es.altamirano.tiempo.core.HourEntry
import es.altamirano.tiempo.core.aqiBand
import es.altamirano.tiempo.core.freshObsTemp
import es.altamirano.tiempo.core.latest
import es.altamirano.tiempo.core.latestTemp
import es.altamirano.tiempo.core.maxOfDay
import es.altamirano.tiempo.core.nextHours
import es.altamirano.tiempo.core.parseObsTime
import es.altamirano.tiempo.core.pickPeriod
import es.altamirano.tiempo.core.pollenLevel
import es.altamirano.tiempo.core.skyNow
import es.altamirano.tiempo.core.upcomingDays
import es.altamirano.tiempo.data.CityWeather
import es.altamirano.tiempo.data.SavedLocation
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.math.roundToInt

/** El idioma de las fechas: el de la app (`I18n`). */
private val Spanish: Locale get() = es.altamirano.tiempo.core.I18n.locale()

@Composable
fun CityPage(loc: SavedLocation, w: CityWeather) {
    val now = LocalDateTime.now(loc.zone)
    val hours = w.hourly.nextHours(now)
    LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = gutter(), vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (w.alerts.isNotEmpty()) item { Alerts(w, loc.zone) }
        if (!w.hasData) item {
            Card {
                Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    if (w.loading) CircularProgressIndicator(color = Green) else {
                        Icon(Icons.Filled.Warning, null, Modifier.size(36.dp), tint = Color(0xFFFF9500))
                        Text("Tiempo no disponible", fontWeight = FontWeight.SemiBold)
                        Text("Inténtalo en unos minutos con ↻.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        } else {
            item { NowCard(w, hours, now) }
            if (loc.stationName != null || loc.isCurrent || loc.isPortuguese) item { PlaceCard(loc) }
            if (hours.isNotEmpty()) item { HourlyCard(hours) }
            if (hours.size > 2) item { TempChart(hours.take(25)) }
            item { DailyCard(w, now.toLocalDate()) }
            w.daily.upcomingDays(now.toLocalDate()).firstOrNull()?.let { item { TodayDetails(it) } }
            if (!w.obs.isNullOrEmpty() && (w.source == "AEMET" || w.source?.startsWith("IPMA") == true)) item { ObservationCard(w, loc) }
            w.air?.let { item { AirCard(it) } }
            item { SourceFooter(w) }
        }
    }
}

@Composable
fun Card(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxWidth().shadow(4.dp, RoundedCornerShape(12.dp), ambientColor = Color(0x0D000000), spotColor = Color(0x0D000000))
        .clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainer)) { content() }
}

@Composable
fun CardTitle(text: String, trailing: String? = null) {
    Row(Modifier.fillMaxWidth().padding(16.dp, 12.dp)) {
        Text(text.uppercase(Spanish), Modifier.weight(1f), fontSize = 12.sp, fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant, letterSpacing = 1.sp)
        trailing?.let { Text(it, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

private fun r(d: Double?) = d?.roundToInt()

@Composable
private fun Alerts(w: CityWeather, zone: ZoneId) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        w.alerts.forEach { a ->
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(alertColor(a.level.ordinal)).padding(12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Warning, null, tint = Color.White)
                Column(Modifier.padding(start = 10.dp)) {
                    Text("${a.phenomenon} · nivel ${a.level.name.lowercase()}", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    val f = DateTimeFormatter.ofPattern("EEE d, HH:mm", Spanish)
                    val win = listOfNotNull(a.onset?.atZone(zone)?.format(f), a.expires?.atZone(zone)?.format(f)).joinToString(" → ")
                    if (win.isNotEmpty()) Text(win, color = Color.White.copy(alpha = 0.9f), fontSize = 12.sp)
                    Text(a.area, color = Color.White.copy(alpha = 0.8f), fontSize = 11.sp)
                }
            }
        }
    }
}

/** La tarjeta grande: la temperatura de la estación si es reciente; si no, la de esta hora. */
@Composable
private fun NowCard(w: CityWeather, hours: List<HourEntry>, now: LocalDateTime) {
    val today = w.daily.upcomingDays(now.toLocalDate()).firstOrNull()
    val current = freshObsTemp(w.obs.latestTemp()) ?: hours.firstOrNull()?.temp ?: w.obs.latestTemp()?.ta
    val sky = w.hourly.skyNow(now.hour) ?: today?.estadoCielo?.let { pickPeriod(it) }
    val hum = w.obs.latest { it.hr }
    val wind = w.obs.latest { it.vv }?.let { (it * 3.6).roundToInt() }
    val gust = w.obs.latest { it.vmax }?.let { (it * 3.6).roundToInt() }
    val rain = w.obs.latest { it.prec }
    val (icon, tint) = skyIcon(sky?.value)
    Row(Modifier.fillMaxWidth().shadow(8.dp, RoundedCornerShape(16.dp), spotColor = GreenDeep.copy(alpha = 0.3f))
        .clip(RoundedCornerShape(16.dp)).background(HeroGradient).padding(16.dp)) {
        Icon(icon, null, Modifier.size(60.dp), tint = if (tint == Color(0xFF8E8E93)) Color.White else tint)
        Column(Modifier.weight(1f).padding(start = 16.dp)) {
            Text(current?.let { "${it.roundToInt()}°" } ?: today?.temperatura?.maxima?.let { "${it.roundToInt()}°" } ?: "—",
                fontSize = 46.sp, fontWeight = FontWeight.Light, color = Color.White)
            Text(sky?.descripcion ?: "—", fontSize = 15.sp, color = Color.White.copy(alpha = 0.9f))
            Row {
                r(today?.temperatura?.maxima)?.let { Text("↑$it°  ", color = Color.White, fontSize = 13.sp) }
                r(today?.temperatura?.minima)?.let { Text("↓$it°", color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp) }
            }
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(7.dp)) {
            hum?.let { HeroStat("💧", "${it.roundToInt()}%", "Humedad") }
            wind?.let { HeroStat("💨", "$it km/h", if ((gust ?: 0) > 0) "racha $gust" else "Viento") }
            rain?.takeIf { it > 0 }?.let { HeroStat("🌧", String.format(Locale.getDefault(), "%.1f L/m²", it), "Lluvia 1h") }
        }
    }
}

@Composable
private fun HeroStat(emoji: String, value: String, label: String) {
    Column(horizontalAlignment = Alignment.End) {
        Text("$emoji $value", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White)
        Text(label, fontSize = 12.sp, color = Color.White.copy(alpha = 0.7f))
    }
}

/**
 * El sitio y la estación de observación que se usa, con su distancia. Al tocarla, el selector
 * (`StationPickerSheet`): la más cercana no siempre es la que representa tu sitio.
 */
@Composable
private fun PlaceCard(loc: SavedLocation) {
    var picking by remember { mutableStateOf(false) }
    Box(Modifier.clickable { picking = true }) {
        Card {
            Row(Modifier.padding(16.dp, 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text((if (loc.isCurrent) "📍 " else "") + loc.name, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                    val app = LocalContext.current.app
                    val tr: (String) -> String = { es.altamirano.tiempo.core.tr(it) }
                    val sub = if (loc.isPortuguese) listOfNotNull(loc.province?.takeIf { it != loc.name }?.let(tr), app.weather.portugalLabel(loc)).joinToString(" · ")
                        else listOfNotNull(loc.province?.takeIf { it != loc.name }, loc.stationName?.let { s ->
                        tr(loc.stationKm?.let { "Estación $s · a ${km(it)} km" } ?: "Estación $s") }).joinToString(" · ")
                    if (sub.isNotEmpty()) Text(sub, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text("›", fontSize = 20.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    if (picking) { if (loc.isPortuguese) SourcePicker(loc) { picking = false } else StationPicker(loc) { picking = false } }
}

fun km(v: Double): String = if (v < 10) String.format(Locale.getDefault(), "%.1f", v) else v.roundToInt().toString()

@Composable
private fun StationPicker(loc: SavedLocation, onDismiss: () -> Unit) {
    val app = LocalContext.current.app
    val scope = rememberCoroutineScope()
    val configured = app.weather.aemet.configured
    var nearby by remember { mutableStateOf<List<Pair<ObsRecord, Double>>?>(null) }
    LaunchedEffect(loc.lat, loc.lon) { nearby = if (configured) app.weather.nearbyStations(loc.lat, loc.lon) else emptyList() }
    fun pick(st: ObsRecord?, km: Double?) {
        val updated = if (st == null) loc.copy(idema = null, stationName = null, stationKm = null, stationPinned = false)
            else WeatherRepo.withStation(loc, st, km ?: 0.0, pinned = true)
        app.locations.update(updated)
        scope.launch { app.snapshots.save(updated, app.weather.load(updated, force = true)) }
        onDismiss()
    }
    AlertDialog(onDismissRequest = onDismiss, confirmButton = { TextButton(onClick = onDismiss) { Text("Listo") } },
        title = { Text("Estación") },
        text = {
            when {
                !configured -> Text("Sin clave de AEMET el tiempo sale de Open-Meteo, que no tiene estaciones: modela tu punto exacto. Pon la clave en Ajustes para elegir estación.")
                nearby == null -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Green) }
                nearby!!.isEmpty() -> Text("No se pudo cargar la red de estaciones de AEMET (¿clave caducada?).")
                else -> LazyColumn {
                    item {
                        // Como iOS: en automática, la que se está usando (la de la tarjeta); con una fijada, la que se usaría.
                        val nearest = nearby!!.first().first.let { WeatherRepo.stationName(it.ubi ?: it.idema ?: "") }
                        StationRow("Automática", "Ahora: " + (if (!loc.stationPinned) loc.stationName ?: nearest else nearest),
                            !loc.stationPinned) { pick(null, null) }
                        Text("Automática usa la estación más cercana. Si está al otro lado de la montaña, elige a mano la que comparta tu valle o tu altitud.",
                            Modifier.padding(vertical = 6.dp), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                    item { Text("Estaciones cercanas", Modifier.padding(top = 10.dp, bottom = 2.dp), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    items(nearby!!) { (st, d) ->
                        StationRow(WeatherRepo.stationName(st.ubi ?: st.idema ?: ""),
                            "a ${km(d)} km" + (st.alt?.let { " · ${it.roundToInt()} m" } ?: ""),
                            loc.stationPinned && loc.idema == st.idema) { pick(st, d) }
                    }
                }
            }
        })
}

/**
 * En Portugal no se elige estación sino servicio (`portugueseSourceList`): el IPMA es el
 * oficial, pero sólo prevé 35 localidades; desde lejos, Open-Meteo modela tu punto exacto.
 */
@Composable
private fun SourcePicker(loc: SavedLocation, onDismiss: () -> Unit) {
    val app = LocalContext.current.app
    val scope = rememberCoroutineScope()
    val point = es.altamirano.tiempo.core.Ipma.forecastPoint(loc.municipio, loc.lat, loc.lon)
    val active = app.weather.portugalSource(loc)
    fun pick(s: es.altamirano.tiempo.core.PortugalSource) {
        app.settings.setPortugalSource(loc.municipio, s)
        scope.launch { app.snapshots.save(loc, app.weather.load(loc, force = true)) }
        onDismiss()
    }
    AlertDialog(onDismissRequest = onDismiss, confirmButton = { TextButton(onClick = onDismiss) { Text("Listo") } },
        title = { Text("Fuente") },
        text = {
            Column {
                point?.let { (p, d) -> StationRow("IPMA · ${p.name}", "Previsión oficial · a ${km(d)} km", active == es.altamirano.tiempo.core.PortugalSource.IPMA) { pick(es.altamirano.tiempo.core.PortugalSource.IPMA) } }
                StationRow("Open-Meteo", "Tu punto exacto", active == es.altamirano.tiempo.core.PortugalSource.OPEN_METEO) { pick(es.altamirano.tiempo.core.PortugalSource.OPEN_METEO) }
                Text(if (point != null && point.second > es.altamirano.tiempo.core.Ipma.NEARBY_KM)
                    "El IPMA solo publica previsión para 35 localidades. Es la fuente oficial, pero desde ${km(point.second)} km su previsión ya describe otro sitio: Open-Meteo modela tu punto exacto. Los avisos son siempre del IPMA."
                    else "Los avisos meteorológicos son siempre del IPMA.", Modifier.padding(top = 8.dp), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        })
}

@Composable
private fun StationRow(title: String, detail: String, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp)
            Text(detail, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (selected) Text("✓", color = Green, fontWeight = FontWeight.Bold, fontSize = 18.sp)
    }
}

@Composable
private fun HourlyCard(hours: List<HourEntry>) {
    Card {
        Column {
            CardTitle("Próximas 48 horas")
            LazyRow(contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 12.dp, end = 12.dp, bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(hours) { h ->
                    val (icon, tint) = skyIcon(h.sky)
                    val first = h === hours.first()
                    Column(Modifier.clip(RoundedCornerShape(10.dp)).background(if (first) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                        .padding(horizontal = 8.dp, vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(if (first) "Ahora" else "%02d".format(h.hour), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        Icon(icon, null, Modifier.size(26.dp).padding(vertical = 3.dp), tint = tint)
                        Text("${h.temp.roundToInt()}°", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                        Text(h.prob?.takeIf { it > 0 }?.let { "💧$it%" } ?: " ", fontSize = 10.sp, color = Color(0xFF007AFF))
                    }
                }
            }
        }
    }
}

/**
 * La curva de las próximas horas (`tempChartCard` de iOS): línea naranja suave con su área, eje de
 * grados con cuatro líneas, y la probabilidad de lluvia en barras azules en una franja propia por
 * debajo de la curva (`floor`…`barCeil`), con las horas debajo alineadas con sus puntos.
 */
@Composable
internal fun TempChart(hours: List<HourEntry>) {
    if (hours.size < 3) return
    val temps = hours.map { it.temp }
    val tMin = temps.min(); val tMax = temps.max()
    val span = maxOf(tMax - tMin, 1.0)
    val floor = tMin - span * 0.5            // abajo del todo: la franja de la lluvia
    val barCeil = tMin - span * 0.08         // hasta donde llega una barra del 100 %
    val top = tMax + span * 0.12
    val hasRain = hours.any { (it.prob ?: 0) > 0 }
    val tickStep = maxOf(1, hours.size / 6)
    val orange = Color(0xFFFF9500)
    val rain = Color(0xFF007AFF).copy(alpha = 0.40f)
    val grid = MaterialTheme.colorScheme.outlineVariant
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val measurer = androidx.compose.ui.text.rememberTextMeasurer()
    val labelStyle = androidx.compose.ui.text.TextStyle(fontSize = 10.sp, color = labelColor)
    // Las marcas del eje: un paso «redondo» (1, 2, 5, 10…) que dé unas cuatro líneas.
    val step = listOf(1.0, 2.0, 5.0, 10.0, 20.0).firstOrNull { (top - floor) / it <= 4.5 } ?: 20.0
    val ticks = generateSequence(Math.ceil(floor / step) * step) { it + step }.takeWhile { it <= top }
        .filter { it >= tMin - span * 0.05 }.toList()
    Card {
        Column {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Temperatura próximas horas", Modifier.weight(1f), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                if (hasRain) {
                    Box(Modifier.size(7.dp, 9.dp).background(rain, androidx.compose.foundation.shape.RoundedCornerShape(1.dp)))
                    Text(" lluvia   ", fontSize = 11.sp, color = labelColor)
                }
                Text("${tMin.roundToInt()}° / ${tMax.roundToInt()}°", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = labelColor)
            }
            Canvas(Modifier.fillMaxWidth().height(150.dp).padding(start = 8.dp, end = 16.dp, top = 8.dp, bottom = 8.dp)) {
                val axisW = 30.dp.toPx()                     // los grados, a la izquierda
                val xLabelH = 16.dp.toPx()                   // las horas, abajo
                val plotW = size.width - axisW; val plotH = size.height - xLabelH
                fun x(i: Int) = axisW + plotW * i / (hours.size - 1)
                fun y(t: Double) = (plotH * (1 - (t - floor) / (top - floor))).toFloat()
                for (t in ticks) {
                    val yy = y(t)
                    drawLine(grid, Offset(axisW, yy), Offset(size.width, yy), strokeWidth = 1.dp.toPx())
                    val lay = measurer.measure("${t.roundToInt()}°", labelStyle)
                    drawText(lay, topLeft = Offset(axisW - lay.size.width - 6.dp.toPx(), yy - lay.size.height / 2f))
                }
                // Curva suave (Catmull-Rom pasada a Bézier, como `.catmullRom` en iOS).
                val pts = hours.indices.map { Offset(x(it), y(temps[it])) }
                val line = Path().apply {
                    moveTo(pts[0].x, pts[0].y)
                    for (i in 0 until pts.size - 1) {
                        val p0 = pts[maxOf(i - 1, 0)]; val p1 = pts[i]; val p2 = pts[i + 1]; val p3 = pts[minOf(i + 2, pts.size - 1)]
                        cubicTo(p1.x + (p2.x - p0.x) / 6, p1.y + (p2.y - p0.y) / 6, p2.x - (p3.x - p1.x) / 6, p2.y - (p3.y - p1.y) / 6, p2.x, p2.y)
                    }
                }
                val area = Path().apply { addPath(line); lineTo(pts.last().x, plotH); lineTo(pts.first().x, plotH); close() }
                drawPath(area, Brush.verticalGradient(listOf(orange.copy(alpha = 0.28f), orange.copy(alpha = 0.02f)), startY = y(tMax), endY = plotH))
                drawPath(line, orange, style = Stroke(2.5.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round))
                // La lluvia encima del área, en su franja, sin tocar la curva.
                hours.forEachIndexed { i, h ->
                    val p = h.prob ?: 0
                    if (p > 0) drawLine(rain, Offset(x(i), y(floor)), Offset(x(i), y(floor + (barCeil - floor) * p / 100.0)),
                        strokeWidth = 5.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round)
                }
                // Las horas, cada una bajo su punto (centrada, sin salirse por los bordes).
                for (i in hours.indices step tickStep) {
                    val lay = measurer.measure("%02dh".format(hours[i].hour), labelStyle)
                    val lx = (x(i) - lay.size.width / 2f).coerceIn(axisW - lay.size.width / 2f, size.width - lay.size.width.toFloat())
                    drawText(lay, topLeft = Offset(lx, plotH + 3.dp.toPx()))
                }
            }
        }
    }
}

@Composable
private fun DailyCard(w: CityWeather, today: LocalDate) {
    val days = w.daily.upcomingDays(today).take(7)
    if (days.isEmpty()) return
    Card {
        Column {
            CardTitle("Próximos 7 días")
            days.forEachIndexed { i, d ->
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                DailyRow(d, i == 0, today)
            }
        }
    }
}

@Composable
private fun DailyRow(d: DailyDay, first: Boolean, today: LocalDate) {
    val sky = pickPeriod(d.estadoCielo)
    val prob = pickPeriod(d.probPrecipitacion)?.value?.toIntOrNull() ?: maxOfDay(d.probPrecipitacion)?.value?.toIntOrNull()
    val (icon, tint) = skyIcon(sky?.value)
    val date = d.fecha?.take(10)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
    val label = when {
        date == null -> d.fecha ?: "—"
        first && date == today -> "Hoy"
        else -> date.format(DateTimeFormatter.ofPattern("EEE d MMM", Spanish)).replaceFirstChar { it.uppercase() }
    }
    Row(Modifier.fillMaxWidth().padding(16.dp, 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.width(96.dp), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        Icon(icon, null, Modifier.size(24.dp), tint = tint)
        Column(Modifier.weight(1f).padding(start = 10.dp)) {
            Row {
                prob?.takeIf { it > 0 }?.let { Text("💧$it%  ", fontSize = 12.sp, color = Color(0xFF007AFF)) }
                d.uvMax?.takeIf { it > 0 }?.let { Text("☀️UV$it", fontSize = 12.sp, color = Color(0xFFFF9500)) }
            }
        }
        r(d.temperatura?.maxima)?.let { Text("$it°", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color(0xFFFF9500)) }
        r(d.temperatura?.minima)?.let { Text("  $it°", fontSize = 15.sp, color = Color(0xFF007AFF)) }
    }
}

@Composable
private fun TodayDetails(t: DailyDay) {
    fun mm(m: es.altamirano.tiempo.core.MinMax?, unit: String) = "${r(m?.minima)?.let { "$it$unit" } ?: "—"} / ${r(m?.maxima)?.let { "$it$unit" } ?: "—"}"
    val rows = buildList {
        t.temperatura?.let { add("Temperatura" to mm(it, "°")) }
        t.sensTermica?.let { add("Sensación térmica" to mm(it, "°")) }
        t.humedadRelativa?.let { add("Humedad relativa" to mm(it, "%")) }
        maxOfDay(t.probPrecipitacion)?.value?.takeIf { it.isNotEmpty() && it != "0" }?.let { add("Prob. lluvia" to "$it%") }
        t.cotaNieveProv?.firstOrNull { !it.value.isNullOrEmpty() }?.value?.let { add("Cota de nieve" to "$it m") }
        (t.viento?.firstOrNull { it.periodo == "00-24" } ?: t.viento?.firstOrNull())?.takeIf { !it.velocidad.isNullOrEmpty() }
            ?.let { add("Viento" to "${it.direccion ?: ""} ${it.velocidad} km/h".trim()) }
        maxOfDay(t.rachaMax)?.value?.takeIf { it.isNotEmpty() }?.let { add("Racha máxima" to "$it km/h") }
        t.uvMax?.takeIf { it > 0 }?.let { add("Índice UV máximo" to "$it") }
    }
    Card {
        Column {
            CardTitle("Detalles de hoy")
            rows.forEach { (k, v) ->
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Row(Modifier.fillMaxWidth().padding(16.dp, 10.dp)) {
                    Text(k, Modifier.weight(1f), fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(v, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}

@Composable
private fun ObservationCard(w: CityWeather, loc: SavedLocation) {
    val o = w.obs ?: return
    val last = o.latestTemp()
    val at = last?.fint?.let(::parseObsTime)?.atZone(loc.zone)?.format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))
    val rows = buildList {
        last?.ta?.let { add("Temperatura" to String.format(Locale.getDefault(), "%.1f°", it)) }
        if (last?.tamin != null && last.tamax != null) add("Mín. / máx. última hora" to "${r(last.tamin)}° / ${r(last.tamax)}°")
        o.latest { it.hr }?.let { add("Humedad" to "${it.roundToInt()}%") }
        o.latest { it.pres }?.let { add("Presión" to String.format(Locale.getDefault(), "%.1f hPa", it)) }
        o.latest { it.vv }?.let { v -> add("Viento" to "${es.altamirano.tiempo.core.windDirection(o.latest { it.dv })} ${(v * 3.6).roundToInt()} km/h") }
        o.latest { it.prec }?.let { add("Lluvia última hora" to String.format(Locale.getDefault(), "%.1f L/m²", it)) }
    }
    Card {
        Column {
            CardTitle("Estación · ${loc.stationName ?: last?.ubi ?: last?.idema ?: ""}", at)
            rows.forEach { (k, v) ->
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Row(Modifier.fillMaxWidth().padding(16.dp, 10.dp)) {
                    Text(k, Modifier.weight(1f), fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(v, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}

private fun severity(s: Int) = when (s) { 0 -> Color(0xFF34C759); 1 -> Color(0xFF30B0C7); 2 -> Color(0xFFFFCC00); 3 -> Color(0xFFFF9500); 4 -> Color(0xFFFF3B30); else -> Color(0xFFAF52DE) }
private fun uvLabel(uv: Double) = when { uv < 3 -> "Bajo"; uv < 6 -> "Moderado"; uv < 8 -> "Alto"; uv < 11 -> "Muy alto"; else -> "Extremo" }
private fun uvColor(uv: Double) = when { uv < 3 -> Color(0xFF34C759); uv < 6 -> Color(0xFFFFCC00); uv < 8 -> Color(0xFFFF9500); uv < 11 -> Color(0xFFFF3B30); else -> Color(0xFFAF52DE) }

@Composable
private fun AirCard(a: es.altamirano.tiempo.core.AirData) {
    Card {
        Column(Modifier.padding(bottom = 12.dp)) {
            CardTitle("Aire · Polen · UV", "Open-Meteo")
            Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                a.aqi?.let { val (label, sev) = aqiBand(it); Badge("Calidad del aire", "$it", label, severity(sev), Modifier.weight(1f)) }
                (a.uvNow ?: a.uvMax)?.let { uv -> Badge("UV", "${uv.roundToInt()}", uvLabel(a.uvMax ?: uv) + (a.uvMax?.let { " · máx ${it.roundToInt()}" } ?: ""), uvColor(uv), Modifier.weight(1f)) }
            }
            val pm = listOfNotNull(a.pm25?.let { "PM2.5 ${it.roundToInt()} µg/m³" }, a.pm10?.let { "PM10 ${it.roundToInt()} µg/m³" }).joinToString("   ")
            if (pm.isNotEmpty()) Text(pm, Modifier.padding(16.dp, 8.dp), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            a.pollen.forEach { p ->
                val (label, sev) = pollenLevel(p.value)
                Row(Modifier.fillMaxWidth().padding(16.dp, 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).clip(RoundedCornerShape(4.dp)).background(severity(sev)))
                    Text("  ${p.name}", Modifier.weight(1f), fontSize = 14.sp)
                    Text("$label  ", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("${p.value.roundToInt()} gr/m³", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun Badge(title: String, value: String, subtitle: String, color: Color, modifier: Modifier) {
    Column(modifier.clip(RoundedCornerShape(10.dp)).background(color.copy(alpha = 0.12f)).padding(10.dp)) {
        Text(title.uppercase(Spanish), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontSize = 30.sp, fontWeight = FontWeight.Black, color = color)
        Text(subtitle, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = color)
    }
}

@Composable
private fun SourceFooter(w: CityWeather) {
    Column(Modifier.fillMaxWidth().padding(bottom = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        w.loadedAt?.let {
            val t = Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).withLocale(Spanish))
            Text("Actualizado $t", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        // Como `sourceCard` de iOS: cuándo elaboró AEMET la predicción.
        (w.daily?.elaborado ?: w.hourly?.elaborado)?.takeIf { w.source == "AEMET" }?.let { e ->
            runCatching { java.time.LocalDateTime.parse(e.take(19)).format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).withLocale(Spanish)) }.getOrNull()
        }?.let { Text("Predicción: $it", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        Text("Fuente: " + when {
            w.source == "AEMET" -> "© AEMET"; w.source == null -> "—"; w.source.startsWith("IPMA") -> "© ${w.source}"; else -> "Open-Meteo (CC BY 4.0)" },
            fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        w.error?.let { Text(it, fontSize = 11.sp, color = Color(0xFFFF9500)) }
        Spacer(Modifier.height(8.dp))
    }
}
