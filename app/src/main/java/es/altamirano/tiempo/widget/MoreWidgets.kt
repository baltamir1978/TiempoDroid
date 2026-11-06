package es.altamirano.tiempo.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalSize
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import es.altamirano.tiempo.app
import es.altamirano.tiempo.core.IHM_STATIONS
import es.altamirano.tiempo.core.NetatmoSnapshot
import es.altamirano.tiempo.core.SunMoon
import es.altamirano.tiempo.core.TempPalette
import es.altamirano.tiempo.core.TempScale
import es.altamirano.tiempo.core.Tide
import es.altamirano.tiempo.core.TideCurve
import es.altamirano.tiempo.data.LocationStore
import es.altamirano.tiempo.ui.MainActivity
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

private val hhmm = DateTimeFormatter.ofPattern("HH:mm")
private const val GREEN_TOP = 0x37D094
private const val GREEN_BOTTOM = 0x0B6F53

/** Un degradado vertical de verdad (Glance sólo pinta fondos lisos): un mapa de bits estirado. */
fun gradientBitmap(top: Int, bottom: Int): Bitmap {
    val b = Bitmap.createBitmap(4, 128, Bitmap.Config.ARGB_8888)
    val c = android.graphics.Canvas(b)
    c.drawRect(0f, 0f, 4f, 128f, Paint().apply { shader = LinearGradient(0f, 0f, 0f, 128f, top or 0xFF000000.toInt(), bottom or 0xFF000000.toInt(), Shader.TileMode.CLAMP) })
    return b
}

/** El fondo del widget a pantalla completa, con las esquinas redondeadas. */
@Composable
fun WidgetFrame(top: Int, bottom: Int, deepLink: String, content: @Composable () -> Unit) {
    Box(GlanceModifier.fillMaxSize().appWidgetBackground().cornerRadius(20.dp).background(ColorProvider(Color(0xFF000000.toInt() or top)))
        .clickable(actionStartActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("tiempo://$deepLink")).setPackage("es.altamirano.tiempo")))) {
        Image(ImageProvider(gradientBitmap(top, bottom)), null, GlanceModifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
        content()
    }
}

private fun density(context: Context) = context.resources.displayMetrics.density

/** Un sensor como anillo: el arco dice dónde cae la medida entre `lo` y `hi` (`SensorRing`). */
private fun ringBitmap(context: Context, fraction: Double, tint: Int, track: Int, sizeDp: Int): Bitmap {
    val px = (sizeDp * density(context)).roundToInt().coerceAtLeast(8)
    val b = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
    val c = android.graphics.Canvas(b)
    val w = 4 * density(context)
    val r = RectF(w / 2, w / 2, px - w / 2, px - w / 2)
    val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = w; strokeCap = Paint.Cap.ROUND }
    c.drawArc(r, 0f, 360f, false, p.apply { color = track })
    c.drawArc(r, -90f, (360 * fraction.coerceIn(0.0, 1.0)).toFloat(), false, p.apply { color = tint })
    return b
}

/** Mín ——•—— máx: la temperatura de hoy con un punto en la de ahora (`RangeBar`). */
private fun rangeBitmap(context: Context, fraction: Double, ink: Int, widthDp: Int): Bitmap {
    val d = density(context); val w = (widthDp * d).roundToInt(); val h = (10 * d).roundToInt()
    val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val c = android.graphics.Canvas(b)
    val p = Paint(Paint.ANTI_ALIAS_FLAG)
    p.shader = LinearGradient(0f, 0f, w.toFloat(), 0f, 0xFF7FC8FF.toInt(), 0xFFFFB347.toInt(), Shader.TileMode.CLAMP)
    c.drawRoundRect(RectF(0f, h / 2f - 2 * d, w.toFloat(), h / 2f + 2 * d), 2 * d, 2 * d, p)
    p.shader = null; p.color = ink
    c.drawCircle(4 * d + (w - 8 * d) * fraction.coerceIn(0.0, 1.0).toFloat(), h / 2f, 4 * d, p)
    return b
}

