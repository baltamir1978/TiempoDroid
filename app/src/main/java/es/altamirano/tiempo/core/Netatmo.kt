package es.altamirano.tiempo.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * La estación Netatmo (`NetatmoModels.swift`, `NetatmoSnapshotBuilder.swift`, `NetatmoCharts.swift`).
 * `dashboard_data` se lee como mapa de números: cada módulo trae sus campos.
 */
val NetatmoJson = Json { ignoreUnknownKeys = true; explicitNulls = false; isLenient = true }

data class NetatmoModule(
    val id: String, val type: String?, val name: String?, val reachable: Boolean?,
    val batteryPercent: Int?, val data: Map<String, Double>,
)

data class NetatmoDevice(
    val id: String, val stationName: String?, val reachable: Boolean?, val lastStatusStore: Long?,
    val city: String?, val altitude: Int?, val lat: Double?, val lon: Double?,
    val data: Map<String, Double>, val modules: List<NetatmoModule>,
) {
    /** El sensor exterior (`NAModule1`), el pluviómetro (`NAModule3`). */
    fun module(id: String?, type: String) = modules.firstOrNull { it.id == id } ?: modules.firstOrNull { it.type == type }

    /** «La Granja · 1192 m», como `AppConfiguration.makeStation`. */
    val label: String get() = city?.let { c -> altitude?.let { "$c · $it m" } ?: c } ?: stationName ?: id
}

class NetatmoApiError(val code: Int?, message: String) : Exception(message)

private fun numbers(o: JsonObject?): Map<String, Double> =
    o?.mapNotNull { (k, v) -> (v as? kotlinx.serialization.json.JsonPrimitive)?.doubleOrNull?.let { k to it } }?.toMap() ?: emptyMap()

private fun JsonObject.str(k: String) = (this[k] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it !is JsonNull }?.content

/** `getstationsdata`; si Netatmo contesta con error, se lanza con su código y su texto. */
fun parseStationsData(text: String): List<NetatmoDevice> {
    val root = NetatmoJson.parseToJsonElement(text) as JsonObject
    (root["error"] as? JsonObject)?.let { e ->
        throw NetatmoApiError(e["code"]?.jsonPrimitive?.intOrNull, e.str("message") ?: "Error de Netatmo")
    }
    val devices = ((root["body"] as? JsonObject)?.get("devices") as? JsonArray) ?: return emptyList()
    return devices.filterIsInstance<JsonObject>().map { d ->
        val place = d["place"] as? JsonObject
        val loc = place?.get("location") as? JsonArray
        NetatmoDevice(
            id = d.str("_id") ?: "",
            stationName = d.str("station_name") ?: d.str("home_name"),
            reachable = d["reachable"]?.jsonPrimitive?.booleanOrNull,
            lastStatusStore = d["last_status_store"]?.jsonPrimitive?.longOrNull,
            city = place?.str("city"), altitude = place?.get("altitude")?.jsonPrimitive?.intOrNull,
            lon = loc?.getOrNull(0)?.jsonPrimitive?.doubleOrNull, lat = loc?.getOrNull(1)?.jsonPrimitive?.doubleOrNull,
            data = numbers(d["dashboard_data"] as? JsonObject),
            modules = ((d["modules"] as? JsonArray) ?: JsonArray(emptyList())).filterIsInstance<JsonObject>().map { m ->
                NetatmoModule(m.str("_id") ?: "", m.str("type"), m.str("module_name"),
                    m["reachable"]?.jsonPrimitive?.booleanOrNull, m["battery_percent"]?.jsonPrimitive?.intOrNull,
                    numbers(m["dashboard_data"] as? JsonObject))
            },
        )
    }
}

/** `getmeasure` con `optimize=false`: `{"body": {"<ts>": [v, …]}}`. Ordenado por fecha. */
fun parseMeasure(text: String): List<Pair<Long, Double>> {
    val root = NetatmoJson.parseToJsonElement(text) as JsonObject
    (root["error"] as? JsonObject)?.let { e ->
        throw NetatmoApiError(e["code"]?.jsonPrimitive?.intOrNull, e.str("message") ?: "Error de Netatmo")
    }
    val body = root["body"] as? JsonObject ?: return emptyList()
    return body.mapNotNull { (k, v) ->
        val ts = k.toLongOrNull() ?: return@mapNotNull null
        val value = ((v as? JsonArray)?.firstOrNull() as? kotlinx.serialization.json.JsonPrimitive)?.doubleOrNull ?: return@mapNotNull null
        ts to value
    }.sortedBy { it.first }
}

/**
 * Lo último de la estación, para la pestaña Actual y el widget (`NetatmoSnapshot`). Una sola
 * receta: en iOS, cuando la pestaña y el refresco en segundo plano lo rellenaban distinto, el
 * widget y la app no coincidían.
 */
