package es.altamirano.tiempo.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

/**
 * Open-Meteo (sin clave). Sin AEMET, su previsión se adapta a las mismas estructuras de AEMET
 * que pinta la app, como `OpenMeteoForecast.swift`: ninguna pantalla sabe de dónde vienen.
 */
@Serializable
data class OmForecast(val current: OmCurrent? = null, val hourly: OmHourly? = null, val daily: OmDaily? = null)

@Serializable
data class OmCurrent(
    val temperature_2m: Double? = null, val relative_humidity_2m: Double? = null, val weather_code: Int? = null,
    val wind_speed_10m: Double? = null, val wind_gusts_10m: Double? = null, val precipitation: Double? = null, val is_day: Int? = null,
)

@Serializable
data class OmHourly(
    val time: List<String>? = null, val temperature_2m: List<Double?>? = null, val weather_code: List<Int?>? = null,
    val precipitation_probability: List<Int?>? = null, val is_day: List<Int?>? = null,
)

@Serializable
data class OmDaily(
    val time: List<String>? = null, val weather_code: List<Int?>? = null,
    val temperature_2m_max: List<Double?>? = null, val temperature_2m_min: List<Double?>? = null,
    val apparent_temperature_max: List<Double?>? = null, val apparent_temperature_min: List<Double?>? = null,
    val precipitation_probability_max: List<Int?>? = null, val uv_index_max: List<Double?>? = null,
    val wind_speed_10m_max: List<Double?>? = null, val wind_gusts_10m_max: List<Double?>? = null,
    val wind_direction_10m_dominant: List<Double?>? = null,
)

data class Forecast(val daily: DailyRoot?, val hourly: HourlyRoot?, val obs: List<ObsRecord>?)

const val OPEN_METEO_FORECAST_QUERY =
    "current=temperature_2m,relative_humidity_2m,weather_code,wind_speed_10m,wind_gusts_10m,precipitation,is_day" +
        "&hourly=temperature_2m,weather_code,precipitation_probability,is_day" +
        "&daily=weather_code,temperature_2m_max,temperature_2m_min,apparent_temperature_max,apparent_temperature_min," +
        "precipitation_probability_max,uv_index_max,wind_speed_10m_max,wind_gusts_10m_max,wind_direction_10m_dominant" +
        "&wind_speed_unit=ms&timezone=auto&forecast_days=7"

private val OPEN_METEO = Origen("Open-Meteo (open-meteo.com)", "https://open-meteo.com")

fun adaptOpenMeteo(r: OmForecast, now: LocalDateTime = LocalDateTime.now(ZoneOffset.UTC)): Forecast {
    // Por horas: cada hora bajo su día.
    val byDay = linkedMapOf<String, Triple<MutableList<PeriodValue>, MutableList<PeriodValue>, MutableList<PeriodValue>>>()
    r.hourly?.time?.forEachIndexed { i, iso ->
        val day = iso.take(10)
        val hour = iso.drop(11).take(2).toIntOrNull() ?: 0
        val e = byDay.getOrPut(day) { Triple(mutableListOf(), mutableListOf(), mutableListOf()) }
        val hh = "%02d".format(hour)
        r.hourly.temperature_2m?.getOrNull(i)?.let { e.first += PeriodValue(hh, it.roundToInt().toString()) }
        val isDay = r.hourly.is_day?.getOrNull(i) != 0
        r.hourly.weather_code?.getOrNull(i)?.let { e.second += PeriodValue(hh, skyCode(it, isDay), skyText(it)) }
        r.hourly.precipitation_probability?.getOrNull(i)?.let { e.third += PeriodValue("%02d%02d".format(hour, hour + 1), it.toString()) }
    }
    val hourly = HourlyRoot(prediccion = HourlyPred(byDay.map { (d, e) -> HourlyDay(d, e.first, e.second, e.third) }), origen = OPEN_METEO)

    val d = r.daily
    val days = d?.time?.mapIndexed { i, day ->
        val wind = d.wind_speed_10m_max?.getOrNull(i)
        DailyDay(
            fecha = day,
            temperatura = MinMax(d.temperature_2m_max?.getOrNull(i), d.temperature_2m_min?.getOrNull(i)),
            sensTermica = MinMax(d.apparent_temperature_max?.getOrNull(i), d.apparent_temperature_min?.getOrNull(i)),
            estadoCielo = d.weather_code?.getOrNull(i)?.let { listOf(PeriodValue("00-24", skyCode(it, true), skyText(it))) },
            probPrecipitacion = d.precipitation_probability_max?.getOrNull(i)?.let { listOf(PeriodValue("00-24", it.toString())) },
            rachaMax = d.wind_gusts_10m_max?.getOrNull(i)?.let { listOf(PeriodValue("00-24", (it * 3.6).roundToInt().toString())) },
            viento = wind?.let { listOf(WindEntry("00-24", (it * 3.6).roundToInt().toString(), d.wind_direction_10m_dominant?.getOrNull(i)?.let(::compass8) ?: "")) },
            uvMax = d.uv_index_max?.getOrNull(i)?.roundToInt(),
        )
    } ?: emptyList()
    val daily = DailyRoot(prediccion = DailyPred(days), origen = OPEN_METEO)

    // Lo de ahora, como un registro de estación sintético (en UTC, como los de AEMET).
    val obs = r.current?.let {
        listOf(ObsRecord(fint = now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")), ta = it.temperature_2m,
            hr = it.relative_humidity_2m, vv = it.wind_speed_10m, vmax = it.wind_gusts_10m, prec = it.precipitation))
    }
    return Forecast(daily, hourly, obs)
}