// --- Netatmo ---------------------------------------------------------------------------------

/**
 * La estación Netatmo, fuera y dentro (`NetatmoWidget`). El widget no habla con Netatmo: pinta
 * lo último que guardó la app (pestaña Actual o el refresco en segundo plano) y dice de cuándo es.
 */
class NetatmoWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Responsive(setOf(DpSize(110.dp, 110.dp), DpSize(250.dp, 110.dp), DpSize(250.dp, 250.dp)))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val app = context.app
        val widgetId = GlanceAppWidgetManager(context).getAppWidgetId(id)
        val snap = app.netatmo.snapshot()
        val tempBg = app.settings.widgetTempBackground(widgetId, default = false)
        val scale = app.settings.tempScale.value
        provideContent { Content(context, snap, tempBg, scale) }
    }

    @Composable
    internal fun Content(context: Context, s: NetatmoSnapshot?, tempBg: Boolean, scale: TempScale) {
        val size = LocalSize.current
        val t = s?.temperature
        val (top, bottom) = if (tempBg && t != null) TempPalette.gradient(t, scale) else GREEN_TOP to GREEN_BOTTOM
        val dark = tempBg && t != null && TempPalette.prefersDarkInk(t, scale)
        val inkArgb = if (dark) 0xFF0B2236.toInt() else 0xFFFFFFFF.toInt()
        val ink = ColorProvider(Color(inkArgb))
        val soft = ColorProvider(Color(inkArgb).copy(alpha = 0.78f))
        val track = Color(inkArgb).copy(alpha = 0.22f).let { android.graphics.Color.argb((it.alpha * 255).toInt(), (it.red * 255).toInt(), (it.green * 255).toInt(), (it.blue * 255).toInt()) }
        val cool = if (dark) 0xFF1F66D1.toInt() else 0xFF8FD3FF.toInt()
        val good = if (dark) 0xFF1E8C5A.toInt() else 0xFF7CF2B5.toInt()
        val amber = if (dark) 0xFFB26A00.toInt() else 0xFFFFD166.toInt()
        val red = if (dark) 0xFFC0392B.toInt() else 0xFFFF8A80.toInt()
        val light = androidx.glance.text.FontFamily("sans-serif-light")

        // Un sensor como anillo con su valor dentro del hueco de debajo y el nombre al pie.
        @Composable fun ring(value: Double, lo: Double, hi: Double, tint: Int, text: String, caption: String, sz: Int) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = GlanceModifier.padding(horizontal = 4.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Image(ImageProvider(ringBitmap(context, (value - lo) / (hi - lo), tint, track, sz)), caption, GlanceModifier.size(sz.dp))
                    Text(text, style = TextStyle(color = ink, fontSize = (sz / 4).coerceIn(12, 16).sp, fontWeight = FontWeight.Bold))
                }
                Text(caption, maxLines = 1, style = TextStyle(color = soft, fontSize = 11.sp))
            }
        }
        @Composable fun range(widthDp: Int, sp: Int) {
            val mn = s?.tempMinOut; val mx = s?.tempMaxOut
            if (mn != null && mx != null) Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${mn.roundToInt()}° ", style = TextStyle(color = soft, fontSize = sp.sp))
                Image(ImageProvider(rangeBitmap(context, if (mx > mn && t != null) (t - mn) / (mx - mn) else 0.5, inkArgb, widthDp)), null, GlanceModifier.width(widthDp.dp).height(10.dp))
                Text(" ${mx.roundToInt()}°", style = TextStyle(color = ink, fontSize = sp.sp, fontWeight = FontWeight.Bold))
            }
        }
        fun temp(v: Double?) = v?.let { "${it.roundToInt()}°" } ?: "—"
        @Composable fun big(v: Double?, sp: Int, modifier: GlanceModifier = GlanceModifier) =
            Text(temp(v), modifier, maxLines = 1, style = TextStyle(color = ink, fontSize = sp.sp, fontFamily = light))
        @Composable fun glyph(kind: String, text: String, sp: Int) = Row(verticalAlignment = Alignment.CenterVertically) {
            Image(ImageProvider(WeatherIcons.glyph(context, kind, inkArgb, sp)), null, GlanceModifier.size(sp.dp))
            Text(" $text", maxLines = 1, style = TextStyle(color = soft, fontSize = sp.sp))
        }
        @Composable fun label(text: String) = Text(text, style = TextStyle(color = soft, fontSize = 11.sp, fontWeight = FontWeight.Bold))
        fun co2Tint(c: Double) = if (c > 1600) red else if (c > 1000) amber else good

        WidgetFrame(top, bottom, "netatmo") {
            if (s == null) {
                Column(GlanceModifier.fillMaxSize().padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalAlignment = Alignment.CenterVertically) {
                    Text("Abre la app para actualizar", style = TextStyle(color = soft, fontSize = 13.sp))
                }
                return@WidgetFrame
            }
            val updated = Instant.ofEpochMilli(s.at).atZone(ZoneId.systemDefault()).format(hhmm)
            @Composable fun header(sp: Int) = Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(s.stationName, GlanceModifier.defaultWeight(), maxLines = 1, style = TextStyle(color = ink, fontSize = sp.sp, fontWeight = FontWeight.Medium))
                Text(updated, style = TextStyle(color = soft, fontSize = 11.sp))
            }
            when {
                size.height >= 250.dp -> Column(GlanceModifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 14.dp)) {
                    header(16)
                    Spacer(GlanceModifier.defaultWeight())
                    label("EXTERIOR")
                    Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(GlanceModifier.defaultWeight()) {
                            big(s.temperature, 64)
                            range(80, 13)
                        }
                        s.humidity?.let { ring(it, 0.0, 100.0, cool, "${it.roundToInt()}%", "Humedad", 58) }
                        s.pressure?.let { ring(it, 980.0, 1040.0, good, "${it.roundToInt()}", "hPa", 58) }
                        (s.rainToday ?: s.rain)?.let { ring(it, 0.0, 10.0, cool, String.format(Locale.getDefault(), "%.1f", it), "Lluvia mm", 58) }
                    }
                    Spacer(GlanceModifier.defaultWeight())
                    Box(GlanceModifier.fillMaxWidth().height(1.dp).background(ColorProvider(Color(inkArgb).copy(alpha = 0.22f)))) {}
                    Spacer(GlanceModifier.defaultWeight())
                    label("INTERIOR")
                    Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        big(s.tempIn, 64, GlanceModifier.defaultWeight())
                        s.humidityIn?.let { ring(it, 0.0, 100.0, cool, "${it.roundToInt()}%", "Humedad", 58) }
                        s.co2?.let { ring(it, 400.0, 2000.0, co2Tint(it), "${it.roundToInt()}", "CO₂ ppm", 58) }
                        s.noise?.let { ring(it, 30.0, 90.0, inkArgb, "${it.roundToInt()}", "Ruido dB", 58) }
                    }
                    Spacer(GlanceModifier.defaultWeight())
                }
                size.width >= 250.dp -> Column(GlanceModifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp)) {
                    header(15)
                    Spacer(GlanceModifier.defaultWeight())
                    Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(GlanceModifier.defaultWeight()) {
                            label("EXTERIOR")
                            big(s.temperature, 48)
                            range(54, 12)
                        }
                        s.humidity?.let { ring(it, 0.0, 100.0, cool, "${it.roundToInt()}%", "Humedad", 52) }
                        Box(GlanceModifier.width(1.dp).height(70.dp).background(ColorProvider(Color(inkArgb).copy(alpha = 0.22f)))) {}
                        Column(GlanceModifier.defaultWeight().padding(start = 10.dp)) {
                            label("INTERIOR")
                            big(s.tempIn, 48)
                            s.humidityIn?.let { glyph("drop", "${it.roundToInt()}%", 13) }
                        }
                        s.co2?.let { ring(it, 400.0, 2000.0, co2Tint(it), "${it.roundToInt()}", "CO₂ ppm", 52) }
                    }
                    Spacer(GlanceModifier.defaultWeight())
                }
                else -> Column(GlanceModifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 12.dp)) {
                    header(15)
                    big(s.temperature, 56)
                    range(56, 13)
                    Spacer(GlanceModifier.defaultWeight())
                    Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        s.humidity?.let { glyph("drop", "${it.roundToInt()}%", 14) }
                        Spacer(GlanceModifier.defaultWeight())
                        s.tempIn?.let { glyph("home", temp(it), 14) }
                    }
                }
            }
        }
    }
}

