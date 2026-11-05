package es.altamirano.tiempo

import android.app.Application
import android.content.Context
import es.altamirano.tiempo.core.Places
import es.altamirano.tiempo.data.LocationStore
import es.altamirano.tiempo.data.NetatmoRepo
import es.altamirano.tiempo.net.Netatmo
import es.altamirano.tiempo.data.Settings
import es.altamirano.tiempo.data.WeatherRepo
import es.altamirano.tiempo.net.DiskCache
import es.altamirano.tiempo.net.Tides
import es.altamirano.tiempo.widget.WidgetSnapshots
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

class TiempoApp : Application() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    lateinit var settings: Settings; private set
    lateinit var locations: LocationStore; private set
    lateinit var weather: WeatherRepo; private set
    lateinit var tides: Tides; private set
    lateinit var snapshots: WidgetSnapshots; private set
    lateinit var netatmo: NetatmoRepo; private set

    /** Municipios y nomenclátor: ~40.000 líneas, se cargan una vez y en segundo plano. */
    val places: Places by lazy { assets.open("Municipios.tsv").use { m -> assets.open("Nucleos.tsv").use { n -> assets.open("Recintos.bin").use { r -> assets.open("Distritos.tsv").use { d -> Places(m, n, r, d) } } } } }

    override fun onCreate() {
        super.onCreate()
        settings = Settings(this)
        locations = LocationStore(this) { places }
        snapshots = WidgetSnapshots(this)
        weather = WeatherRepo(this, scope, settings, { weather.aemet.stations() }) { locations.update(it) }
        tides = Tides(DiskCache(File(cacheDir, "mareas")))
        netatmo = NetatmoRepo(this, Netatmo(this))
        scope.launch(Dispatchers.Default) { places }   // precarga
        es.altamirano.tiempo.widget.RefreshWorker.schedule(this)
    }
}

val Context.app: TiempoApp get() = applicationContext as TiempoApp
