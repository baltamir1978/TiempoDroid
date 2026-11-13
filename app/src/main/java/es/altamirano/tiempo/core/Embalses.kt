package es.altamirano.tiempo.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import kotlin.math.abs

/**
 * Los embalses del **Boletín Hidrológico** de MITECO (lo mismo que enseña embalses.net, que lo
 * saca de ahí). MITECO lo publica cada martes en dos servicios de ArcGIS públicos, sin clave:
 *
 * - `Embalses_Mapa`: la última semana, un punto por embalse (los peninsulares de más de 5 hm³).
 * - `Embalses_Total`: todas las semanas desde 1988, embalse a embalse (`EMBALSE_ID` común).
 *
 * El año pasado y la media de diez años no vienen hechos: se sacan del histórico con la semana
 * más próxima de cada año, que es como cuadran al dato con embalses.net.
 */
object Embalses {
    private const val BASE = "https://services-eu1.arcgis.com/RvnYk1PBUJ9rrAuT/arcgis/rest/services"

    /** La semana actual de todos, con su punto en grados. */
    const val CURRENT_URL = "$BASE/Embalses_Mapa/FeatureServer/0/query?where=1%3D1&outSR=4326&f=json" +
        "&outFields=EMBALSE_ID,embalse_nombre,agua_total,agua_actual,ambito_nombre,fecha,Variacion_Reserva,electrico_flag,Uso"

    /** Las semanas de un embalse desde `from` (once años bastan para la media de diez: ~575 filas, por debajo del tope de 2.000). */
    fun historyUrl(id: Int, from: LocalDate): String =
        "$BASE/Embalses_Total/FeatureServer/0/query?where=EMBALSE_ID%3D$id%20AND%20fecha%3E%3Ddate%20%27$from%27" +
            "&outFields=agua_actual,agua_total,fecha&orderByFields=fecha&f=json"

    private val json = Json { ignoreUnknownKeys = true }

    private fun JsonObject.num(k: String) = (this[k] as? JsonPrimitive)?.doubleOrNull
    private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun JsonObject.date(k: String) = (this[k] as? JsonPrimitive)?.longOrNull?.let { Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() }

    /** ArcGIS contesta 200 con `{"error": …}` cuando algo va mal. */
    private fun features(text: String): List<JsonObject> {
        val root = json.parseToJsonElement(text).jsonObject
        root["error"]?.let { throw IllegalStateException("MITECO: " + ((it as? JsonObject)?.str("message") ?: "error")) }
        return root["features"]?.jsonArray?.map { it.jsonObject } ?: emptyList()
    }

    fun parseCurrent(text: String): List<Reservoir> = features(text).mapNotNull { f ->
        val a = f["attributes"]?.jsonObject ?: return@mapNotNull null
        val g = f["geometry"] as? JsonObject ?: return@mapNotNull null
        Reservoir(
            id = (a["EMBALSE_ID"] as? JsonPrimitive)?.intOrNull ?: return@mapNotNull null,
            name = a.str("embalse_nombre")?.trim() ?: return@mapNotNull null,
            basin = a.str("ambito_nombre")?.trim() ?: "",
            capacity = a.num("agua_total") ?: return@mapNotNull null,
            current = a.num("agua_actual") ?: return@mapNotNull null,
            weekChange = a.num("Variacion_Reserva") ?: 0.0,
            date = a.date("fecha") ?: return@mapNotNull null,
            lat = g.num("y") ?: return@mapNotNull null,
            lon = g.num("x") ?: return@mapNotNull null,
            hydro = (a["electrico_flag"] as? JsonPrimitive)?.intOrNull == 1,
        )
    }.filter { it.capacity > 0 }.sortedBy { it.name }

    fun parseHistory(text: String): List<Week> = features(text).mapNotNull { f ->
        val a = f["attributes"]?.jsonObject ?: return@mapNotNull null
        Week(a.date("fecha") ?: return@mapNotNull null, a.num("agua_actual") ?: return@mapNotNull null, a.num("agua_total") ?: 0.0)
    }.sortedBy { it.date }