class NetatmoWidgetReceiver : GlanceAppWidgetReceiver() { override val glanceAppWidget: GlanceAppWidget = NetatmoWidget() }

// --- Sol·Luna --------------------------------------------------------------------------------

/** Orto y ocaso, fase de la luna y la próxima (`SunMoonWidget`). Se calcula aquí, sin red. */
class SunMoonWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Responsive(setOf(DpSize(110.dp, 110.dp), DpSize(250.dp, 110.dp)))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val app = context.app
        val widgetId = GlanceAppWidgetManager(context).getAppWidgetId(id)
        val key = app.settings.widgetCity(widgetId)
        val loc = key?.let { app.locations.byKey(it) } ?: app.locations.all().first()
        val zone = loc.zone
        val now = Instant.now()
        val today = LocalDate.now(zone)
        val sun = SunMoon.sun(today, loc.lat, loc.lon, zone)
        val moon = SunMoon.moon(today, loc.lat, loc.lon, zone, now)
        val next = SunMoon.nextPhases(now, 1).firstOrNull()
        fun t(i: Instant?) = i?.atZone(zone)?.format(hhmm) ?: "—"
        val nextWhen = next?.second?.atZone(zone)?.format(DateTimeFormatter.ofPattern("d MMM · HH:mm", es.altamirano.tiempo.core.I18n.locale()))
        val daylight = sun.daylightMinutes?.let { "${it / 60} h ${"%02d".format(it % 60)} min" }
        provideContent {
            Content(context, loc.name, t(sun.sunrise), t(sun.sunset), t(moon.rise), t(moon.set), moon.ageDays / 29.530588853,
                moon.illumination, moon.phase.label, next?.first, nextWhen, daylight)
        }
    }

    /** La fase de una luna con nombre, en la fracción del ciclo que dibuja `WeatherIcons.moon`. */
    private fun phaseFraction(p: es.altamirano.tiempo.core.MoonPhase) = p.ordinal / 8.0

    @Composable
    internal fun Content(context: Context, place: String, rise: String, set: String, moonRise: String, moonSet: String, cycle: Double,
                         illumination: Double, label: String, next: es.altamirano.tiempo.core.MoonPhase?, nextWhen: String?, daylight: String? = null) {
        val wide = LocalSize.current.width >= 250.dp
        val white = ColorProvider(Color.White); val soft = ColorProvider(Color.White.copy(alpha = 0.8f))
        val ink = 0xFFFFFFFF.toInt()
        val light = androidx.glance.text.FontFamily("sans-serif-light")
        // Orto u ocaso: el icono y la hora grande en letra fina.
        @Composable fun sunLine(kind: String, time: String, sp: Int) = Row(verticalAlignment = Alignment.CenterVertically) {
            Image(ImageProvider(WeatherIcons.glyph(context, kind, ink, sp - 2)), null, GlanceModifier.size((sp - 2).dp))
            Text("  $time", style = TextStyle(color = white, fontSize = sp.sp, fontFamily = light))
        }
        val illum = "${(illumination * 100).roundToInt()}% iluminada"
        WidgetFrame(GREEN_TOP, GREEN_BOTTOM, "cosmos") {
            if (!wide) Box(GlanceModifier.fillMaxSize()) {
                Column(GlanceModifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 12.dp)) {
                    Text(place, maxLines = 1, style = TextStyle(color = white, fontSize = 15.sp, fontWeight = FontWeight.Medium))
                    Spacer(GlanceModifier.defaultWeight())
                    sunLine("rise", rise, 24)
                    sunLine("set", set, 24)
                    Spacer(GlanceModifier.defaultWeight())
                    Text(illum, maxLines = 1, style = TextStyle(color = soft, fontSize = 13.sp))
                }
                // La luna grande en la esquina, como el icono del widget de Tiempo.
                Box(GlanceModifier.fillMaxSize().padding(top = 8.dp, end = 8.dp), contentAlignment = Alignment.TopEnd) {
                    Image(ImageProvider(WeatherIcons.moon(context, cycle, 56)), label, GlanceModifier.size(56.dp))
                }
            } else Row(GlanceModifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(GlanceModifier.defaultWeight()) {
                    Text(place, maxLines = 1, style = TextStyle(color = white, fontSize = 15.sp, fontWeight = FontWeight.Medium))
                    Spacer(GlanceModifier.height(4.dp))
                    sunLine("rise", rise, 24)
                    sunLine("set", set, 24)
                    daylight?.let { Text("Día: $it", maxLines = 1, style = TextStyle(color = soft, fontSize = 13.sp)) }
                    Text("Luna  ↑ $moonRise   ↓ $moonSet", maxLines = 1, style = TextStyle(color = soft, fontSize = 13.sp))
                }
                Box(GlanceModifier.width(1.dp).fillMaxHeight().background(ColorProvider(Color.White.copy(alpha = 0.25f)))) {}
                Column(GlanceModifier.defaultWeight().padding(start = 14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Image(ImageProvider(WeatherIcons.moon(context, cycle, 56)), null, GlanceModifier.size(56.dp))
                        Text(label, GlanceModifier.padding(start = 8.dp), maxLines = 2, style = TextStyle(color = white, fontSize = 15.sp, fontWeight = FontWeight.Medium))
                    }
                    Text(illum, maxLines = 1, style = TextStyle(color = soft, fontSize = 13.sp))
                    if (next != null && nextWhen != null) {
                        Spacer(GlanceModifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Image(ImageProvider(WeatherIcons.moon(context, phaseFraction(next), 28)), null, GlanceModifier.size(28.dp))
                            Column(GlanceModifier.padding(start = 8.dp)) {
                                Text(next.label, maxLines = 1, style = TextStyle(color = white, fontSize = 13.sp, fontWeight = FontWeight.Medium))
                                Text(nextWhen, maxLines = 1, style = TextStyle(color = soft, fontSize = 12.sp))
                            }
                        }
                    }
                }
            }
        }
    }
}

