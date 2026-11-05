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
import androidx.compose.material3.Text
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

private val Spanish = Locale("es", "ES")

@Composable
fun CityPage(loc: SavedLocation, w: CityWeather) {
    val now = LocalDateTime.now(loc.zone)
    val hours = w.hourly.nextHours(now)
    LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
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
            if (loc.stationName != null || loc.isCurrent) item { PlaceCard(loc) }
            if (hours.isNotEmpty()) item { HourlyCard(hours) }
            if (hours.size > 2) item { TempChart(hours.take(25)) }
            item { DailyCard(w, now.toLocalDate()) }
            w.daily.upcomingDays(now.toLocalDate()).firstOrNull()?.let { item { TodayDetails(it) } }
            if (!w.obs.isNullOrEmpty() && w.source == "AEMET") item { ObservationCard(w, loc) }
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
                    val sub = listOfNotNull(loc.province?.takeIf { it != loc.name }, loc.stationName?.let { s ->
                        "Estación $s" + (loc.stationKm?.let { " · a ${km(it)} km" } ?: "") }).joinToString(" · ")
                    if (sub.isNotEmpty()) Text(sub, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text("›", fontSize = 20.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    if (picking) StationPicker(loc) { picking = false }
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
                        StationRow("Automática", "La más cercana" + (nearby!!.first().first.ubi?.let { ": " + WeatherRepo.stationName(it) } ?: ""),
                            !loc.stationPinned) { pick(null, null) }
                        Text("Si la más cercana está al otro lado de la montaña, elige la que comparta tu valle o tu altitud.",
                            Modifier.padding(vertical = 6.dp), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                    items(nearby!!) { (st, d) ->
                        StationRow(WeatherRepo.stationName(st.ubi ?: st.idema ?: ""),
                            "a ${km(d)} km" + (st.alt?.let { " · ${it.roundToInt()} m" } ?: ""),
                            loc.stationPinned && loc.idema == st.idema) { pick(st, d) }
                    }
                }
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

/** La curva de las próximas horas, con barras de lluvia debajo (al estilo de Breezy). */
@Composable
private fun TempChart(hours: List<HourEntry>) {
    val tMin = hours.minOf { it.temp }; val tMax = hours.maxOf { it.temp }
    val lo = tMin - 1; val hi = tMax + 1
    val line = MaterialTheme.colorScheme.primary
    val rainColor = Color(0xFF007AFF).copy(alpha = 0.45f)
    Card {
        Column {
            CardTitle("Temperatura próximas horas", "${tMin.roundToInt()}° / ${tMax.roundToInt()}°")
            Canvas(Modifier.fillMaxWidth().height(110.dp).padding(start = 16.dp, end = 16.dp, bottom = 4.dp)) {
                val n = hours.size; val step = size.width / (n - 1)
                hours.forEachIndexed { i, h ->
                    val p = (h.prob ?: 0) / 100f
                    if (p > 0) drawRect(rainColor, Offset(i * step - step * 0.3f, size.height - p * size.height * 0.4f), Size(step * 0.6f, p * size.height * 0.4f))
                }
                val path = Path()
                hours.forEachIndexed { i, h ->
                    val x = i * step; val y = (size.height * 0.85f) - ((h.temp - lo) / (hi - lo)).toFloat() * size.height * 0.75f
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(path, Brush.horizontalGradient(listOf(Color(0xFF32ADE6), line, Sun)), style = Stroke(3.dp.toPx()))
            }
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                hours.filterIndexed { i, _ -> i % 6 == 0 }.forEach { Text("%02dh".format(it.hour), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
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
        Text("Fuente: " + when (w.source) { "AEMET" -> "© AEMET"; null -> "—"; else -> "Open-Meteo (CC BY 4.0)" },
            fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        w.error?.let { Text(it, fontSize = 11.sp, color = Color(0xFFFF9500)) }
        Spacer(Modifier.height(8.dp))
    }
}
