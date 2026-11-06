package es.altamirano.tiempo.data

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.CancellationSignal
import es.altamirano.tiempo.BuildConfig
import es.altamirano.tiempo.core.AemetAlert
import es.altamirano.tiempo.core.AirData
import es.altamirano.tiempo.core.DailyRoot
import es.altamirano.tiempo.core.HourlyRoot
import es.altamirano.tiempo.core.ObsRecord
import es.altamirano.tiempo.core.Places
import es.altamirano.tiempo.core.Ipma
import es.altamirano.tiempo.core.PortugalSource
import es.altamirano.tiempo.core.PortugalShape
import es.altamirano.tiempo.net.IpmaService
import es.altamirano.tiempo.core.TempScale
import es.altamirano.tiempo.core.timeZoneFor
import es.altamirano.tiempo.net.Aemet
import es.altamirano.tiempo.net.DiskCache
import es.altamirano.tiempo.net.OpenMeteo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.time.ZoneId
import kotlin.coroutines.resume

private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

/** Un sitio que se sigue: código de municipio de AEMET, nombre y coordenadas (`SavedLocation`). */
@Serializable
data class SavedLocation(
    val code: String,
    val name: String,
    val province: String? = null,
    val lat: Double,
    val lon: Double,
    val idema: String? = null,
    val stationName: String? = null,
    val stationKm: Double? = null,
    /** El municipio de AEMET de la ubicación del GPS (su `code` es el centinela). */
    val ine: String? = null,
    /** La estación la eligió Bruno a mano; si no, la más cercana («Automática»). */
    val stationPinned: Boolean = false,
) {
    /** El código con el que pedir la previsión. */
    val municipio: String get() = if (isCurrent) ine ?: "" else code
    val zone: ZoneId get() = Ipma.location(municipio)?.zone ?: timeZoneFor(municipio, lat, lon)
    /** Portugal: lo sirve el IPMA (o Open-Meteo), no AEMET. */
    val isPortuguese get() = Ipma.isPortuguese(municipio)
    val isCurrent get() = code == CURRENT

    companion object {
        const val CURRENT = "__current__"
        /** Las de partida, como en iOS. */
        val DEFAULTS = listOf(
            SavedLocation("40181", "La Granja", "Segovia", 40.9000, -4.0167, "2465"),
            SavedLocation("28079", "Madrid", "Madrid", 40.4168, -3.7038, "3126Y"),
            SavedLocation("33036", "Posada de Llanes", "Asturias", 43.4214, -4.7546, "1183X"),
        )
    }
}

/** La clave de AEMET es un JWT: su `exp` (segundos) dice cuándo caduca. */
fun aemetKeyExpiry(key: String): Long? = runCatching {
    val payload = String(java.util.Base64.getUrlDecoder().decode(key.split('.')[1].trimEnd('=')))
    Regex("\"exp\"\\s*:\\s*(\\d+)").find(payload)!!.groupValues[1].toLong()
}.getOrNull()

class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("ajustes", Context.MODE_PRIVATE)
    // La de Ajustes manda, salvo que la del APK caduque más tarde (una clave guardada en la app
    // tapaba para siempre la renovada en `local.properties`). Vacía en Ajustes = sin clave, a propósito.
    private val _key = MutableStateFlow(prefs.getString("aemet", null).let { saved ->
        val built = BuildConfig.AEMET_API_KEY
        when {
            saved == null -> built
            saved.isBlank() -> ""
            (aemetKeyExpiry(built) ?: 0) > (aemetKeyExpiry(saved) ?: 0) -> built
            else -> saved
        }
    })
    val aemetKey: StateFlow<String> = _key.asStateFlow()
    private val _scale = MutableStateFlow(runCatching { TempScale.valueOf(prefs.getString("escala", "")!!) }.getOrDefault(TempScale.DEFAULT))
    val tempScale: StateFlow<TempScale> = _scale.asStateFlow()

    fun setAemetKey(k: String) { _key.value = k.trim(); prefs.edit().putString("aemet", k.trim()).apply() }
    fun setScale(s: TempScale) { _scale.value = s; prefs.edit().putString("escala", s.name).apply() }

    /** La fuente elegida a mano para un sitio de Portugal (`IPMA.pinnedSource`), por código. */
    fun portugalSource(code: String): PortugalSource? = prefs.getString("fuente-$code", null)?.let { runCatching { PortugalSource.valueOf(it) }.getOrNull() }
    fun setPortugalSource(code: String, s: PortugalSource?) = prefs.edit().putString("fuente-$code", s?.name).apply()

    /** El puerto de las mareas (en iOS, `tide_station_id`; por omisión Llanes). */
    private val _port = MutableStateFlow(prefs.getString("puerto", "4")!!)
    val tidePort: StateFlow<String> = _port.asStateFlow()
    fun setTidePort(id: String) { _port.value = id; prefs.edit().putString("puerto", id).apply() }

    /** Ciudad y fondo de cada widget («Editar widget» en iOS). */
    fun widgetCity(id: Int): String? = prefs.getString("widget-$id", null)
    fun widgetTempBackground(id: Int, default: Boolean = true): Boolean = prefs.getBoolean("widget-$id-temp", default)
    fun setWidget(id: Int, code: String?, tempBackground: Boolean) =
        prefs.edit().putString("widget-$id", code).putBoolean("widget-$id-temp", tempBackground).apply()
    /** El puerto del widget de mareas («Editar widget → Estación» en iOS). */
    fun widgetPort(id: Int): String? = prefs.getString("widget-$id-puerto", null)
    fun setWidgetPort(id: Int, port: String) = prefs.edit().putString("widget-$id-puerto", port).apply()

}

