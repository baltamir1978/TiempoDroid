package es.altamirano.tiempo.widget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import es.altamirano.tiempo.ui.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalSize
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.layout.size
import androidx.glance.text.FontWeight
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import es.altamirano.tiempo.R
import es.altamirano.tiempo.app
import es.altamirano.tiempo.core.TempPalette
import es.altamirano.tiempo.core.freshObsTemp
import es.altamirano.tiempo.core.latestTemp
import es.altamirano.tiempo.core.latest
import es.altamirano.tiempo.core.nextHours
import es.altamirano.tiempo.core.skyNow
import es.altamirano.tiempo.core.upcomingDays
import es.altamirano.tiempo.data.CityWeather
import es.altamirano.tiempo.data.LocationStore
import es.altamirano.tiempo.data.SavedLocation
import es.altamirano.tiempo.ui.Green
import es.altamirano.tiempo.ui.TiempoTheme
import es.altamirano.tiempo.ui.skyEmoji
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/** Una hora de la tira del widget (`AemetHourPoint`). */
@Serializable data class HourPoint(val hour: Int, val temp: Int, val sky: String? = null, val prob: Int? = null)

/** Un día de las filas del widget grande (`AemetDayPoint`). */
@Serializable data class DayPoint(val date: String, val min: Int? = null, val max: Int? = null, val sky: String? = null, val prob: Int? = null)

/** Lo que enseña el widget de una ubicación (como `AemetSnapshot` en el App Group de iOS). */
@Serializable
data class Snapshot(
    val name: String, val temp: Int?, val max: Int?, val min: Int?, val sky: String?, val skyText: String?,
    val hourly: List<HourPoint> = emptyList(), val alertLevel: Int = 0, val alert: String? = null, val at: Long = 0,
    val humidity: Int? = null, val windKmh: Int? = null, val daily: List<DayPoint> = emptyList(),
)

private val json = Json { ignoreUnknownKeys = true }

class WidgetSnapshots(private val context: Context) {
    private val prefs = context.getSharedPreferences("widgets", Context.MODE_PRIVATE)
    private var nudge: Job? = null

    fun get(key: String): Snapshot? = prefs.getString(key, null)?.let { runCatching { json.decodeFromString<Snapshot>(it) }.getOrNull() }

    /** Con la misma cadena que la tarjeta verde de la app, para que los dos digan lo mismo. */
    fun save(loc: SavedLocation, w: CityWeather) {
        val now = LocalDateTime.now(loc.zone)
        val days = w.daily.upcomingDays(now.toLocalDate())
        val today = days.firstOrNull()
        val hours = w.hourly.nextHours(now)
        val sky = w.hourly.skyNow(now.hour) ?: today?.estadoCielo?.let { es.altamirano.tiempo.core.pickPeriod(it) }
        val temp = freshObsTemp(w.obs.latestTemp()) ?: hours.firstOrNull()?.temp ?: w.obs.latestTemp()?.ta ?: today?.temperatura?.maxima
        val top = w.alerts.maxByOrNull { it.level }
        val s = Snapshot(loc.name, temp?.roundToInt(), today?.temperatura?.maxima?.roundToInt(), today?.temperatura?.minima?.roundToInt(),
            sky?.value, sky?.descripcion,
            hours.drop(1).take(6).map { HourPoint(it.hour, it.temp.roundToInt(), it.sky, it.prob) },
            top?.level?.ordinal ?: 0, top?.phenomenon, System.currentTimeMillis(),
            humidity = w.obs.latest { it.hr }?.roundToInt(), windKmh = w.obs.latest { it.vv }?.let { (it * 3.6).roundToInt() },
            daily = days.take(5).map { d ->
                DayPoint(d.fecha?.take(10) ?: "", d.temperatura?.minima?.roundToInt(), d.temperatura?.maxima?.roundToInt(),
                    es.altamirano.tiempo.core.pickPeriod(d.estadoCielo)?.value,
                    (es.altamirano.tiempo.core.pickPeriod(d.probPrecipitacion)?.value ?: es.altamirano.tiempo.core.maxOfDay(d.probPrecipitacion)?.value)?.toIntOrNull())
            })
        prefs.edit().putString(LocationStore.keyOf(loc), json.encodeToString(Snapshot.serializer(), s)).apply()
        requestUpdate()
    }

