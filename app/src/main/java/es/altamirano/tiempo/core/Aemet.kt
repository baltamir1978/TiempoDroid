package es.altamirano.tiempo.core

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

val AemetJson = Json { ignoreUnknownKeys = true; explicitNulls = false; coerceInputValues = true; isLenient = true }

/** AEMET manda `value` y `velocidad` unas veces como texto y otras como número: siempre texto. */
object FlexString : KSerializer<String?> {
    override val descriptor = PrimitiveSerialDescriptor("FlexString", PrimitiveKind.STRING)
    override fun deserialize(decoder: Decoder): String? {
        val el = (decoder as? JsonDecoder)?.decodeJsonElement() ?: return decoder.decodeString()
        val p = el as? JsonPrimitive ?: return null
        if (p.isString) return p.content
        return p.content.takeIf { it != "null" }?.let { c -> c.toDoubleOrNull()?.let { if (it % 1.0 == 0.0) it.toLong().toString() else c } ?: c }
    }
    override fun serialize(encoder: Encoder, value: String?) { if (value == null) encoder.encodeNull() else encoder.encodeString(value) }
}

@Serializable data class AemetEnvelope(val descripcion: String? = null, val estado: Int? = null, val datos: String? = null)

@Serializable data class PeriodValue(val periodo: String? = null, @Serializable(FlexString::class) val value: String? = null, val descripcion: String? = null)
@Serializable data class WindEntry(val periodo: String? = null, @Serializable(FlexString::class) val velocidad: String? = null, val direccion: String? = null)
@Serializable data class MinMax(val maxima: Double? = null, val minima: Double? = null)
@Serializable data class Origen(val productor: String? = null, val web: String? = null)

@Serializable
data class DailyDay(
    val fecha: String? = null,
    val temperatura: MinMax? = null,
    val sensTermica: MinMax? = null,
    val humedadRelativa: MinMax? = null,
    val estadoCielo: List<PeriodValue>? = null,
    val probPrecipitacion: List<PeriodValue>? = null,
    val rachaMax: List<PeriodValue>? = null,
    val viento: List<WindEntry>? = null,
    val cotaNieveProv: List<PeriodValue>? = null,
    val uvMax: Int? = null,
)

@Serializable data class DailyPred(val dia: List<DailyDay>? = null)
@Serializable data class DailyRoot(val nombre: String? = null, val elaborado: String? = null, val prediccion: DailyPred? = null, val origen: Origen? = null)

@Serializable
data class HourlyDay(
    val fecha: String? = null,
    val temperatura: List<PeriodValue>? = null,
    val estadoCielo: List<PeriodValue>? = null,
    val probPrecipitacion: List<PeriodValue>? = null,
)

@Serializable data class HourlyPred(val dia: List<HourlyDay>? = null)
@Serializable data class HourlyRoot(val nombre: String? = null, val elaborado: String? = null, val prediccion: HourlyPred? = null, val origen: Origen? = null)

@Serializable
data class ObsRecord(
    val idema: String? = null, val fint: String? = null, val ta: Double? = null, val hr: Double? = null,
    val prec: Double? = null, val vv: Double? = null, val vmax: Double? = null, val dv: Double? = null,
    val pres: Double? = null, val alt: Double? = null, val lat: Double? = null, val lon: Double? = null,
    val ubi: String? = null, val tamin: Double? = null, val tamax: Double? = null,
)

/** AEMET devuelve una lista con un elemento (a veces el objeto suelto). */
fun parseDaily(text: String): DailyRoot? = runCatching { AemetJson.decodeFromString<List<DailyRoot>>(text).firstOrNull() }.getOrNull()
    ?: runCatching { AemetJson.decodeFromString<DailyRoot>(text) }.getOrNull()
fun parseHourly(text: String): HourlyRoot? = runCatching { AemetJson.decodeFromString<List<HourlyRoot>>(text).firstOrNull() }.getOrNull()
    ?: runCatching { AemetJson.decodeFromString<HourlyRoot>(text) }.getOrNull()
fun parseObs(text: String): List<ObsRecord>? = runCatching { AemetJson.decodeFromString<List<ObsRecord>>(text) }.getOrNull()

// --- Lo que se enseña, sacado de los datos (como `AemetCityView` y `AemetSnapshotBuilder`) ----

/** Los días que quedan (AEMET incluye a veces el de ayer). */
fun DailyRoot?.upcomingDays(today: LocalDate = LocalDate.now()): List<DailyDay> =
    (this?.prediccion?.dia ?: emptyList()).filter { d -> d.fecha?.take(10)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }?.let { !it.isBefore(today) } ?: true }

data class HourEntry(val date: LocalDate, val hour: Int, val temp: Double, val sky: String?, val skyText: String?, val prob: Int?)

