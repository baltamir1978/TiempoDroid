package es.altamirano.tiempo.net

import es.altamirano.tiempo.core.AemetAlert
import es.altamirano.tiempo.core.AemetEnvelope
import es.altamirano.tiempo.core.AemetJson
import es.altamirano.tiempo.core.AirData
import es.altamirano.tiempo.core.Cap
import es.altamirano.tiempo.core.DailyRoot
import es.altamirano.tiempo.core.Forecast
import es.altamirano.tiempo.core.HourlyRoot
import es.altamirano.tiempo.core.OPEN_METEO_FORECAST_QUERY
import es.altamirano.tiempo.core.ObsRecord
import es.altamirano.tiempo.core.OmForecast
import es.altamirano.tiempo.core.POLLEN_LABELS
import es.altamirano.tiempo.core.Tide
import es.altamirano.tiempo.core.TideStation
import es.altamirano.tiempo.core.adaptOpenMeteo
import es.altamirano.tiempo.core.airFrom
import es.altamirano.tiempo.core.parseDaily
import es.altamirano.tiempo.core.parseHourly
import es.altamirano.tiempo.core.parseObs
import es.altamirano.tiempo.core.timeZoneFor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.TimeUnit

const val USER_AGENT = "TiempoES-Android/0.1 (+https://github.com/baltamir1978/TiempoDroid)"

class ServiceError(message: String) : IOException(message)

val http: OkHttpClient = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()

private suspend fun get(url: String, headers: Map<String, String> = emptyMap()): Pair<Int, ByteArray> = withContext(Dispatchers.IO) {
    val req = Request.Builder().url(url).header("User-Agent", USER_AGENT).apply { headers.forEach { (k, v) -> header(k, v) } }.build()
    http.newCall(req).execute().use { it.code to (it.body?.bytes() ?: ByteArray(0)) }
}

/** AEMET a veces manda el JSON en Latin-1 en vez de UTF-8. */
fun decodeText(bytes: ByteArray): String {
    val utf8 = Charsets.UTF_8.newDecoder()
    return runCatching { utf8.decode(java.nio.ByteBuffer.wrap(bytes)).toString() }.getOrElse { String(bytes, Charsets.ISO_8859_1) }
}

/** La última respuesta buena de cada consulta, en disco, con su edad. */
class DiskCache(dir: File) {
    private val dir = dir.apply { mkdirs() }
    private fun file(key: String) = File(dir, key.replace('/', '_') + ".bin")
    fun load(key: String): Pair<ByteArray, Long>? = file(key).takeIf { it.exists() }?.let { it.readBytes() to (System.currentTimeMillis() - it.lastModified()) / 1000 }
    fun save(key: String, data: ByteArray) {
        val tmp = File(dir, "$key.tmp".replace('/', '_'))
        tmp.writeBytes(data); tmp.renameTo(file(key))
    }
}

/**
 * AEMET OpenData (`AEMETService.swift`). Responde en dos pasos: la primera llamada da una URL de
 * `datos` que caduca. Limita las peticiones por minuto: se espacian 1,2 s, se reintenta a los
 * 2,5 y 5 s si dice 429, y cada consulta guarda en disco su última respuesta buena, a la que se
 * vuelve si la red o el límite fallan.
 */
class Aemet(private val cache: DiskCache, private val apiKey: () -> String) {
    private val base = "https://opendata.aemet.es/opendata/api"
    private val throttle = Mutex()
    private var lastCall = 0L

    val configured get() = apiKey().isNotBlank()

    /** Para Ajustes: null si AEMET acepta la clave; si no, lo que contesta («Tu apiKey ha superado…»). */
    suspend fun check(): String? = runCatching { dataUrl("prediccion/especifica/municipio/diaria/28079"); null }
        .getOrElse { it.message ?: "sin respuesta" }