    /**
     * Agrupado: los widgets tienen un cupo de recargas, y avisar en cada página que se pasa lo
     * agotaba en iOS (se quedaban congelados). Se avisa 3 s después del último cambio.
     */
    fun requestUpdate() {
        nudge?.cancel()
        nudge = context.app.scope.launch { delay(3000); updateAllWidgets(context) }
    }
}

/** La barra del día en la escala de la semana (las filas del widget grande): mín ——— máx. */
private fun dayBar(context: Context, lo: Double, hi: Double, wMin: Double, wMax: Double, track: Int, widthDp: Int): android.graphics.Bitmap {
    val d = context.resources.displayMetrics.density
    val w = (widthDp * d).roundToInt().coerceAtLeast(4); val h = (5 * d).roundToInt()
    val b = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
    val c = android.graphics.Canvas(b)
    val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = track }
    c.drawRoundRect(0f, 0f, w.toFloat(), h.toFloat(), h / 2f, h / 2f, p)
    val span = (wMax - wMin).coerceAtLeast(1.0)
    val x0 = ((lo - wMin) / span * w).toFloat(); val x1 = ((hi - wMin) / span * w).toFloat().coerceAtLeast(x0 + h)
    p.color = 0xFF000000.toInt()
    p.shader = android.graphics.LinearGradient(x0, 0f, x1, 0f, 0xFF6CCBFF.toInt(), 0xFFFFB340.toInt(), android.graphics.Shader.TileMode.CLAMP)
    c.drawRoundRect(x0, 0f, x1, h.toFloat(), h / 2f, h / 2f, p)
    return b
}

/**
 * La curva de temperatura de las próximas horas (como las de tendencia de Breezy): un punto por
 * columna con su temperatura encima, un velo bajo la línea y, al pie, la probabilidad de lluvia en
 * barras azules con su porcentaje (como la gráfica de la app). Mapa de bits: Glance no dibuja.
 */
private fun hourCurve(context: Context, temps: List<Int>, probs: List<Int?>, widthDp: Int, heightDp: Int, ink: Int, rainArgb: Int): android.graphics.Bitmap {
    val d = context.resources.displayMetrics.density
    val sp = context.resources.displayMetrics.scaledDensity
    val w = (widthDp * d).roundToInt().coerceAtLeast(8); val h = (heightDp * d).roundToInt().coerceAtLeast(8)
    val b = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
    if (temps.isEmpty()) return b
    val c = android.graphics.Canvas(b)
    val hasRain = probs.any { (it ?: 0) > 0 }
    val rainBand = if (hasRain) 42 * d else 0f                   // la franja de la lluvia, abajo
    val lo = temps.min(); val hi = temps.max(); val span = (hi - lo).coerceAtLeast(3)
    val top = 20 * d; val bottom = h - rainBand - 6 * d
    val xs = temps.indices.map { (it + 0.5f) * w / temps.size }
    val ys = temps.map { bottom - (it - lo).toFloat() / span * (bottom - top) }
    val line = android.graphics.Path().apply {
        moveTo(xs[0], ys[0])
        for (i in 1 until xs.size) { val mx = (xs[i - 1] + xs[i]) / 2; cubicTo(mx, ys[i - 1], mx, ys[i], xs[i], ys[i]) }
    }
    val fill = android.graphics.Path(line).apply { lineTo(xs.last(), h - rainBand); lineTo(xs.first(), h - rainBand); close() }
    val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    val a = ink and 0x00FFFFFF
    p.shader = android.graphics.LinearGradient(0f, top, 0f, h - rainBand, a or 0x40000000, a, android.graphics.Shader.TileMode.CLAMP)
    c.drawPath(fill, p); p.shader = null
    p.style = android.graphics.Paint.Style.STROKE; p.strokeWidth = 2.5f * d; p.color = a or 0xE6000000.toInt(); p.strokeCap = android.graphics.Paint.Cap.ROUND
    c.drawPath(line, p)
    p.style = android.graphics.Paint.Style.FILL; p.color = ink
    val bold = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
    val tp = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = ink; textSize = 15 * sp; typeface = bold; textAlign = android.graphics.Paint.Align.CENTER
    }
    xs.indices.forEach { i -> c.drawCircle(xs[i], ys[i], 3.5f * d, p); c.drawText("${temps[i]}°", xs[i], ys[i] - 7 * d, tp) }
    if (hasRain) {
        val bar = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = rainArgb }
        val rp = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = rainArgb; textSize = 12 * sp; typeface = bold; textAlign = android.graphics.Paint.Align.CENTER
        }
        val base = h.toFloat(); val bw = 16 * d; val maxH = rainBand - 15 * d
        xs.indices.forEach { i ->
            val pr = probs.getOrNull(i) ?: 0
            if (pr > 0) {
                val bh = (maxH * pr / 100f).coerceAtLeast(2 * d)
                bar.alpha = 110 + (145 * pr / 100)                    // más lluvia, más intensa
                c.drawRoundRect(xs[i] - bw / 2, base - bh, xs[i] + bw / 2, base, 2 * d, 2 * d, bar)
                c.drawText("$pr%", xs[i], base - bh - 4 * d, rp)
            }
        }
    }
    return b
}

