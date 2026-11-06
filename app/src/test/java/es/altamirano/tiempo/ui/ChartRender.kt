package es.altamirano.tiempo.ui

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import es.altamirano.tiempo.core.HourEntry
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.LocalDate
import kotlin.math.sin

/** Pinta la gráfica de temperatura a PNG, como `WidgetRender` con los widgets. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = android.app.Application::class, qualifiers = "w411dp-h891dp-xxhdpi")
class ChartRender {
    @get:Rule val rule = createAndroidComposeRule<androidx.activity.ComponentActivity>()
    private val out = System.getProperty("widgets.out").orEmpty()

    @Test fun tempChart() {
        assumeTrue(out.isNotEmpty())
        val d = LocalDate.now()
        val hours = (0 until 48).map { i ->
            val h = (12 + i) % 24
            HourEntry(d.plusDays(((12 + i) / 24).toLong()), h, 12 + 5 * sin((h - 9) / 24.0 * 2 * Math.PI), "14",
                null, listOf(0, 0, 10, 40, 70, 55, 20, 0)[(i / 3) % 8])
        }
        rule.setContent { TiempoTheme { Box(Modifier.width(380.dp).padding(8.dp)) { TempChart(hours) } } }
        rule.waitForIdle()
        // captureToImage se cuelga en Robolectric: se pinta la vista a mano.
        val v = rule.activity.window.decorView.findViewById<android.view.ViewGroup>(android.R.id.content).getChildAt(0)
        val b = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
        v.draw(android.graphics.Canvas(b))
        File(out).mkdirs(); File(out, "grafica.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