/** Las próximas 48 horas a partir de la hora en punto actual. */
fun HourlyRoot?.nextHours(now: LocalDateTime = LocalDateTime.now(), hours: Long = 48): List<HourEntry> {
    val floor = now.withMinute(0).withSecond(0).withNano(0)
    val cutoff = now.plusHours(hours)
    val out = mutableListOf<HourEntry>()
    for (day in this?.prediccion?.dia ?: emptyList()) {
        val date = day.fecha?.take(10)?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: continue
        for (t in day.temperatura ?: emptyList()) {
            val hour = t.periodo?.toIntOrNull() ?: continue
            val temp = t.value?.toDoubleOrNull() ?: continue
            val at = date.atTime(hour, 0)
            if (at.isBefore(floor) || at.isAfter(cutoff)) continue
            val sky = day.estadoCielo?.firstOrNull { it.periodo?.toIntOrNull() == hour }
            val prob = day.probPrecipitacion?.firstOrNull { p ->
                val per = p.periodo ?: return@firstOrNull false
                if (per.length != 4) return@firstOrNull false
                val s = per.take(2).toIntOrNull() ?: 0; val e = per.takeLast(2).toIntOrNull() ?: 0
                // «2002» va de 20 a 02 y cruza la medianoche. La app de iOS sólo comparaba
                // `hora >= s && hora < e`, así que de 20 a 02 nunca salía la lluvia.
                if (e > s) hour in s until e else hour >= s || hour < e
            }?.value?.toIntOrNull()
            out += HourEntry(date, hour, temp, sky?.value, sky?.descripcion, prob)
        }
    }
    return out.sortedWith(compareBy({ it.date }, { it.hour }))
}

/** El registro más reciente con temperatura (el último a veces la trae vacía). */
fun List<ObsRecord>?.latestTemp(): ObsRecord? = this?.lastOrNull { it.ta != null }

/**
 * El valor más reciente de un campo: AEMET publica cada sensor a su ritmo y el último registro
 * suele traer sólo la temperatura (pasaba con San Pablo de los Montes).
 */
fun <T> List<ObsRecord>?.latest(f: (ObsRecord) -> T?): T? = this?.lastOrNull { f(it) != null }?.let(f)

/** `fint` de AEMET viene en UTC sin zona: «2026-07-04T09:00:00». */
fun parseObsTime(s: String): Instant? =
    runCatching { LocalDateTime.parse(s.take(19)).toInstant(ZoneOffset.UTC) }.getOrNull()
        ?: runCatching { Instant.parse(s) }.getOrNull()

/** La temperatura de la estación sólo si es de hace 2 h o menos; si no, manda la previsión. */
fun freshObsTemp(r: ObsRecord?, now: Instant = Instant.now()): Double? {
    val ta = r?.ta ?: return null
    val at = r.fint?.let(::parseObsTime) ?: return null
    return if (now.epochSecond - at.epochSecond <= 2 * 3600) ta else null
}

/** El periodo que representa el día: 00-24, si no 12-24, si no 06-24. */
fun pickPeriod(arr: List<PeriodValue>?): PeriodValue? {
    if (arr == null) return null
    for (p in listOf("00-24", "12-24", "06-24")) arr.firstOrNull { it.periodo == p && !it.value.isNullOrEmpty() }?.let { return it }
    return arr.firstOrNull { !it.value.isNullOrEmpty() } ?: arr.firstOrNull()
}

fun maxOfDay(arr: List<PeriodValue>?): PeriodValue? = arr?.maxByOrNull { it.value?.toDoubleOrNull() ?: 0.0 }

/** El cielo de ahora: el de la hora actual en la previsión horaria. */
fun HourlyRoot?.skyNow(hour: Int): PeriodValue? {
    val day = this?.prediccion?.dia?.firstOrNull() ?: return null
    return day.estadoCielo?.firstOrNull { it.periodo?.toIntOrNull() == hour } ?: day.estadoCielo?.firstOrNull()
}

// --- Iconos del cielo: código de AEMET («11», «12n»…) → categoría ---------------------------

enum class Sky { CLEAR, CLEAR_NIGHT, PARTLY, PARTLY_NIGHT, CLOUDY, HIGH_CLOUDS, RAIN, SHOWER, THUNDER, THUNDER_RAIN, SNOW, LIGHT_SNOW, FOG, MIST, UNKNOWN }

fun skyOf(code: String?): Sky {
    if (code == null) return Sky.UNKNOWN
    val night = code.endsWith("n")
    return when (code.filter { it.isDigit() }.toIntOrNull() ?: 0) {
        11 -> if (night) Sky.CLEAR_NIGHT else Sky.CLEAR
        12, 13 -> if (night) Sky.PARTLY_NIGHT else Sky.PARTLY
        14, 15, 16 -> Sky.CLOUDY
        17 -> Sky.HIGH_CLOUDS
        in 23..26 -> Sky.RAIN
        in 33..36 -> Sky.SNOW
        in 43..46 -> Sky.SHOWER
        in 51..54 -> Sky.THUNDER
        in 61..64 -> Sky.THUNDER_RAIN
        in 71..74 -> Sky.LIGHT_SNOW
        81 -> Sky.FOG
        82, 83 -> Sky.MIST
        else -> Sky.UNKNOWN
    }
}

fun windDirection(deg: Double?): String {
    if (deg == null) return "—"
    val dirs = listOf("N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE", "S", "SSO", "SO", "OSO", "O", "ONO", "NO", "NNO")
    return dirs[(Math.round(deg / 22.5).toInt() % 16 + 16) % 16]
}

/** La zona horaria de un sitio: Canarias va una hora por detrás (por provincia o coordenadas). */
fun timeZoneFor(code: String, lat: Double, lon: Double): ZoneId {
    if (code.startsWith("35") || code.startsWith("38")) return ZoneId.of("Atlantic/Canary")
    if (lat in 27.3..29.6 && lon in -18.4..-13.2) return ZoneId.of("Atlantic/Canary")
    return ZoneId.of("Europe/Madrid")
}