    /** La semana del histórico más próxima a `day` (a ±4 días: los boletines van de martes a martes). */
    fun near(history: List<Week>, day: LocalDate): Week? =
        history.minByOrNull { abs(ChronoUnit.DAYS.between(it.date, day)) }?.takeIf { abs(ChronoUnit.DAYS.between(it.date, day)) <= 4 }

    /** La misma semana hace `years` años. */
    fun sameWeek(history: List<Week>, day: LocalDate, years: Int = 1): Week? = near(history, day.minusYears(years.toLong()))

    /** La media de esa semana en los diez años anteriores (con los que haya, si faltan). */
    fun tenYearMean(history: List<Week>, day: LocalDate): Double? =
        (1..10).mapNotNull { sameWeek(history, day, it)?.hm3 }.takeIf { it.isNotEmpty() }?.average()

    /** El año hidrológico empieza el 1 de octubre. */
    fun hydroYearStart(day: LocalDate): LocalDate = LocalDate.of(if (day.monthValue >= 10) day.year else day.year - 1, 10, 1)

    /**
     * Las tres curvas del «detalle anual»: este año hidrológico, el anterior y la media de diez,
     * en días desde el 1 de octubre.
     */
    fun yearCurves(history: List<Week>, today: LocalDate): YearCurves {
        val start = hydroYearStart(today)
        fun offset(d: LocalDate, s: LocalDate) = ChronoUnit.DAYS.between(s, d).toInt()
        val thisYear = history.filter { !it.date.isBefore(start) }.map { offset(it.date, start) to it.hm3 }
        val prevStart = start.minusYears(1)
        val lastYear = history.filter { !it.date.isBefore(prevStart) && it.date.isBefore(start) }.map { offset(it.date, prevStart) to it.hm3 }
        val mean = (0..364 step 7).mapNotNull { d ->
            (1..10).mapNotNull { y -> near(history, start.minusYears(y.toLong()).plusDays(d.toLong()))?.hm3 }
                .takeIf { it.size >= 5 }?.let { d to it.average() }
        }
        return YearCurves(start, thisYear, lastYear, mean)
    }

    /** Totales de un grupo (España, una cuenca). */
    fun total(list: List<Reservoir>): Total =
        Total(list.sumOf { it.current }, list.sumOf { it.capacity }, list.sumOf { it.weekChange })

    /** Color del % lleno, en tramos (como las escalas de temperatura): rojo, naranja, amarillo, verde y azul. */
    fun color(percent: Double): Int = when {
        percent < 20 -> 0xFFD92E2E.toInt()
        percent < 40 -> 0xFFF28C1A.toInt()
        percent < 60 -> 0xFFF2CC1A.toInt()
        percent < 80 -> 0xFF34B36B.toInt()
        else -> 0xFF1F78D1.toInt()
    }
    val LEGEND = listOf("< 20 %" to 10.0, "20–40" to 30.0, "40–60" to 50.0, "60–80" to 70.0, "> 80 %" to 90.0)
}

data class Reservoir(
    val id: Int, val name: String, val basin: String, val capacity: Double, val current: Double,
    val weekChange: Double, val date: LocalDate, val lat: Double, val lon: Double, val hydro: Boolean,
) {
    val percent: Double get() = 100 * current / capacity
}

data class Week(val date: LocalDate, val hm3: Double, val capacity: Double)

data class YearCurves(val start: LocalDate, val thisYear: List<Pair<Int, Double>>, val lastYear: List<Pair<Int, Double>>, val mean: List<Pair<Int, Double>>)

data class Total(val current: Double, val capacity: Double, val weekChange: Double) {
    val percent: Double get() = if (capacity > 0) 100 * current / capacity else 0.0
}
