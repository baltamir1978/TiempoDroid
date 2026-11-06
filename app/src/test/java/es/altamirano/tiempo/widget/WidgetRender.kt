package es.altamirano.tiempo.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.widget.FrameLayout
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceId
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.compose
import androidx.glance.appwidget.provideContent
import androidx.test.core.app.ApplicationProvider
import es.altamirano.tiempo.core.NetatmoSnapshot
import es.altamirano.tiempo.core.TempScale
import es.altamirano.tiempo.core.Tide
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Pinta los widgets a PNG para verlos sin móvil: Glance → RemoteViews → vista → mapa de bits.
 * Sólo corre si se pide: `./gradlew testDebugUnitTest --tests '*WidgetRender*' -Dwidgets.out=<carpeta>`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = android.app.Application::class, qualifiers = "w411dp-h891dp-xxhdpi")
class WidgetRender {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val out = System.getProperty("widgets.out").orEmpty()

    private class Preview(val body: @Composable (Context) -> Unit) : GlanceAppWidget() {
        override val sizeMode = SizeMode.Single
        override suspend fun provideGlance(context: Context, id: GlanceId) = provideContent { body(context) }
    }

    private fun render(name: String, w: Int, h: Int, body: @Composable (Context) -> Unit) {
        val rv = runBlocking { Preview(body).compose(ctx, size = DpSize(w.dp, h.dp)) }
        val d = ctx.resources.displayMetrics.density
        val pw = (w * d).toInt(); val ph = (h * d).toInt()
        val parent = FrameLayout(ctx)
        val v = rv.apply(ctx, parent)
        parent.addView(v, FrameLayout.LayoutParams(pw, ph))
        parent.measure(View.MeasureSpec.makeMeasureSpec(pw, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(ph, View.MeasureSpec.EXACTLY))
        parent.layout(0, 0, pw, ph)
        // Un fondo de escritorio cualquiera, para ver bien las esquinas y la transparencia.
        val b = Bitmap.createBitmap(pw + 48, ph + 48, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        c.drawColor(0xFF5B6B7A.toInt())
        c.translate(24f, 24f)
        parent.draw(c)
        File(out).mkdirs()
        File(out, "$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private val snap = Snapshot(
        name = "Llanes", temp = 16, max = 17, min = 10, sky = "14", skyText = "Nuboso",
        hourly = listOf(HourPoint(12, 16, "14", 10), HourPoint(13, 17, "43", 40), HourPoint(14, 17, "43", 55), HourPoint(15, 16, "14", 20),
            HourPoint(16, 15, "12", 5), HourPoint(17, 14, "11", 0)),
        alertLevel = 1, alert = "Costeros", at = System.currentTimeMillis(), humidity = 78, windKmh = 14,
        daily = (0..4).map { i -> DayPoint(LocalDate.now().plusDays(i.toLong()).toString(), listOf(10, 9, 8, 11, 12)[i], listOf(17, 15, 14, 18, 19)[i],
            listOf("14", "43", "25", "12", "11")[i], listOf(20, 60, 90, 10, 0)[i]) },
    )

    @Test fun weather() {
        assumeTrue(out.isNotEmpty())
        for ((n, w, h) in listOf(Triple("small", 170, 170), Triple("medium", 360, 170), Triple("large", 360, 360))) {
            render("tiempo-$n", w, h) { WeatherWidget().Content(it, snap, false, TempScale.DEFAULT) }
            render("tiempo-$n-temp", w, h) { WeatherWidget().Content(it, snap, true, TempScale.DEFAULT) }
        }
        render("tiempo-medium-50", 360, 170) {
            androidx.compose.runtime.CompositionLocalProvider(LocalWidgetOpacity provides 0.5f) { WeatherWidget().Content(it, snap, true, TempScale.DEFAULT) }
        }
    }

    @Test fun icons() {
        assumeTrue(out.isNotEmpty())
        val codes = listOf("11", "11n", "12", "12n", "13", "14", "15", "16", "17", "23", "24", "26", "43", "43n", "51", "61", "33", "71", "81", "82")
        val size = 64; val d = ctx.resources.displayMetrics.density; val px = (size * d).toInt()
        val cols = 10; val rows = (codes.size + cols - 1) / cols + 1
        val b = Bitmap.createBitmap(cols * px, rows * px, Bitmap.Config.ARGB_8888)
        val c = Canvas(b); c.drawColor(0xFF2BBE92.toInt())
        codes.forEachIndexed { i, code -> c.drawBitmap(WeatherIcons.sky(ctx, code, size), (i % cols) * px.toFloat(), (i / cols) * px.toFloat(), null) }
        val y = (rows - 1) * px.toFloat()
        (0 until 7).forEach { i -> c.drawBitmap(WeatherIcons.moon(ctx, i / 8.0 + 0.0625, size), i * px.toFloat(), y, null) }
        listOf("drop", "wind", "home").forEachIndexed { i, k -> c.drawBitmap(WeatherIcons.glyph(ctx, k, -1, size / 2), (7 * px + i * px / 2).toFloat(), y, null) }
        listOf("rise", "set").forEachIndexed { i, k -> c.drawBitmap(WeatherIcons.glyph(ctx, k, -1, size / 2), (7 * px + i * px / 2).toFloat(), y + px / 2, null) }
        File(out).mkdirs(); File(out, "iconos.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun others() {
        assumeTrue(out.isNotEmpty())
        val n = NetatmoSnapshot("Casa", temperature = 15.2, humidity = 81.0, pressure = 1016.0, at = System.currentTimeMillis(),
            tempMinOut = 9.8, tempMaxOut = 17.1, rainToday = 2.4, tempIn = 21.4, humidityIn = 55.0, co2 = 820.0, noise = 38.0)
        for ((s, w, h) in listOf(Triple("small", 170, 170), Triple("medium", 360, 170), Triple("large", 360, 360)))
            render("netatmo-$s", w, h) { NetatmoWidget().Content(it, n, false, TempScale.DEFAULT) }
        val zone = ZoneId.of("Europe/Madrid")
        val base = LocalDate.now(zone).atStartOfDay(zone).toInstant()
        val tides = (-2..8).map { i -> Tide(base.plusSeconds(i * 22350L + 3 * 3600), if (i % 2 == 0) 4.1 else 0.9, i % 2 == 0) }
        for ((s, w, h) in listOf(Triple("small", 170, 170), Triple("medium", 360, 170)))
            render("sol-$s", w, h) { SunMoonWidget().Content(it, "Llanes", "08:12", "18:02", "14:40", "01:15", 0.22, 0.62, "Creciente iluminante",
                es.altamirano.tiempo.core.MoonPhase.FIRST_QUARTER, "8 nov · 14:30", "9 h 50 min") }
        // Marea baja de día (arena), alta de día (agua arriba) y de noche.
        render("mareas-alta", 170, 170) { TidesWidget().Content(it, "Llanes", tides, zone, base.plusSeconds(8 * 3600 - 86400 * 2), base.plusSeconds(18 * 3600 + 86400)) }
        for ((s, w, h) in listOf(Triple("small", 170, 170), Triple("medium", 360, 170)))
            render("mareas-$s", w, h) { TidesWidget().Content(it, "Llanes", tides, zone, base.plusSeconds(8 * 3600), base.plusSeconds(18 * 3600)) }
    }
}