/**
 * El widget de Tiempo en tres tamaños, como en iOS: pequeño (2×2), mediano (4×2) y grande (4×4).
 * Con el aire de Breezy Weather: temperatura grande en letra fina, iconos dibujados
 * (`WeatherIcons`), poco texto y el aviso como una etiqueta. Letra e iconos grandes para llenar
 * el widget, y la lluvia siempre en azul con su gota. El mediano: sitio y aviso, temperatura con
 * cielo, máx/mín, humedad y viento, el icono, y cinco horas. El grande añade la curva de seis horas
 * con la lluvia al pie y los cinco días con su barra en la escala de la semana.
 */
class WeatherWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Responsive(setOf(DpSize(110.dp, 110.dp), DpSize(250.dp, 110.dp), DpSize(250.dp, 250.dp)))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val app = context.app
        val widgetId = GlanceAppWidgetManager(context).getAppWidgetId(id)
        provideContent {
            // Se lee dentro, según el sello: si no, un widget recién puesto se quedaba con lo de antes de configurarlo.
            val stamp = widgetStamp()
            val (snap, tempBg, scale) = androidx.compose.runtime.remember(stamp) {
                val key = app.settings.widgetCity(widgetId) ?: LocationStore.keyOf(app.locations.all().first())
                Triple(app.snapshots.get(key), app.settings.widgetTempBackground(widgetId), app.settings.tempScale.value)
            }
            androidx.compose.runtime.CompositionLocalProvider(LocalWidgetOpacity provides opacity(app, widgetId, stamp)) { Content(context, snap, tempBg, scale) }
        }
    }

    @androidx.compose.runtime.Composable
    internal fun Content(context: Context, s: Snapshot?, tempBg: Boolean, scale: es.altamirano.tiempo.core.TempScale) {
        val size = LocalSize.current
        val large = size.width >= 250.dp && size.height >= 250.dp
        val medium = !large && size.width >= 250.dp
        val temp = s?.temp?.toDouble()
        val (top, bottom) = if (tempBg && temp != null) TempPalette.gradient(temp, scale) else (0x37D094 to 0x0B6F53)
        val dark = tempBg && temp != null && TempPalette.prefersDarkInk(temp, scale)
        val inkColor = if (dark) Color(0xFF0B2236) else Color.White
        val inkArgb = if (dark) 0xFF0B2236.toInt() else 0xFFFFFFFF.toInt()
        val ink = ColorProvider(inkColor)
        val soft = ColorProvider(inkColor.copy(alpha = 0.8f))
        val rainArgb = if (dark) 0xFF0A3A8C.toInt() else 0xFFBDE6FF.toInt()
        val rain = ColorProvider(Color(rainArgb))
        val light = androidx.glance.text.FontFamily("sans-serif-light")
        val now = java.time.LocalDate.now()

        @androidx.compose.runtime.Composable fun icon(code: String?, dp: Int) =
            Image(ImageProvider(WeatherIcons.sky(context, code, dp)), null, GlanceModifier.size(dp.dp))
        @androidx.compose.runtime.Composable fun glyph(kind: String, text: String, sp: Int, tint: Int = inkArgb, color: ColorProvider = soft) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Image(ImageProvider(WeatherIcons.glyph(context, kind, tint, sp)), null, GlanceModifier.size(sp.dp))
                Text(" $text", maxLines = 1, style = TextStyle(color = color, fontSize = sp.sp))
            }
        }
        // La lluvia: gota azul y porcentaje en negrita; vacío (pero con su sitio) si no llueve.
        @androidx.compose.runtime.Composable fun rainMark(prob: Int?, sp: Int) {
            if ((prob ?: 0) > 0) Row(verticalAlignment = Alignment.CenterVertically) {
                Image(ImageProvider(WeatherIcons.glyph(context, "drop", rainArgb, sp - 2)), null, GlanceModifier.size((sp - 2).dp))
                Text("$prob%", maxLines = 1, style = TextStyle(color = rain, fontSize = sp.sp, fontWeight = FontWeight.Bold))
            } else Text(" ", style = TextStyle(fontSize = sp.sp))
        }
        @androidx.compose.runtime.Composable fun alertChip(full: Boolean, sp: Int) {
            val a = s?.alert ?: return
            val bg = when (s.alertLevel) { 3 -> Color(0xFFE03C31); 2 -> Color(0xFFFF9F1C); else -> Color(0xFFFFD43B) }
            val fg = if (s.alertLevel >= 3) Color.White else Color(0xFF3A2A00)
            Box(GlanceModifier.cornerRadius(9.dp).background(ColorProvider(bg)).padding(horizontal = 7.dp, vertical = 2.dp)) {
                Text(if (full) "⚠︎ $a" else "⚠︎", maxLines = 1, style = TextStyle(color = ColorProvider(fg), fontSize = sp.sp, fontWeight = FontWeight.Bold))
            }
        }
        @androidx.compose.runtime.Composable fun title(fontSp: Int, full: Boolean) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(s!!.name, maxLines = 1, style = TextStyle(color = ink, fontWeight = FontWeight.Medium, fontSize = fontSp.sp))
                if (s.alert != null) { Spacer(GlanceModifier.width(8.dp)); alertChip(full, fontSp - 3) }
            }
        }
        @androidx.compose.runtime.Composable fun bigTemp(sp: Int) =
            Text("${s!!.temp ?: "—"}°", maxLines = 1, style = TextStyle(color = ink, fontSize = sp.sp, fontFamily = light))
        val maxMin = "↑${s?.max ?: "—"}°  ↓${s?.min ?: "—"}°"
        // El icono principal, grande y superpuesto arriba a la derecha: así crece sin empujar las horas.
        @androidx.compose.runtime.Composable fun cornerIcon(dp: Int, topDp: Int, endDp: Int) =
            Box(GlanceModifier.fillMaxSize().padding(top = topDp.dp, end = endDp.dp), contentAlignment = Alignment.TopEnd) { icon(s?.sky, dp) }
        // La lluvia de ahora: la de la hora más próxima, para la cabecera.
        val rainNow = s?.hourly?.firstOrNull()?.prob

        WidgetFrame(top, bottom, "aemet") {
            if (s == null) { Text("Abre la app para cargar el tiempo", GlanceModifier.padding(12.dp), style = TextStyle(color = ink, fontSize = 14.sp)); return@WidgetFrame }
            when {
                large -> Box(GlanceModifier.fillMaxSize()) { Column(GlanceModifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp)) {
                    title(17, true)
                    Row(GlanceModifier.fillMaxWidth().padding(end = 92.dp), verticalAlignment = Alignment.CenterVertically) {
                        bigTemp(58)
                        Column(GlanceModifier.defaultWeight().padding(start = 10.dp)) {
                            Text(s.skyText ?: "", maxLines = 1, style = TextStyle(color = ink, fontSize = 15.sp, fontWeight = FontWeight.Medium))
                            Text(maxMin, style = TextStyle(color = soft, fontSize = 15.sp))
                            Row(GlanceModifier, verticalAlignment = Alignment.CenterVertically) {
                                s.humidity?.let { glyph("drop", "$it%", 14) }
                                if (s.humidity != null && s.windKmh != null) Spacer(GlanceModifier.width(10.dp))
                                s.windKmh?.let { glyph("wind", "$it km/h", 14) }
                            }
                        }
                    }
                    // Las horas: hora e icono; debajo la curva con su temperatura y la lluvia al pie.
                    val hs = s.hourly.take(6)
                    Row(GlanceModifier.fillMaxWidth()) {
                        hs.forEach { h ->
                            Column(GlanceModifier.defaultWeight(), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("%02d".format(h.hour), style = TextStyle(color = soft, fontSize = 13.sp))
                                icon(h.sky, 30)
                            }
                        }
                    }
                    val curveH = if (hs.any { (it.prob ?: 0) > 0 }) 86 else 50
                    if (hs.isNotEmpty()) Image(ImageProvider(hourCurve(context, hs.map { it.temp }, hs.map { it.prob }, size.width.value.roundToInt() - 32, curveH, inkArgb, rainArgb)), null,
                        GlanceModifier.fillMaxWidth().height(curveH.dp), contentScale = androidx.glance.layout.ContentScale.FillBounds)
                    if (s.daily.isNotEmpty()) {
                        Spacer(GlanceModifier.height(6.dp))
                        Box(GlanceModifier.fillMaxWidth().height(1.dp).background(ColorProvider(inkColor.copy(alpha = 0.22f)))) {}
                        val wMin = (s.daily.mapNotNull { it.min }.minOrNull() ?: 0).toDouble()
                        val wMax = (s.daily.mapNotNull { it.max }.maxOrNull() ?: 1).toDouble()
                        val track = android.graphics.Color.argb(60, (inkColor.red * 255).toInt(), (inkColor.green * 255).toInt(), (inkColor.blue * 255).toInt())
                        // En su propia columna: Glance no pinta más de 10 hijos por Column y se comía días.
                        Column(GlanceModifier.fillMaxWidth().defaultWeight()) { s.daily.take(5).forEach { d ->
                            val date = runCatching { java.time.LocalDate.parse(d.date) }.getOrNull()
                            val label = if (date == now) "Hoy" else date?.format(java.time.format.DateTimeFormatter.ofPattern("EEE", es.altamirano.tiempo.core.I18n.locale()))?.replaceFirstChar { it.titlecase() }?.trimEnd('.') ?: ""
                            Row(GlanceModifier.fillMaxWidth().defaultWeight(), verticalAlignment = Alignment.CenterVertically) {
                                Text(label, GlanceModifier.width(46.dp), maxLines = 1, style = TextStyle(color = ink, fontSize = 15.sp, fontWeight = FontWeight.Medium))
                                icon(d.sky, 26)
                                Box(GlanceModifier.width(52.dp).padding(start = 6.dp)) { rainMark(d.prob, 13) }
                                Text("${d.min ?: "—"}°", GlanceModifier.width(32.dp), style = TextStyle(color = soft, fontSize = 15.sp, textAlign = TextAlign.End))
                                Spacer(GlanceModifier.width(8.dp))
                                if (d.min != null && d.max != null) Image(ImageProvider(dayBar(context, d.min.toDouble(), d.max.toDouble(), wMin, wMax, track, 110)), null,
                                    GlanceModifier.defaultWeight().height(6.dp), contentScale = androidx.glance.layout.ContentScale.FillBounds)
                                else Spacer(GlanceModifier.defaultWeight())
                                Text("${d.max ?: "—"}°", GlanceModifier.width(38.dp), style = TextStyle(color = ink, fontSize = 15.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.End))
                            }
                        } }
                    }
                }; cornerIcon(110, 4, 8) }
                medium -> Box(GlanceModifier.fillMaxSize()) { Column(GlanceModifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Row(GlanceModifier.fillMaxWidth().padding(end = 84.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(GlanceModifier.defaultWeight()) {
                            title(14, true)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                bigTemp(40)
                                Column(GlanceModifier.padding(start = 10.dp)) {
                                    Text(s.skyText ?: "", maxLines = 1, style = TextStyle(color = ink, fontSize = 14.sp, fontWeight = FontWeight.Medium))
                                    Text(maxMin, maxLines = 1, style = TextStyle(color = soft, fontSize = 14.sp))
                                    Row(GlanceModifier, verticalAlignment = Alignment.CenterVertically) {
                                        s.humidity?.let { glyph("drop", "$it%", 13) }
                                        if (s.humidity != null && s.windKmh != null) Spacer(GlanceModifier.width(8.dp))
                                        s.windKmh?.let { glyph("wind", "$it km/h", 13) }
                                    }
                                }
                            }
                        }
                    }
                    Spacer(GlanceModifier.defaultWeight())
                    val hs = s.hourly.take(5)
                    val anyRain = hs.any { (it.prob ?: 0) > 0 }
                    Row(GlanceModifier.fillMaxWidth()) {
                        hs.forEach { h ->
                            Column(GlanceModifier.defaultWeight(), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("%02d".format(h.hour), style = TextStyle(color = soft, fontSize = 11.sp))
                                icon(h.sky, 26)
                                Text("${h.temp}°", style = TextStyle(color = ink, fontSize = 15.sp, fontWeight = FontWeight.Bold))
                                if (anyRain) rainMark(h.prob, 11)
                            }
                        }
                    }
                }; cornerIcon(92, 0, 6) }
                else -> Box(GlanceModifier.fillMaxSize()) { Column(GlanceModifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 12.dp)) {
                    title(15, false)
                    bigTemp(52)
                    Text(s.skyText ?: "", maxLines = 1, style = TextStyle(color = ink, fontSize = 14.sp, fontWeight = FontWeight.Medium))
                    Spacer(GlanceModifier.defaultWeight())
                    Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(maxMin, GlanceModifier.defaultWeight(), maxLines = 1, style = TextStyle(color = ink, fontSize = 15.sp, fontWeight = FontWeight.Medium))
                        if ((rainNow ?: 0) > 0) rainMark(rainNow, 14)
                    }
                    Row(GlanceModifier, verticalAlignment = Alignment.CenterVertically) {
                        s.humidity?.let { glyph("drop", "$it%", 13) }
                        if (s.humidity != null && s.windKmh != null) Spacer(GlanceModifier.width(10.dp))
                        s.windKmh?.let { glyph("wind", "$it km/h", 13) }
                    }
                }; cornerIcon(if ("${s.temp ?: ""}".length > 2) 64 else 80, 22, 4) }
            }
        }
    }
}