    private suspend fun dataUrl(path: String): String {
        val key = apiKey().ifBlank { throw ServiceError("Sin clave de AEMET") }
        var last: Exception = ServiceError("AEMET: demasiadas peticiones, espera un momento.")
        for (wait in listOf(0L, 2500L, 5000L)) {
            if (wait > 0) delay(wait)
            throttle.withLock {
                val gap = lastCall + 1200 - System.currentTimeMillis()
                if (gap > 0) delay(gap)
                lastCall = System.currentTimeMillis()
            }
            val (code, body) = try { get("$base/$path", mapOf("api_key" to key)) } catch (e: IOException) { throw ServiceError(e.message ?: "sin red") }
            if (code == 429) continue
            val env = runCatching { AemetJson.decodeFromString(AemetEnvelope.serializer(), decodeText(body)) }.getOrNull()
                ?: throw ServiceError("AEMET: respuesta ilegible")
            if (env.estado == 429) continue
            env.datos?.let { return it }
            last = ServiceError("AEMET: ${env.descripcion ?: "sin datos"}")
            if (env.estado == 401 || env.estado == 404) break
        }
        throw last
    }

    /** Con `maxAge` (s) se usa la caché si es reciente; si la red falla, la caché aunque sea vieja. */
    private suspend fun <T> cached(key: String, path: String, maxAge: Long?, parse: (String) -> T?): Pair<T, Long>? {
        val c = cache.load(key)
        if (maxAge != null && c != null && c.second < maxAge) parse(decodeText(c.first))?.let { return it to c.second }
        return try {
            val (_, raw) = get(dataUrl(path))
            val text = decodeText(raw)
            val parsed = parse(text) ?: throw ServiceError("AEMET: datos ilegibles")
            cache.save(key, text.toByteArray())
            parsed to 0L
        } catch (e: Exception) {
            c?.let { parse(decodeText(it.first))?.let { p -> p to it.second } } ?: throw e
        }
    }

    suspend fun daily(code: String, maxAge: Long? = null) = cached("diaria_$code", "prediccion/especifica/municipio/diaria/$code", maxAge, ::parseDaily)
    suspend fun hourly(code: String, maxAge: Long? = null) = cached("horaria_$code", "prediccion/especifica/municipio/horaria/$code", maxAge, ::parseHourly)
    suspend fun observation(idema: String, maxAge: Long? = null) = cached("obs_$idema", "observacion/convencional/datos/estacion/$idema", maxAge, ::parseObs)

    fun cachedDaily(code: String) = cache.load("diaria_$code")?.let { (b, age) -> parseDaily(decodeText(b))?.let { it to age } }
    fun cachedHourly(code: String) = cache.load("horaria_$code")?.let { (b, age) -> parseHourly(decodeText(b))?.let { it to age } }
    fun cachedObservation(idema: String) = cache.load("obs_$idema")?.let { (b, age) -> parseObs(decodeText(b))?.let { it to age } }

    /** Estaciones que publican observaciones en tiempo real (una vez al día como mucho). */
    suspend fun stations(): List<ObsRecord> = cached("obs_stations", "observacion/convencional/todas", 24 * 3600L, ::parseObs)?.first
        ?.filter { it.idema != null && it.lat != null && it.lon != null }?.distinctBy { it.idema } ?: emptyList()

    /** Avisos de la comunidad que cubren el punto (el `.tar` CAP, binario: nunca como texto). */
    suspend fun alerts(code: String, lat: Double, lon: Double, maxAge: Long? = null): List<AemetAlert> {
        val area = Cap.area(code) ?: return emptyList()
        val key = "avisos_$area"
        val c = cache.load(key)
        if (maxAge != null && c != null && c.second < maxAge) return Cap.parse(c.first, lat, lon)
        return try {
            val (_, raw) = get(dataUrl("avisos_cap/ultimoelaborado/area/$area"))
            cache.save(key, raw)
            Cap.parse(raw, lat, lon)
        } catch (e: Exception) {
            c?.let { Cap.parse(it.first, lat, lon) } ?: throw e
        }
    }
}

