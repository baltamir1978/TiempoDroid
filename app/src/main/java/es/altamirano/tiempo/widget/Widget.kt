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
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
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

/** Lo que enseña el widget de una ubicación (como `AemetSnapshot` en el App Group de iOS). */
@Serializable
data class Snapshot(
    val name: String, val temp: Int?, val max: Int?, val min: Int?, val sky: String?, val skyText: String?,
    val hours: List<Triple<Int, String?, Int>> = emptyList(), val alertLevel: Int = 0, val alert: String? = null, val at: Long = 0,
)

private val json = Json { ignoreUnknownKeys = true }

class WidgetSnapshots(private val context: Context) {
    private val prefs = context.getSharedPreferences("widgets", Context.MODE_PRIVATE)
    private var nudge: Job? = null

    fun get(key: String): Snapshot? = prefs.getString(key, null)?.let { runCatching { json.decodeFromString<Snapshot>(it) }.getOrNull() }

    fun save(loc: SavedLocation, w: CityWeather) {
        val now = LocalDateTime.now(loc.zone)
        val today = w.daily.upcomingDays(now.toLocalDate()).firstOrNull()
        val hours = w.hourly.nextHours(now)
        val sky = w.hourly.skyNow(now.hour)
        val temp = freshObsTemp(w.obs.latestTemp()) ?: hours.firstOrNull()?.temp ?: w.obs.latestTemp()?.ta
        val top = w.alerts.maxByOrNull { it.level }
        val s = Snapshot(loc.name, temp?.roundToInt(), today?.temperatura?.maxima?.roundToInt(), today?.temperatura?.minima?.roundToInt(),
            sky?.value, sky?.descripcion, hours.drop(1).take(5).map { Triple(it.hour, it.sky, it.temp.roundToInt()) },
            top?.level?.ordinal ?: 0, top?.phenomenon, System.currentTimeMillis())
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

class WeatherWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Responsive(setOf(DpSize(110.dp, 110.dp), DpSize(250.dp, 110.dp)))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val app = context.app
        val widgetId = GlanceAppWidgetManager(context).getAppWidgetId(id)
        val key = app.settings.widgetCity(widgetId) ?: LocationStore.keyOf(app.locations.all().first())
        val snap = app.snapshots.get(key)
        val tempBg = app.settings.widgetTempBackground(widgetId)
        val scale = app.settings.tempScale.value
        provideContent { Content(snap, tempBg, scale) }
    }

    @androidx.compose.runtime.Composable
    private fun Content(s: Snapshot?, tempBg: Boolean, scale: es.altamirano.tiempo.core.TempScale) {
        val wide = LocalSize.current.width >= 250.dp
        val temp = s?.temp?.toDouble()
        val (top, bottom) = if (tempBg && temp != null) TempPalette.gradient(temp, scale) else (0x37D094 to 0x0B6F53)
        val dark = tempBg && temp != null && TempPalette.prefersDarkInk(temp, scale)
        val ink = ColorProvider(if (dark) Color(0xFF0B2236) else Color.White)
        val soft = ColorProvider(if (dark) Color(0xCC0B2236) else Color(0xCCFFFFFF))
        WidgetFrame(top, bottom, "aemet") {
            if (s == null) { Text("Abre la app para cargar el tiempo", GlanceModifier.padding(12.dp), style = TextStyle(color = ink, fontSize = 12.sp)); return@WidgetFrame }
            Row(GlanceModifier.fillMaxSize().padding(12.dp)) {
                Column(GlanceModifier.defaultWeight()) {
                    Text(s.name, maxLines = 1, style = TextStyle(color = ink, fontWeight = FontWeight.Bold, fontSize = 13.sp))
                    Text("${s.temp ?: "—"}°", style = TextStyle(color = ink, fontSize = 38.sp))
                    Text(skyEmoji(s.sky) + " " + (s.skyText ?: ""), maxLines = 1, style = TextStyle(color = soft, fontSize = 12.sp))
                    Text("↑${s.max ?: "—"}°  ↓${s.min ?: "—"}°", style = TextStyle(color = soft, fontSize = 12.sp))
                    if (s.alert != null) Text("⚠ ${s.alert}", maxLines = 1, style = TextStyle(color = ink, fontSize = 11.sp, fontWeight = FontWeight.Bold))
                }
                if (wide) Row(GlanceModifier.defaultWeight(), verticalAlignment = Alignment.CenterVertically) {
                    s.hours.forEach { (h, sky, t) ->
                        Column(GlanceModifier.defaultWeight(), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("%02d".format(h), style = TextStyle(color = soft, fontSize = 11.sp))
                            Text(skyEmoji(sky), style = TextStyle(fontSize = 16.sp))
                            Text("$t°", style = TextStyle(color = ink, fontSize = 13.sp, fontWeight = FontWeight.Bold))
                        }
                    }
                }
            }
        }
    }
}