class WeatherWidgetReceiver : GlanceAppWidgetReceiver() { override val glanceAppWidget: GlanceAppWidget = WeatherWidget() }

/**
 * El sello de cada widget en su estado de Glance. `provideGlance` sólo corre al abrir la sesión, y
 * la sesión sigue viva un rato: si el widget leyera sus ajustes ahí, al elegir ciudad o puerto
 * justo después de ponerlo (o al refrescar mientras sigue viva) pintaría lo de antes. Cada widget
 * lee sus datos dentro de la composición según este sello, y cada aviso lo cambia.
 */
private val WIDGET_STAMP = androidx.datastore.preferences.core.longPreferencesKey("sello")

@androidx.compose.runtime.Composable
fun widgetStamp(): Long = androidx.glance.currentState<androidx.datastore.preferences.core.Preferences>()[WIDGET_STAMP] ?: 0L

/** Cambia el sello de un widget y lo repinta. */
suspend fun refreshWidget(context: Context, widget: GlanceAppWidget, id: GlanceId) {
    androidx.glance.appwidget.state.updateAppWidgetState(context, id) { it[WIDGET_STAMP] = System.nanoTime() }
    widget.update(context, id)
}

/** Avisa a los cuatro widgets (agrupado por `WidgetSnapshots.requestUpdate`). */
suspend fun updateAllWidgets(context: Context) {
    val gm = GlanceAppWidgetManager(context)
    for (w in listOf(WeatherWidget(), NetatmoWidget(), SunMoonWidget(), TidesWidget()))
        runCatching { gm.getGlanceIds(w.javaClass).forEach { id -> runCatching { refreshWidget(context, w, id) } } }
}