/** Open-Meteo, sin clave: la previsión cuando no hay AEMET, y aire, polen y UV en todas partes. */
object OpenMeteo {
    suspend fun forecast(lat: Double, lon: Double): Forecast? = runCatching {
        val (code, body) = get("https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon&$OPEN_METEO_FORECAST_QUERY")
        if (code != 200) null else adaptOpenMeteo(AemetJson.decodeFromString(OmForecast.serializer(), String(body)))
    }.getOrNull()

    suspend fun air(lat: Double, lon: Double): AirData? = runCatching {
        val fields = (listOf("european_aqi", "pm2_5", "pm10") + POLLEN_LABELS.map { it.first }).joinToString(",")
        val air = get("https://air-quality-api.open-meteo.com/v1/air-quality?latitude=$lat&longitude=$lon&current=$fields&timezone=auto")
            .second.let { AemetJson.parseToJsonElement(String(it)) as? JsonObject }
        val uv = get("https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon&current=uv_index&daily=uv_index_max&timezone=auto&forecast_days=1")
            .second.let { AemetJson.parseToJsonElement(String(it)) as? JsonObject }
        val uvNow = (uv?.get("current") as? JsonObject)?.get("uv_index")?.jsonPrimitive?.doubleOrNull
        val uvMax = ((uv?.get("daily") as? JsonObject)?.get("uv_index_max") as? JsonArray)?.firstOrNull()?.jsonPrimitive?.doubleOrNull
        airFrom(air, uvNow, uvMax).takeIf { it.hasContent }
    }.getOrNull()
}

/**
 * Mareas del Instituto Hidrográfico de la Marina (`TidesService.swift`). **El IHM publica en
 * UTC sin decirlo** y su parámetro `date` elige un día UTC: cada hora se pasa a la del puerto y
 * se reagrupa por día local, pidiendo las dos o tres tablas UTC que lo solapan. Sin esto, en
 * verano la pleamar salía dos horas antes. Una tabla publicada no cambia: se guarda para siempre.
 */
class Tides(private val cache: DiskCache) {
    fun zone(s: TideStation): ZoneId = s.tz?.let(ZoneId::of) ?: timeZoneFor("", s.lat, s.lon)

    suspend fun days(station: TideStation, from: LocalDate, count: Int = 2): List<Pair<LocalDate, List<Tide>>> {
        val zone = zone(station)
        val start = from.atStartOfDay(zone).toInstant()
        val end = from.plusDays(count.toLong()).atStartOfDay(zone).toInstant()
        val all = mutableListOf<Tide>()
        var utcDay = start.atZone(ZoneOffset.UTC).toLocalDate()
        while (utcDay.atStartOfDay().toInstant(ZoneOffset.UTC).isBefore(end)) {
            all += table(station.id, utcDay)
            utcDay = utcDay.plusDays(1)
        }
        return (0 until count).map { i ->
            val day = from.plusDays(i.toLong())
            val s = day.atStartOfDay(zone).toInstant(); val e = day.plusDays(1).atStartOfDay(zone).toInstant()
            day to all.filter { !it.at.isBefore(s) && it.at.isBefore(e) }.distinctBy { it.at }.sortedBy { it.at }
        }
    }

    private suspend fun table(id: String, utcDay: LocalDate): List<Tide> {
        val date = utcDay.format(DateTimeFormatter.BASIC_ISO_DATE)
        val key = "marea_${id}_$date"
        val text = cache.load(key)?.first?.let { String(it) } ?: runCatching {
            val (code, body) = get("https://ideihm.covam.es/api-ihm/getmarea?request=gettide&id=$id&format=json&date=$date")
            String(body).takeIf { code == 200 }
        }.getOrNull() ?: return emptyList()
        val tides = parseIhm(text, utcDay)
        // Sólo se guarda una respuesta con mareas: un día vacío puede ser un mal momento del IHM.
        if (tides.isNotEmpty() && cache.load(key) == null) cache.save(key, text.toByteArray())
        return tides
    }