class WeatherWidgetReceiver : GlanceAppWidgetReceiver() { override val glanceAppWidget: GlanceAppWidget = WeatherWidget() }

/** Avisa a los cuatro widgets (agrupado por `WidgetSnapshots.requestUpdate`). */
suspend fun updateAllWidgets(context: Context) {
    runCatching { WeatherWidget().updateAll(context) }
    runCatching { NetatmoWidget().updateAll(context) }
    runCatching { SunMoonWidget().updateAll(context) }
    runCatching { TidesWidget().updateAll(context) }
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
        fun done() {
            app.scope.launch { updateAllWidgets(applicationContext) }
            setResult(Activity.RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id))
            finish()
        }
        setContent {
            TiempoTheme {
                var tempBg by remember { mutableStateOf(app.settings.widgetTempBackground(id, default = kind == "weather")) }
                Surface(Modifier.fillMaxSize()) {
                    androidx.compose.foundation.lazy.LazyColumn(Modifier.padding(16.dp)) {
                        item {
                            Text(when (kind) { "netatmo" -> "Widget de Netatmo"; "sunmoon" -> "Widget de Sol·Luna"; "tides" -> "Widget de Mareas"; else -> "Widget de Tiempo" },
                                style = MaterialTheme.typography.titleLarge)
                        }
                        if (kind == "weather" || kind == "netatmo") item {
                            Row(Modifier.padding(vertical = 12.dp)) {
                                Text("Color según la temperatura", Modifier.weight(1f).padding(top = 12.dp))
                                Switch(tempBg, { tempBg = it })
                            }
                        }
                        when (kind) {
                            "netatmo" -> item {
                                androidx.compose.material3.Button(onClick = { app.settings.setWidget(id, null, tempBg); done() }) { Text("Listo") }
                            }
                            "tides" -> {
                                val here = locs.firstOrNull()
                                val near = here?.let { es.altamirano.tiempo.core.portsByDistance(it.lat, it.lon).take(4).map { it.first } } ?: emptyList()
                                item { Text("Puerto", style = MaterialTheme.typography.titleSmall, color = Green) }
                                items(near + es.altamirano.tiempo.core.IHM_STATIONS.filter { it !in near }) { p ->
                                    ListItem(headlineContent = { Text(p.name) },
                                        supportingContent = if (p in near && here != null) { { Text("a ${es.altamirano.tiempo.ui.km(es.altamirano.tiempo.core.Places.distanceKm(here.lat, here.lon, p.lat, p.lon))} km de ${here.name}") } } else null,
                                        modifier = Modifier.clickable { app.settings.setWidgetPort(id, p.id); done() })
                                }
                            }
                            else -> {
                                item { Text("Ciudad", style = MaterialTheme.typography.titleSmall, color = Green) }
                                items(locs) { l ->
                                    ListItem(headlineContent = { Text(if (l.isCurrent) "📍 ${l.name}" else l.name) },
                                        modifier = Modifier.clickable {
                                            app.settings.setWidget(id, LocationStore.keyOf(l), tempBg)
                                            if (kind == "weather") app.scope.launch { runCatching { app.weather.load(l) }.getOrNull()?.let { app.snapshots.save(l, it) } }
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