/**
 * Lo que se elige de cada widget al ponerlo (o al editarlo), como «Editar widget» en iOS:
 * Tiempo, ciudad y fondo; Sol·Luna, ciudad; Mareas, puerto; Netatmo, fondo.
 */
class WidgetConfigActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent?.extras?.getInt(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID) ?: AppWidgetManager.INVALID_APPWIDGET_ID
        setResult(Activity.RESULT_CANCELED, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id))
        if (id == AppWidgetManager.INVALID_APPWIDGET_ID) { finish(); return }
        val provider = AppWidgetManager.getInstance(this).getAppWidgetInfo(id)?.provider?.className ?: ""
        val kind = when {
            provider.endsWith("NetatmoWidgetReceiver") -> "netatmo"
            provider.endsWith("SunMoonWidgetReceiver") -> "sunmoon"
            provider.endsWith("TidesWidgetReceiver") -> "tides"
            else -> "weather"
        }
        val locs = app.locations.all()
        // Cada cambio se guarda y se ve al momento en el widget; antes el interruptor sólo se
        // guardaba al tocar una ciudad, y al volver atrás se perdía.
        fun ok() = setResult(Activity.RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id))
        fun refresh() = app.scope.launch {
            val gm = GlanceAppWidgetManager(applicationContext)
            val glanceId = runCatching { gm.getGlanceIdBy(id) }.getOrNull() ?: return@launch
            val widget = when (kind) { "netatmo" -> NetatmoWidget(); "sunmoon" -> SunMoonWidget(); "tides" -> TidesWidget(); else -> WeatherWidget() }
            runCatching { refreshWidget(applicationContext, widget, glanceId) }
        }
        fun done() { refresh(); ok(); finish() }
        setContent {
            TiempoTheme {
                var tempBg by remember { mutableStateOf(app.settings.widgetTempBackground(id, default = kind == "weather")) }
                var opacity by remember { mutableStateOf(app.settings.widgetOpacity(id).toFloat()) }
                var city by remember { mutableStateOf(app.settings.widgetCity(id)) }
                var port by remember { mutableStateOf(app.settings.widgetPort(id) ?: app.settings.tidePort.value) }
                Surface(Modifier.fillMaxSize()) {
                    // Con targetSdk 35 la ventana va de borde a borde: sin esto el título queda bajo la barra de estado y la cámara.
                    androidx.compose.foundation.lazy.LazyColumn(Modifier.safeDrawingPadding(),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = es.altamirano.tiempo.ui.gutter(), vertical = 16.dp)) {
                        item {
                            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                Text(when (kind) { "netatmo" -> "Widget de Netatmo"; "sunmoon" -> "Widget de Sol·Luna"; "tides" -> "Widget de Mareas"; else -> "Widget de Tiempo" },
                                    Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                                androidx.compose.material3.Button(onClick = { done() }) { Text("Listo") }
                            }
                        }
                        if (kind == "weather" || kind == "netatmo") item {
                            Row(Modifier.padding(top = 12.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                Text("Color según la temperatura", Modifier.weight(1f))
                                Switch(tempBg, { tempBg = it; app.settings.setWidgetTempBackground(id, it); ok(); refresh() })
                            }
                        }
                        item {
                            Column(Modifier.padding(vertical = 8.dp)) {
                                Text("Opacidad del fondo: ${opacity.roundToInt()}%")
                                androidx.compose.material3.Slider(opacity, { opacity = it }, valueRange = 0f..100f, steps = 19,
                                    onValueChangeFinished = { app.settings.setWidgetOpacity(id, opacity.roundToInt()); ok(); refresh() })
                            }
                        }
                        when (kind) {
                            "netatmo" -> {}
                            "tides" -> {
                                val here = locs.firstOrNull()
                                val near = here?.let { es.altamirano.tiempo.core.portsByDistance(it.lat, it.lon).take(4).map { it.first } } ?: emptyList()
                                item { Text("Puerto", style = MaterialTheme.typography.titleSmall, color = Green) }
                                items(near + es.altamirano.tiempo.core.IHM_STATIONS.filter { it !in near }) { p ->
                                    ListItem(headlineContent = { Text(p.name) },
                                        supportingContent = if (p in near && here != null) { { Text("a ${es.altamirano.tiempo.ui.km(es.altamirano.tiempo.core.Places.distanceKm(here.lat, here.lon, p.lat, p.lon))} km de ${here.name}") } } else null,
                                        trailingContent = if (p.id == port) { { Text("✓", color = Green) } } else null,
                                        modifier = Modifier.clickable { port = p.id; app.settings.setWidgetPort(id, p.id); done() })
                                }
                            }
                            else -> {
                                item { Text("Ciudad", style = MaterialTheme.typography.titleSmall, color = Green) }
                                items(locs) { l ->
                                    val key = LocationStore.keyOf(l)
                                    ListItem(headlineContent = { Text(if (l.isCurrent) "📍 ${l.name}" else l.name) },
                                        trailingContent = if (key == (city ?: LocationStore.keyOf(locs.first()))) { { Text("✓", color = Green) } } else null,
                                        modifier = Modifier.clickable {
                                            city = key
                                            app.settings.setWidget(id, key, tempBg)
                                            if (kind == "weather") app.scope.launch { runCatching { app.weather.load(l) }.getOrNull()?.let { app.snapshots.save(l, it) }; refresh() }
                                            done()
                                        })
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Refresco en segundo plano de las ubicaciones con widget y de la estación Netatmo (cada 3 h). */
class RefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext.app
        val gm = GlanceAppWidgetManager(applicationContext)
        val ids = runCatching { gm.getGlanceIds(WeatherWidget::class.java) }.getOrDefault(emptyList())
        val keys = ids.mapNotNull { app.settings.widgetCity(gm.getAppWidgetId(it)) }.toSet()
        for (key in keys) {
            var loc = app.locations.byKey(key) ?: continue
            if (loc.isCurrent) loc = app.locations.locate() ?: loc
            runCatching { app.snapshots.save(loc, app.weather.load(loc)) }
        }
        updateAllWidgets(applicationContext)
        return Result.success()
    }

    companion object {
        fun schedule(context: Context) {
            val wm = WorkManager.getInstance(context)
            wm.enqueueUniquePeriodicWork("widgets", ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<RefreshWorker>(3, TimeUnit.HOURS)
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build())
            wm.enqueueUniquePeriodicWork("cada-hora", ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<HourlyWorker>(1, TimeUnit.HOURS).build())
        }
    }
}

/**
 * Cada hora: la estación Netatmo si hay su widget (la caché de 10 min frena lo demás; Netatmo
 * limita las consultas periódicas desde marzo de 2027), y Sol·Luna y Mareas, que se calculan
 * sin red o salen de la caché del IHM y sólo necesitan mover la hora.
 */
class HourlyWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext.app
        val netatmoWidgets = runCatching { GlanceAppWidgetManager(applicationContext).getGlanceIds(NetatmoWidget::class.java) }.getOrDefault(emptyList())
        if (netatmoWidgets.isNotEmpty() && app.netatmo.api.configured) runCatching { app.netatmo.load() }
        updateAllWidgets(applicationContext)
        return Result.success()
    }
}