@Serializable
data class NetatmoSnapshot(
    val stationName: String,
    val temperature: Double? = null, val humidity: Double? = null, val pressure: Double? = null,
    val at: Long = 0,
    val tempMinOut: Double? = null, val tempMaxOut: Double? = null,
    val rain: Double? = null, val rainToday: Double? = null,
    val tempIn: Double? = null, val humidityIn: Double? = null, val co2: Double? = null, val noise: Double? = null,
    val measuredAt: Long? = null,
) {
    /** Las bandas de CO₂ de Netatmo: ≤1000 bien, ≤1600 regular, más, mal. */
    val co2Level: Int? get() = co2?.let { if (it <= 1000) 0 else if (it <= 1600) 1 else 2 }

    companion object {
        fun make(station: NetatmoDevice, exterior: NetatmoModule?, rain: NetatmoModule?, name: String, now: Long = System.currentTimeMillis()) =
            NetatmoSnapshot(
                stationName = name,
                temperature = exterior?.data?.get("Temperature"), humidity = exterior?.data?.get("Humidity"),
                // La presión la mide la base, pero va con lo de fuera.
                pressure = station.data["Pressure"] ?: exterior?.data?.get("Pressure"),
                at = now,
                tempMinOut = exterior?.data?.get("min_temp"), tempMaxOut = exterior?.data?.get("max_temp"),
                rain = rain?.data?.get("sum_rain_1") ?: rain?.data?.get("Rain"), rainToday = rain?.data?.get("sum_rain_24"),
                tempIn = station.data["Temperature"], humidityIn = station.data["Humidity"],
                co2 = station.data["CO2"], noise = station.data["Noise"],
                measuredAt = station.lastStatusStore?.times(1000),
            )
    }
}

// --- Gráficas -------------------------------------------------------------------------------

/** El grupo de una gráfica. La presión la mide la base: su gráfica es «interior». */
enum class ChartSource { EXTERIOR, INTERIOR }

enum class ChartPeriod(val label: String, val scale: String, val days: Int) {
    WEEK("Semana", "3hours", 7), MONTH("Mes", "1day", 30), QUARTER("3 Meses", "1day", 90), YEAR("Año", "1week", 365)
}

data class ChartData(
    val points: List<Pair<Long, Double>>,
    val labels: List<String>,
    val min: Double?, val max: Double?, val minLabel: String?, val maxLabel: String?,
    val total: Double?, val rainyDays: Int?,
    val ticks: List<Pair<Int, String>>,
) {
    val values get() = points.map { it.second }
    val average get() = values.takeIf { it.isNotEmpty() }?.average()
}

object NetatmoCharts {
    fun unit(type: String) = when (type) { "Temperature" -> "°C"; "Humidity" -> "%"; "Pressure" -> "hPa"; "CO2" -> "ppm"; "Noise" -> "dB"; "Rain" -> "L/m²"; else -> "" }

    /** La temperatura, la humedad, la presión y la lluvia: «Máxima»; el CO₂ y el ruido: «Máximo». */
    fun feminine(type: String) = type in setOf("Temperature", "Humidity", "Pressure", "Rain")

    fun title(source: ChartSource, type: String) = when (source to type) {
        ChartSource.EXTERIOR to "Temperature" -> "Temperatura ext."
        ChartSource.EXTERIOR to "Humidity" -> "Humedad ext."
        ChartSource.EXTERIOR to "Rain" -> "Lluvia"
        ChartSource.INTERIOR to "Temperature" -> "Temperatura"
        ChartSource.INTERIOR to "CO2" -> "CO₂"
        ChartSource.INTERIOR to "Humidity" -> "Humedad"
        ChartSource.INTERIOR to "Noise" -> "Ruido"
        ChartSource.INTERIOR to "Pressure" -> "Presión"
        else -> if (source == ChartSource.EXTERIOR) "Exterior" else "Interior"
    }

    fun build(points: List<Pair<Long, Double>>, period: ChartPeriod, isRain: Boolean, zone: ZoneId, locale: Locale = Locale("es", "ES")): ChartData? {
        if (points.isEmpty()) return null
        val fmt = DateTimeFormatter.ofPattern(if (period.scale == "3hours") "d/M HH'h'" else "d MMM", locale)
        val labels = points.map { Instant.ofEpochSecond(it.first).atZone(zone).format(fmt) }
        val values = points.map { it.second }
        val ticks = ticks(points.map { it.first }, period, zone, locale)
        return if (isRain) {
            val max = values.max(); val maxIdx = values.indexOf(max)
            ChartData(points, labels, null, max, null, labels[maxIdx], values.sum(), values.count { it > 0 }, ticks)
        } else {
            val min = values.min(); val max = values.max()
            ChartData(points, labels, min, max, labels[values.indexOf(min)], labels[values.indexOf(max)], null, null, ticks)
        }
    }

    /**
     * La semana: un día de la semana bajo el centro de cada día, salvo los que tienen menos de
     * la mitad de sus puntos. Lo demás, unas cinco fechas contando hacia atrás desde hoy (el
     * mes, semanal, para que caigan en el mismo día de la semana que hoy).
     */
    fun ticks(stamps: List<Long>, period: ChartPeriod, zone: ZoneId, locale: Locale): List<Pair<Int, String>> {
        if (stamps.isEmpty()) return emptyList()
        val dates = stamps.map { Instant.ofEpochSecond(it).atZone(zone) }
        if (period.scale == "3hours") {
            val f = DateTimeFormatter.ofPattern("EEE", locale)
            val days = mutableListOf<MutableList<Int>>()
            dates.forEachIndexed { i, d ->
                if (days.isNotEmpty() && dates[days.last().last()].toLocalDate() == d.toLocalDate()) days.last() += i else days += mutableListOf(i)
            }
            return days.filter { it.size >= 4 }.map { day -> val mid = day[day.size / 2]; mid to dates[mid].format(f) }
        }
        val f = DateTimeFormatter.ofPattern("d MMM", locale)
        val step = if (dates.size <= 31) 7 else Math.ceil(dates.size / 5.0).toInt()
        return (dates.size - 1 downTo 0 step step).reversed().map { it to dates[it].format(f) }
    }
}