/**
 * Las ubicaciones: la del GPS («Ubicación actual») y las que sigues. La del GPS se resuelve sin
 * red: municipio de AEMET más cercano, y dentro de él el núcleo del nomenclátor (el pueblo, no
 * el municipio: en todo el concejo de Llanes, Apple decía «Llanes»).
 */
class LocationStore(private val context: Context, private val places: () -> Places) {
    private val portugalShape by lazy { context.assets.open("Portugal.txt").use { PortugalShape(it) } }
    private fun portugal() = portugalShape
    private val file = File(context.filesDir, "ubicaciones.json")
    private val prefs = context.getSharedPreferences("ubicaciones", Context.MODE_PRIVATE)

    private val _saved = MutableStateFlow(runCatching { json.decodeFromString(ListSerializer(SavedLocation.serializer()), file.readText()) }
        .getOrDefault(SavedLocation.DEFAULTS))
    val saved: StateFlow<List<SavedLocation>> = _saved.asStateFlow()
    private val _current = MutableStateFlow(prefs.getString("actual", null)?.let { runCatching { json.decodeFromString(SavedLocation.serializer(), it) }.getOrNull() })
    val current: StateFlow<SavedLocation?> = _current.asStateFlow()

    private fun persist() {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(json.encodeToString(ListSerializer(SavedLocation.serializer()), _saved.value)); tmp.renameTo(file)
    }

    fun add(loc: SavedLocation) { if (_saved.value.none { it.code == loc.code && it.name == loc.name }) { _saved.update { it + loc }; persist() } }
    fun remove(loc: SavedLocation) { _saved.update { l -> l.filterNot { it.code == loc.code && it.name == loc.name } }; persist() }
    fun move(from: Int, to: Int) { _saved.update { l -> l.toMutableList().apply { add(to, removeAt(from)) } }; persist() }
    fun update(loc: SavedLocation) {
        if (loc.isCurrent) { _current.value = loc; prefs.edit().putString("actual", json.encodeToString(SavedLocation.serializer(), loc)).apply() }
        else { _saved.update { l -> l.map { if (it.code == loc.code && it.name == loc.name) loc else it } }; persist() }
    }

    fun all(): List<SavedLocation> = listOfNotNull(_current.value) + _saved.value
    fun byKey(key: String): SavedLocation? = all().firstOrNull { keyOf(it) == key }

    fun hasPermission() = context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /**
     * Dónde estás, con `LocationManager` y no con el de Google Play (GrapheneOS): primero lo
     * último conocido si es reciente, si no un fix nuevo (red o GPS), con 20 s de margen.
     */
    @SuppressLint("MissingPermission")
    suspend fun locate(): SavedLocation? {
        if (!hasPermission()) return null
        val lm = context.getSystemService(LocationManager::class.java)
        val providers = listOf(LocationManager.FUSED_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
            .filter { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }
        val recent = providers.mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .filter { System.currentTimeMillis() - it.time < 10 * 60_000 }.maxByOrNull { it.time }
        val fix = recent ?: withTimeoutOrNull(20_000) {
            suspendCancellableCoroutine<Location?> { cont ->
                val provider = providers.firstOrNull() ?: run { cont.resume(null); return@suspendCancellableCoroutine }
                val cancel = CancellationSignal()
                cont.invokeOnCancellation { cancel.cancel() }
                lm.getCurrentLocation(provider, cancel, context.mainExecutor) { cont.resume(it) }
            }
        } ?: providers.mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }.maxByOrNull { it.time } ?: return null
        val loc = withContext(Dispatchers.Default) { resolve(fix.latitude, fix.longitude) } ?: return null
        update(loc)
        return loc
    }

