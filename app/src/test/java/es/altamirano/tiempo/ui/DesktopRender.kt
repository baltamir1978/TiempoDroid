package es.altamirano.tiempo.ui

import android.graphics.Bitmap
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Brightness4
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import es.altamirano.tiempo.core.parseDaily
import es.altamirano.tiempo.core.parseHourly
import es.altamirano.tiempo.data.CityWeather
import es.altamirano.tiempo.data.SavedLocation
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** La página de una ciudad en una ventana de escritorio, a PNG (como `ChartRender`). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = android.app.Application::class, qualifiers = "w1280dp-h800dp-mdpi")
class DesktopRender {
    @get:Rule val rule = createAndroidComposeRule<androidx.activity.ComponentActivity>()
    private val out = System.getProperty("widgets.out").orEmpty()
    private fun res(name: String) = javaClass.classLoader!!.getResource(name)!!.readText()

    @Test fun cityPageWide() {
        assumeTrue(out.isNotEmpty())
        val w = CityWeather(daily = parseDaily(res("aemet-diaria.json")), hourly = parseHourly(res("aemet-horaria.json")), source = "AEMET")
        val loc = SavedLocation(code = "33036", name = "Llanes", lat = 43.42, lon = -4.75)
        rule.setContent {
            TiempoTheme {
                AdaptiveNavigation(listOf(
                    NavItem(true, {}, { Icon(Icons.Filled.Cloud, null) }, { Text("AEMET") }),
                    NavItem(false, {}, { Icon(Icons.Filled.Brightness4, null) }, { Text("Sol·Luna") }),
                    NavItem(false, {}, { Icon(Icons.Filled.Settings, null) }, { Text("Ajustes") }))) { CityPage(loc, w) }
            }
        }
        rule.waitForIdle()
        val v = rule.activity.window.decorView.findViewById<android.view.ViewGroup>(android.R.id.content).getChildAt(0)
        val b = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
        v.draw(android.graphics.Canvas(b))
        File(out).mkdirs(); File(out, "escritorio.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