class SunMoonWidgetReceiver : GlanceAppWidgetReceiver() { override val glanceAppWidget: GlanceAppWidget = SunMoonWidget() }

// --- Mareas ----------------------------------------------------------------------------------

/**
 * La marea de ahora en un puerto (`TidesWidget`): una playa cuya agua llega a la altura de la
 * marea dentro del rango del día (cerca de la pleamar casi todo agua, cerca de la bajamar casi
 * todo arena), con sol o luna según la hora del puerto, y la próxima pleamar o bajamar.
 */
class TidesWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Responsive(setOf(DpSize(110.dp, 110.dp), DpSize(250.dp, 110.dp)))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val app = context.app
        val widgetId = GlanceAppWidgetManager(context).getAppWidgetId(id)
        val portId = app.settings.widgetPort(widgetId) ?: app.settings.tidePort.value
        val port = IHM_STATIONS.firstOrNull { it.id == portId } ?: IHM_STATIONS.first { it.id == "4" }
        val zone = app.tides.zone(port)
        val today = LocalDate.now(zone)
        val tides = runCatching { app.tides.days(port, today.minusDays(1), 3) }.getOrDefault(emptyList()).flatMap { it.second }
        val sun = SunMoon.sun(today, port.lat, port.lon, zone)
        provideContent { Content(context, port.name, tides, zone, sun.sunrise, sun.sunset) }
    }

    @Composable
    internal fun Content(context: Context, name: String, tides: List<Tide>, zone: ZoneId, rise: Instant?, set: Instant?) {
        val now = Instant.now()
        val size = LocalSize.current
        val wide = size.width >= 250.dp
        val white = ColorProvider(Color.White)
        val day = (rise == null || set == null) || (now.isAfter(rise) && now.isBefore(set))
        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("tiempo://tides")).setPackage("es.altamirano.tiempo")
        Box(GlanceModifier.fillMaxSize().appWidgetBackground().cornerRadius(20.dp).background(ColorProvider(Color(0xFF3AA4DD)))
            .clickable(actionStartActivity(intent))) {
            val todays = tides.filter { it.at.atZone(zone).toLocalDate() == LocalDate.now(zone) }
            if (tides.size < 2 || todays.isEmpty()) {
                Column(GlanceModifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalAlignment = Alignment.CenterVertically) {
                    Text("Sin datos de mareas", style = TextStyle(color = white, fontSize = 11.sp))
                }
                return@Box
            }
            val curve = TideCurve(tides)
            val h = curve.height(now)
            val next = curve.next(now)
            val rising = next?.high ?: false
            val lo = todays.minOf { it.height }; val hi = todays.maxOf { it.height }
            val f = ((h - lo) / max(0.01, hi - lo)).coerceIn(0.0, 1.0)
            Image(ImageProvider(beach(context, size.width.value.roundToInt(), size.height.value.roundToInt(), f, day)), null,
                GlanceModifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
            // El texto de arriba cae en la arena salvo con la marea muy alta; el de abajo, casi siempre en el agua.
            val navy = 0xFF0B2236.toInt(); val whiteArgb = 0xFFFFFFFF.toInt()
            val topArgb = if (day && f < 0.62) navy else whiteArgb
            val bottomArgb = if (day && f < 0.08) navy else whiteArgb
            val topInk = ColorProvider(Color(topArgb)); val bottomInk = ColorProvider(Color(bottomArgb))
            val light = androidx.glance.text.FontFamily("sans-serif-light")
            Column(GlanceModifier.fillMaxSize().padding(horizontal = if (wide) 16.dp else 14.dp, vertical = 12.dp)) {
                Text(name, maxLines = 1, style = TextStyle(color = topInk, fontSize = 15.sp, fontWeight = FontWeight.Medium))
                Text((if (rising) "↗ " else "↘ ") + String.format(Locale.getDefault(), if (wide) "%.2f m" else "%.1f m", h),
                    maxLines = 1, style = TextStyle(color = topInk, fontSize = if (wide) 40.sp else 34.sp, fontFamily = light))
                fun line(t: Tide) = (if (t.high) "Pleamar " else "Bajamar ") + t.at.atZone(zone).format(hhmm) + String.format(Locale.getDefault(), " · %.1f m", t.height)
                val coming = tides.filter { it.at.isAfter(now) }.sortedBy { it.at }
                if (rise != null && set != null) Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Image(ImageProvider(WeatherIcons.glyph(context, "rise", topArgb, 14)), null, GlanceModifier.size(14.dp))
                    Text(" ${rise.atZone(zone).format(hhmm)}    ", style = TextStyle(color = topInk, fontSize = 14.sp))
                    Image(ImageProvider(WeatherIcons.glyph(context, "set", topArgb, 14)), null, GlanceModifier.size(14.dp))
                    Text(" ${set.atZone(zone).format(hhmm)}", style = TextStyle(color = topInk, fontSize = 14.sp))
                    // La siguiente a la próxima, arriba en la arena; abajo, en el agua, sólo una línea.
                    if (wide) coming.getOrNull(1)?.let {
                        Spacer(GlanceModifier.defaultWeight())
                        Text("Luego: " + line(it), maxLines = 1, style = TextStyle(color = topInk, fontSize = 13.sp))
                    }
                }
                Spacer(GlanceModifier.defaultWeight())
                if (wide) Row(GlanceModifier.fillMaxWidth()) {
                    Text(if (rising) "Subiendo" else "Bajando", style = TextStyle(color = bottomInk, fontSize = 15.sp, fontWeight = FontWeight.Bold), modifier = GlanceModifier.defaultWeight())
                    next?.let { Text(line(it), maxLines = 1, style = TextStyle(color = bottomInk, fontSize = 15.sp, fontWeight = FontWeight.Bold)) }
                } else next?.let { Text(line(it), maxLines = 1, style = TextStyle(color = bottomInk, fontSize = 14.sp, fontWeight = FontWeight.Bold)) }
            }
        }
    }

    /** Arena arriba, agua abajo con su orilla, y el sol o la luna en el cielo. */
    private fun beach(context: Context, wDp: Int, hDp: Int, f: Double, day: Boolean): Bitmap {
        val d = density(context)
        val w = max(1, (wDp * d).roundToInt()); val h = max(1, (hDp * d).roundToInt())
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(b)
        val sand = if (day) intArrayOf(0xFFECD589.toInt(), 0xFFD4B45F.toInt()) else intArrayOf(0xFF836A40.toInt(), 0xFF5B4728.toInt())
        val water = if (day) intArrayOf(0xFF3AA4DD.toInt(), 0xFF0C5A92.toInt()) else intArrayOf(0xFF1F6FA0.toInt(), 0xFF062A45.toInt())
        val foam = if (day) 0xFFEAF7FF.toInt() else 0xFFCFEAFB.toInt()
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = LinearGradient(0f, 0f, 0f, h.toFloat(), sand[0], sand[1], Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p); p.shader = null
        val level = h * (1 - (0.10 + 0.80 * f)).toFloat()
        p.color = sand[1]; p.alpha = 128
        c.drawRect(0f, level - 9 * d, w.toFloat(), level + 3 * d, p); p.alpha = 255
        val amp = max(0.5f, h * 0.005f)
        fun surface(x: Float, dy: Float) = level + dy + (sin(x / d / 24.0 + 0.6) * amp + sin(x / d / 14.4 - 0.72) * amp * 0.4).toFloat()
        val path = android.graphics.Path().apply {
            moveTo(0f, h.toFloat()); var x = 0f
            while (x <= w) { lineTo(x, surface(x, 0f)); x += 3 * d }
            lineTo(w.toFloat(), h.toFloat()); close()
        }
        p.shader = LinearGradient(0f, level, 0f, h.toFloat(), water[0], water[1], Shader.TileMode.CLAMP)
        c.drawPath(path, p); p.shader = null
        p.style = Paint.Style.STROKE; p.strokeCap = Paint.Cap.ROUND
        for ((dy, width, alpha) in listOf(Triple(5 * d, 1.2f * d, 56), Triple(0f, 2 * d, 230))) {
            p.color = foam; p.alpha = alpha; p.strokeWidth = width
            val line = android.graphics.Path().apply { moveTo(0f, surface(0f, dy)); var x = 0f; while (x <= w) { lineTo(x, surface(x, dy)); x += 3 * d } }
            c.drawPath(line, p)
        }
        p.style = Paint.Style.FILL; p.alpha = 255
        p.color = if (day) 0xFFFFC53D.toInt() else 0xFFEDF2FF.toInt()
        c.drawCircle(w - 24 * d, 24 * d, 14 * d, p)
        return b
    }
}

class TidesWidgetReceiver : GlanceAppWidgetReceiver() { override val glanceAppWidget: GlanceAppWidget = TidesWidget() }