    /** Municipio y núcleo más cercanos al punto. */
    fun resolve(lat: Double, lon: Double): SavedLocation? {
        val p = places()
        // En Portugal no hay municipio de AEMET: antes se iba al de este lado de la frontera
        // (en Elvas salía Badajoz). Se mira primero si cae en un término de España, que la
        // frontera de Natural Earth es más gruesa.
        if (!p.inSpain(lat, lon) && portugal().portugal(lat, lon)) {
            val (pt, km) = Ipma.nearest(lat, lon) ?: return null
            val name = if (km <= 5) pt.name else "Cerca de ${pt.name}"
            return SavedLocation(SavedLocation.CURRENT, name, "Portugal", lat, lon, ine = pt.code)
        }
        val m = p.nearestMunicipio(lat, lon) ?: return null
        val name = p.nearestNucleo(lat, lon, m.code)?.name ?: m.name
        // La estación elegida a mano se conserva mientras sigas en el mismo municipio; la
        // automática se vuelve a buscar, que en Madrid la más cercana cambia de un barrio a otro.
        val keep = _current.value?.takeIf { it.ine == m.code && it.stationPinned }
        return SavedLocation(SavedLocation.CURRENT, name, m.name, lat, lon, keep?.idema, keep?.stationName, keep?.stationKm, m.code, keep != null)
    }

    companion object {
        fun keyOf(l: SavedLocation) = if (l.isCurrent) SavedLocation.CURRENT else "${l.code}|${l.name}"
    }
}

/** Todo lo que enseña la página de una ubicación (`CityWeather`). */
data class CityWeather(
    val daily: DailyRoot? = null,
    val hourly: HourlyRoot? = null,
    val obs: List<ObsRecord>? = null,
    val air: AirData? = null,
    val alerts: List<AemetAlert> = emptyList(),
    val source: String? = null,
    val loadedAt: Long? = null,
    val loading: Boolean = false,
    val error: String? = null,
) {
    val hasData get() = daily != null || hourly != null || obs != null
}

/**
 * El tiempo de cada ubicación, cada una con su copia: una ciudad ya visitada sale al instante y
 * nunca se ve el tiempo de la anterior bajo el nombre de la nueva. Con AEMET, si falla, Open-Meteo.
 */