/** Código WMO → código de cielo de AEMET (con «n» de noche). */
fun skyCode(wmo: Int, day: Boolean): String {
    val base = when (wmo) {
        0 -> 11; 1 -> 12; 2 -> 13; 3 -> 15; 45, 48 -> 81
        51, 53, 55, 56, 57 -> 43; 61, 63, 65, 66, 67 -> 24; 80, 81, 82 -> 44
        71, 73, 75, 77, 85, 86 -> 34; 95 -> 52; 96, 99 -> 62
        else -> 15
    }
    return if (day) "$base" else "${base}n"
}

fun skyText(wmo: Int): String = when (wmo) {
    0 -> "Despejado"; 1 -> "Poco nuboso"; 2 -> "Parcialmente nuboso"; 3 -> "Cubierto"; 45, 48 -> "Niebla"
    51, 53, 55, 56, 57 -> "Llovizna"; 61, 63, 65, 66, 67 -> "Lluvia"; 80, 81, 82 -> "Chubascos"
    71, 73, 75, 77 -> "Nieve"; 85, 86 -> "Chubascos de nieve"; 95, 96, 99 -> "Tormenta"
    else -> "—"
}

private fun compass8(deg: Double) = listOf("N", "NE", "E", "SE", "S", "SO", "O", "NO")[(Math.round(deg / 45).toInt() % 8 + 8) % 8]

// --- Aire, polen y UV ----------------------------------------------------------------------

data class Pollen(val name: String, val value: Double)
data class AirData(val aqi: Int?, val pm25: Double?, val pm10: Double?, val uvNow: Double?, val uvMax: Double?, val pollen: List<Pollen>) {
    val hasContent get() = aqi != null || uvNow != null || uvMax != null || pollen.isNotEmpty()
}

val POLLEN_LABELS = listOf("alder_pollen" to "Aliso", "birch_pollen" to "Abedul", "grass_pollen" to "Gramíneas",
    "mugwort_pollen" to "Artemisa", "olive_pollen" to "Olivo", "ragweed_pollen" to "Ambrosía")

fun airFrom(air: JsonObject?, uvCurrent: Double?, uvMax: Double?): AirData {
    val cur = air?.get("current") as? JsonObject
    fun v(k: String) = cur?.get(k)?.jsonPrimitive?.doubleOrNull
    return AirData(v("european_aqi")?.roundToInt(), v("pm2_5"), v("pm10"), uvCurrent, uvMax,
        POLLEN_LABELS.mapNotNull { (k, label) -> v(k)?.takeIf { it > 0 }?.let { Pollen(label, it) } })
}

/** Banda del índice europeo de calidad del aire: (texto, gravedad 0–5). */
fun aqiBand(aqi: Int): Pair<String, Int> = when {
    aqi < 20 -> "Buena" to 0; aqi < 40 -> "Razonable" to 1; aqi < 60 -> "Moderada" to 2
    aqi < 80 -> "Pobre" to 3; aqi < 100 -> "Muy pobre" to 4; else -> "Extrem. pobre" to 5
}

fun pollenLevel(v: Double): Pair<String, Int> = when {
    v < 10 -> "Bajo" to 0; v < 50 -> "Moderado" to 2; v < 150 -> "Alto" to 3; else -> "Muy alto" to 5
}