    companion object {
        fun parseIhm(text: String, utcDay: LocalDate): List<Tide> {
            val root = runCatching { AemetJson.parseToJsonElement(text) as JsonObject }.getOrNull() ?: return emptyList()
            val marea = ((root["mareas"] as? JsonObject)?.get("datos") as? JsonObject)?.get("marea") ?: return emptyList()
            val list = when (marea) { is JsonArray -> marea.filterIsInstance<JsonObject>(); is JsonObject -> listOf(marea); else -> emptyList() }
            return list.mapNotNull { o ->
                val hora = o["hora"]?.jsonPrimitive?.content ?: return@mapNotNull null
                val (h, m) = hora.split(":").mapNotNull { it.toIntOrNull() }.takeIf { it.size == 2 }?.let { it[0] to it[1] } ?: return@mapNotNull null
                val at = utcDay.atTime(h, m).toInstant(ZoneOffset.UTC)
                Tide(at, o["altura"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0,
                    o["tipo"]?.jsonPrimitive?.content?.lowercase(Locale.ROOT)?.startsWith("plea") == true)
            }
        }
    }
}


/**
 * El IPMA, sin clave (`IPMAService.swift`): previsión de las 35 localidades, observaciones de
 * unas 200 estaciones y avisos. Con la misma caché en disco que AEMET: lo reciente se sirve sin
 * pedir, y si la red falla, lo viejo.
 */
class IpmaService(private val cache: DiskCache) {
    private val openData = "https://api.ipma.pt/open-data"
    private val publicData = "https://api.ipma.pt/public-data"

    private suspend fun fetch(url: String, key: String, maxAge: Long?): String {
        val c = cache.load(key)
        if (maxAge != null && c != null && c.second < maxAge) return String(c.first)
        return try {
            val (code, body) = get(url)
            if (code !in 200..299) throw ServiceError("IPMA: error $code")
            cache.save(key, body); String(body)
        } catch (e: Exception) { c?.let { String(it.first) } ?: throw e }
    }

    suspend fun forecast(globalId: Int, maxAge: Long?) = es.altamirano.tiempo.core.Ipma.adapt(fetch("$publicData/forecast/aggregate/$globalId.json", "ipma_fc_$globalId", maxAge))
    fun cachedForecast(globalId: Int) = cache.load("ipma_fc_$globalId")?.let { (b, age) -> runCatching { es.altamirano.tiempo.core.Ipma.adapt(String(b)) to age }.getOrNull() }

    suspend fun stations() = es.altamirano.tiempo.core.Ipma.stations(fetch("$openData/observation/meteorology/stations/stations.json", "ipma_stations", 24 * 3600L))

    /** La estación más cercana con su distancia, y sus últimas horas. */
    suspend fun observation(lat: Double, lon: Double, maxAge: Long?): Pair<List<ObsRecord>, Pair<String, Double>?> {
        val near = runCatching { stations() }.getOrDefault(emptyList()).map { it to es.altamirano.tiempo.core.Places.distanceKm(lat, lon, it.lat, it.lon) }
            .minByOrNull { it.second } ?: return emptyList<ObsRecord>() to null
        val obs = runCatching { es.altamirano.tiempo.core.Ipma.observations(fetch("$openData/observation/meteorology/stations/observations.json", "ipma_obs", maxAge), near.first.id) }
            .getOrDefault(emptyList())
        return obs to (near.first.name to near.second)
    }

    suspend fun alerts(area: String, maxAge: Long?): List<AemetAlert> =
        runCatching { es.altamirano.tiempo.core.Ipma.alerts(fetch("$openData/forecast/warnings/warnings_www.json", "ipma_warnings", maxAge), area) }.getOrDefault(emptyList())
}