class WeatherRepo(
    context: Context, private val scope: CoroutineScope, private val settings: Settings,
    private val stations: suspend () -> List<ObsRecord>,
    /** Para guardar en la ubicación la estación automática, con su nombre y distancia. */
    private val onStation: (SavedLocation) -> Unit = {},
) {
    val cache = DiskCache(File(context.cacheDir, "aemet"))
    val aemet = Aemet(cache) { settings.aemetKey.value }
    val ipma = IpmaService(cache)

    fun portugalSource(loc: SavedLocation) = Ipma.source(loc.municipio, loc.lat, loc.lon, settings.portugalSource(loc.municipio))
    /** Lo que se acredita de un sitio de Portugal: «IPMA · Porto» u «Open-Meteo». */
    fun portugalLabel(loc: SavedLocation) = when (portugalSource(loc)) {
        PortugalSource.IPMA -> Ipma.forecastPoint(loc.municipio, loc.lat, loc.lon)?.let { "IPMA · ${it.first.name}" } ?: "IPMA"
        PortugalSource.OPEN_METEO -> "Open-Meteo"
    }
    private val _pages = MutableStateFlow<Map<String, CityWeather>>(emptyMap())
    val pages: StateFlow<Map<String, CityWeather>> = _pages.asStateFlow()

    /** Lo que hay en disco, sin red: para pintar en cuanto aparece la página. */
    fun primed(loc: SavedLocation): CityWeather {
        _pages.value[LocationStore.keyOf(loc)]?.let { return it }
        val code = loc.municipio
        if (loc.isPortuguese) {
            val pt = Ipma.forecastPoint(code, loc.lat, loc.lon)?.first ?: return CityWeather()
            val c = ipma.cachedForecast(pt.globalId) ?: return CityWeather()
            return CityWeather(c.first.daily, c.first.hourly, source = "IPMA · ${pt.name}", loadedAt = System.currentTimeMillis() - c.second * 1000)
        }
        val d = aemet.cachedDaily(code); val h = aemet.cachedHourly(code)
        val o = loc.idema?.let { aemet.cachedObservation(it) }
        val oldest = listOfNotNull(d?.second, h?.second).maxOrNull()
        return CityWeather(d?.first, h?.first, o?.first, source = d?.first?.origen?.productor,
            loadedAt = oldest?.let { System.currentTimeMillis() - it * 1000 })
    }

    /** `force`: sin mirar la edad de la caché (el botón de actualizar). */
    suspend fun load(loc: SavedLocation, force: Boolean = false): CityWeather {
        val key = LocationStore.keyOf(loc)
        _pages.update { it + (key to primed(loc).copy(loading = true, error = null)) }
        val maxAge = if (force) null else 30 * 60L
        val code = loc.municipio
        var result = CityWeather()
        val airJob = scope.async { OpenMeteo.air(loc.lat, loc.lon) }
        if (loc.isPortuguese) {
            result = loadPortugal(loc, if (force) null else 30 * 60L) ?: primed(loc).copy(error = "No se pudo actualizar")
            result = result.copy(air = airJob.await(), loading = false)
            _pages.update { it + (key to result) }
            return result
        }
        // Como iOS: si AEMET no contesta se dice en la página, y no sólo se cambia de fuente.
        var aemetError: String? = null
        if (aemet.configured && code.isNotEmpty()) {
            try {
                val d = scope.async { runCatching { aemet.daily(code, maxAge) } }
                val h = scope.async { runCatching { aemet.hourly(code, maxAge) } }
                val idema = loc.idema ?: nearbyStations(loc.lat, loc.lon, 1).firstOrNull()?.let { (st, km) ->
                    onStation(withStation(loc, st, km, pinned = false)); st.idema
                }
                val o = idema?.let { runCatching { aemet.observation(it, if (force) null else 10 * 60L) }.getOrNull() }
                val a = runCatching { aemet.alerts(code, loc.lat, loc.lon, if (force) null else 30 * 60L) }.getOrDefault(emptyList())
                val dr = d.await(); val hr = h.await()
                val dd = dr.getOrNull(); val hh = hr.getOrNull()
                if (dd != null || hh != null) {
                    val ages = listOfNotNull(dd?.second, hh?.second)
                    // La caché devuelve su edad: si toda es más vieja de lo pedido, AEMET falló y se tiró de disco.
                    val stale = ages.all { it >= (maxAge ?: 1L) }
                    result = CityWeather(dd?.first, hh?.first, o?.first, alerts = a, source = "AEMET",
                        loadedAt = System.currentTimeMillis() - (ages.maxOrNull() ?: 0) * 1000,
                        error = if (stale) "AEMET sin respuesta · datos anteriores" else null)
                } else {
                    aemetError = (dr.exceptionOrNull() ?: hr.exceptionOrNull())?.message ?: "sin respuesta"
                    result = CityWeather(alerts = a)
                }
            } catch (e: Exception) { aemetError = e.message ?: "sin respuesta" }
        }
        if (!result.hasData) {
            // Sin clave, o AEMET no contesta: Open-Meteo, con la misma interfaz.
            val om = OpenMeteo.forecast(loc.lat, loc.lon)
            result = if (om != null) CityWeather(om.daily, om.hourly, om.obs, alerts = result.alerts, source = "Open-Meteo", loadedAt = System.currentTimeMillis(),
                error = aemetError?.let { "AEMET no disponible: " + it.removePrefix("AEMET: ") })
            else primed(loc).copy(error = "No se pudo actualizar")
        }
        result = result.copy(air = airJob.await(), loading = false)
        _pages.update { it + (key to result) }
        return result
    }

    /**
     * Portugal (`IPMAService.load`): los avisos son del IPMA siempre; la previsión, del IPMA si
     * su punto está cerca (o se eligió), y si no de Open-Meteo con el punto exacto.
     */
    private suspend fun loadPortugal(loc: SavedLocation, maxAge: Long?): CityWeather? {
        val point = Ipma.forecastPoint(loc.municipio, loc.lat, loc.lon)
        val alerts = scope.async { point?.let { ipma.alerts(it.first.warningArea, maxAge) } ?: emptyList() }
        return when (portugalSource(loc)) {
            PortugalSource.IPMA -> {
                val pt = point?.first ?: return null
                val obs = scope.async { runCatching { ipma.observation(loc.lat, loc.lon, if (maxAge == null) null else 10 * 60L) }.getOrNull() }
                val fc = runCatching { ipma.forecast(pt.globalId, maxAge) }.getOrNull() ?: return null
                val o = obs.await()
                o?.second?.let { (name, km) -> if (loc.stationName != name) onStation(loc.copy(stationName = name, stationKm = km)) }
                CityWeather(fc.daily, fc.hourly, o?.first?.takeIf { it.isNotEmpty() }, alerts = alerts.await(), source = "IPMA · ${pt.name}", loadedAt = System.currentTimeMillis())
            }
            PortugalSource.OPEN_METEO -> {
                val om = OpenMeteo.forecast(loc.lat, loc.lon) ?: return null
                CityWeather(om.daily, om.hourly, om.obs, alerts = alerts.await(), source = "Open-Meteo", loadedAt = System.currentTimeMillis())
            }
        }
    }

    /** Las estaciones de AEMET más cercanas, con su distancia (hace falta la clave). */
    // `observacion/convencional/todas` trae una fila por estación y por hora: una por estación
    // (la primera; repiten coordenadas), y sólo las que tienen indicativo y posición.
    suspend fun nearbyStations(lat: Double, lon: Double, n: Int = 5): List<Pair<ObsRecord, Double>> =
        runCatching { stations() }.getOrDefault(emptyList())
            .filter { it.idema != null && it.lat != null && it.lon != null }.distinctBy { it.idema }
            .map { it to Places.distanceKm(lat, lon, it.lat!!, it.lon!!) }.sortedBy { it.second }.take(n)

    companion object {
        fun withStation(loc: SavedLocation, st: ObsRecord, km: Double, pinned: Boolean) =
            loc.copy(idema = st.idema, stationName = stationName(st.ubi ?: st.idema ?: ""), stationKm = km, stationPinned = pinned)

        /**
         * `shortStationName` de iOS: «MADRID, EL GOLOSO» → «El Goloso»; «MADRID/RETIRO» → «Retiro»;
         * «ALMERÍA/AEROPUERTO» → «Almería, Aeropuerto» (un sitio genérico no dice nada solo);
         * «EVC_NIEMBRU-LLANES» → «Niembru-Llanes». El doble espacio se normaliza, no se corta,
         * porque AEMET también lo pone dentro de nombres («SANTA EULALIA  DEL CAMPO»).
         */
        fun stationName(ubi: String): String {
            var name = ubi.trim()
            if (name.uppercase().startsWith("EVC_")) name = name.drop(4)
            name = name.split(" ").filter { it.isNotEmpty() }.joinToString(" ")
            val parts = name.split(',', '/').map { it.trim() }.filter { it.isNotEmpty() }
            val tail = parts.lastOrNull() ?: return titleCased(name)
            if (parts.size < 2 || !isGenericSite(tail)) return titleCased(tail)
            return titleCased(parts[0]) + ", " + titleCased(tail)
        }

        private val genericSiteHeads = setOf("aeropuerto", "aerodromo", "base", "deposito", "depuradora", "embalse", "presa",
            "faro", "puerto", "parque", "instituto", "universidad", "facultad", "fac.", "jardin", "observatorio", "famet", "centro", "ciudad")
        private val linking = setOf("de", "del", "la", "las", "los", "el", "y", "e", "en", "al", "a", "da", "do", "dels")
        private val esES = java.util.Locale("es", "ES")

        private fun normalize(s: String) = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "").lowercase(esES).trim()

        private fun isGenericSite(tail: String) = normalize(tail.split(" ").first()) in genericSiteHeads

        /** Como `.capitalized` (mayúscula tras espacio, guion, paréntesis…) y los enlaces en minúscula salvo al principio. */
        private fun titleCased(s: String): String {
            val sb = StringBuilder(); var up = true
            for (c in s.lowercase(esES)) { sb.append(if (up && c.isLetter()) c.titlecase(esES) else c.toString()); up = !c.isLetterOrDigit() && c != '\'' }
            return sb.split(" ").mapIndexed { i, w -> if (i > 0 && normalize(w) in linking) w.lowercase(esES) else w }.joinToString(" ")
        }
    }
}
