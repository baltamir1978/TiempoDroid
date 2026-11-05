package es.altamirano.tiempo.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.InputStream
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

/**
 * IPMA, el servicio meteorológico de Portugal, sin clave (`IPMAService.swift`). Como Open-Meteo,
 * todo se adapta a las mismas estructuras de AEMET: ninguna pantalla sabe de qué país es.
 *
 * Sólo publica previsión para 35 localidades (capitales de distrito e islas); lejos de ellas,
 * Open-Meteo con el punto exacto describe mejor el sitio. Los avisos son siempre del IPMA. Las
 * mareas no las publica: siguen siendo del IHM.
 */
data class IpmaLocation(val globalId: Int, val name: String, val lat: Double, val lon: Double, val warningArea: String) {
    /** El código con el que se sigue: el prefijo `pt-` es lo que marca un sitio como del IPMA. */
    val code get() = Ipma.PREFIX + globalId

    /** La primera cifra es la región: 1 continente, 2 Madeira, 3 Azores (una hora menos). */
    val zone: ZoneId get() = ZoneId.of(when (globalId.toString().first()) { '3' -> "Atlantic/Azores"; '2' -> "Atlantic/Madeira"; else -> "Europe/Lisbon" })
}

enum class PortugalSource { IPMA, OPEN_METEO }

object Ipma {
    const val PREFIX = "pt-"
    /** Más lejos que esto de la capital, su previsión ya describe otro sitio: Open-Meteo. */
    const val NEARBY_KM = 25.0

    fun isPortuguese(code: String) = code.startsWith(PREFIX)
    fun location(code: String) = if (!isPortuguese(code)) null else code.removePrefix(PREFIX).toIntOrNull()?.let { id -> LOCATIONS.firstOrNull { it.globalId == id } }
    fun nearest(lat: Double, lon: Double) = LOCATIONS.map { it to Places.distanceKm(lat, lon, it.lat, it.lon) }.minByOrNull { it.second }

    /** El punto de previsión del IPMA de un sitio y a cuánto está (siempre medido: Nazaré va con Leiria a 28 km). */
    fun forecastPoint(code: String, lat: Double, lon: Double): Pair<IpmaLocation, Double>? =
        location(code)?.let { it to Places.distanceKm(it.lat, it.lon, lat, lon) } ?: nearest(lat, lon)

    /** La fuente: la que eligió Bruno; si no, el IPMA si su punto está cerca y Open-Meteo si no. */
    fun source(code: String, lat: Double, lon: Double, pinned: PortugalSource?): PortugalSource =
        pinned ?: forecastPoint(code, lat, lon)?.let { if (it.second <= NEARBY_KM) PortugalSource.IPMA else PortugalSource.OPEN_METEO } ?: PortugalSource.OPEN_METEO

    /** El catálogo abierto del IPMA (`open-data/distrits-islands.json`), dentro de la app para buscar sin red. */
    val LOCATIONS = listOf(
        IpmaLocation(3430100, "Angra do Heroísmo", 38.6700, -27.2200, "ACE"), IpmaLocation(1010500, "Aveiro", 40.6413, -8.6535, "AVR"),
        IpmaLocation(1020500, "Beja", 38.0200, -7.8700, "BJA"), IpmaLocation(1030300, "Braga", 41.5475, -8.4227, "BRG"),
        IpmaLocation(1040200, "Bragança", 41.8076, -6.7606, "BGC"), IpmaLocation(1050200, "Castelo Branco", 39.8217, -7.4957, "CBO"),
        IpmaLocation(1060300, "Coimbra", 40.2081, -8.4194, "CBR"), IpmaLocation(1080500, "Faro", 37.0146, -7.9331, "FAR"),
        IpmaLocation(2310300, "Funchal", 32.6485, -16.9084, "MCS"), IpmaLocation(1090700, "Guarda", 40.5379, -7.2647, "GDA"),
        IpmaLocation(1030800, "Guimarães", 41.4434, -8.2938, "BRG"), IpmaLocation(3470100, "Horta", 38.5363, -28.6315, "ACE"),
        IpmaLocation(1100900, "Leiria", 39.7473, -8.8069, "LRA"), IpmaLocation(1110600, "Lisboa", 38.7660, -9.1286, "LSB"),
        IpmaLocation(1080800, "Loulé", 37.1397, -8.0202, "FAR"), IpmaLocation(3460200, "Madalena", 38.5325, -28.5237, "ACE"),
        IpmaLocation(1090821, "Penhas Douradas", 40.4075, -7.5665, "GDA"), IpmaLocation(3420300, "Ponta Delgada", 37.7415, -25.6677, "AOR"),
        IpmaLocation(1121400, "Portalegre", 39.2900, -7.4200, "PTG"), IpmaLocation(1081100, "Portimão", 37.1500, -8.5200, "FAR"),
        IpmaLocation(1131200, "Porto", 41.1580, -8.6294, "PTO"), IpmaLocation(2320100, "Porto Santo", 33.0700, -16.3400, "MPS"),
        IpmaLocation(1081505, "Sagres", 37.0168, -8.9403, "FAR"), IpmaLocation(3440100, "Santa Cruz da Graciosa", 39.0800, -28.0000, "ACE"),
        IpmaLocation(3480200, "Santa Cruz das Flores", 39.4500, -31.1300, "AOC"), IpmaLocation(1141600, "Santarém", 39.2000, -8.7400, "STM"),
        IpmaLocation(1151200, "Setúbal", 38.5246, -8.8856, "STB"), IpmaLocation(1151300, "Sines", 37.9560, -8.8643, "STB"),
        IpmaLocation(3450200, "Velas", 38.6842, -28.2133, "ACE"), IpmaLocation(1160900, "Viana do Castelo", 41.6952, -8.8365, "VCT"),
        IpmaLocation(1171400, "Vila Real", 41.3053, -7.7440, "VRL"), IpmaLocation(3490100, "Vila do Corvo", 39.6700, -31.1200, "AOC"),
        IpmaLocation(3410100, "Vila do Porto", 36.9563, -25.1409, "AOR"), IpmaLocation(1182300, "Viseu", 40.6585, -7.9120, "VIS"),
        IpmaLocation(1070500, "Évora", 38.5701, -7.9104, "EVR"),
    )

    // --- Adaptar el IPMA a las estructuras de AEMET ------------------------------------------

    private val ORIGEN = Origen("IPMA — Instituto Português do Mar e da Atmosfera", "https://www.ipma.pt")

    private fun JsonObject.s(k: String) = (this[k] as? JsonPrimitive)?.takeIf { it.isString || it.content != "null" }?.content
    private fun JsonObject.d(k: String) = s(k)?.toDoubleOrNull()

    /** `forecast/aggregate/{id}.json`: `idPeriodo` 1 son las horas, 24 los días. */
    fun adapt(text: String): Forecast {
        val rows = (AemetJson.parseToJsonElement(text) as JsonArray).filterIsInstance<JsonObject>()
        val byDay = sortedMapOf<String, Triple<MutableList<PeriodValue>, MutableList<PeriodValue>, MutableList<PeriodValue>>>()
        val windByDay = mutableMapOf<String, Pair<Double, String>>()
        for (r in rows.filter { it["idPeriodo"]?.jsonPrimitive?.intOrNull == 1 }) {
            val stamp = r.s("dataPrev")?.takeIf { it.length >= 13 } ?: continue
            val day = stamp.take(10); val hour = stamp.drop(11).take(2).toIntOrNull() ?: 0
            val e = byDay.getOrPut(day) { Triple(mutableListOf(), mutableListOf(), mutableListOf()) }
            val hh = "%02d".format(hour)
            r.d("tMed")?.let { e.first += PeriodValue(hh, it.roundToInt().toString()) }
            r["idTipoTempo"]?.jsonPrimitive?.intOrNull?.takeIf { it > 0 }?.let { e.second += PeriodValue(hh, skyCode(it, hour in 7..20), skyText(it)) }
            r.d("probabilidadePrecipita")?.takeIf { it >= 0 }?.let { e.third += PeriodValue("%02d%02d".format(hour, (hour + 1) % 24), it.roundToInt().toString()) }
            r.d("ffVento")?.takeIf { it >= 0 }?.let { ff -> if ((windByDay[day]?.first ?: -1.0) < ff) windByDay[day] = ff to (r.s("ddVento") ?: "") }
        }
        val hourly = HourlyRoot(prediccion = HourlyPred(byDay.map { (d, e) -> HourlyDay(d, e.first, e.second, e.third) }), origen = ORIGEN)
        val days = rows.filter { it["idPeriodo"]?.jsonPrimitive?.intOrNull == 24 }.sortedBy { it.s("dataPrev") ?: "" }.map { r ->
            val day = (r.s("dataPrev") ?: "").take(10)
            val sky = r["idTipoTempo"]?.jsonPrimitive?.intOrNull?.takeIf { it > 0 }
            // El viento más fuerte de las horas; si no hay, la clase del día (1–4) en el centro de su tramo.
            val wind = windByDay[day]?.let { (kmh, dir) -> WindEntry("00-24", kmh.roundToInt().toString(), dir.ifEmpty { r.s("ddVento") ?: "" }) }
                ?: r["classWindSpeed"]?.jsonPrimitive?.intOrNull?.let { WindEntry("00-24", when (it) { 1 -> "10"; 2 -> "25"; 3 -> "45"; else -> "65" }, r.s("ddVento") ?: "") }
            DailyDay(
                fecha = day, temperatura = MinMax(r.d("tMax"), r.d("tMin")),
                estadoCielo = sky?.let { listOf(PeriodValue("00-24", skyCode(it, true), skyText(it))) },
                probPrecipitacion = r.d("probabilidadePrecipita")?.takeIf { it >= 0 }?.let { listOf(PeriodValue("00-24", it.roundToInt().toString())) },
                viento = wind?.let { listOf(it) }, uvMax = r.d("iUv")?.roundToInt(),
            )
        }
        return Forecast(DailyRoot(prediccion = DailyPred(days), origen = ORIGEN), hourly, null)
    }

    /** `idTipoTempo` del IPMA → código de cielo de AEMET (con «n» de noche). */
    fun skyCode(type: Int, day: Boolean): String {
        val base = when (type) {
            1 -> 11; 2, 3 -> 13; 4, 5, 25, 27 -> 15; 6, 8, 11 -> 44; 7 -> 43; 9, 12, 14 -> 24; 10, 13, 15 -> 43
            16, 17, 26 -> 81; 18, 28 -> 34; 19, 20, 23 -> 52; 21 -> 62; 22 -> 11; 24 -> 15; 29, 30 -> 34; else -> 15
        }
        return if (day) "$base" else "${base}n"
    }

    fun skyText(type: Int): String = when (type) {
        1 -> "Despejado"; 2 -> "Poco nuboso"; 3, 25 -> "Parcialmente nuboso"; 4, 5, 27 -> "Cubierto"; 6, 9 -> "Chubascos"
        7, 10, 13 -> "Lluvia débil"; 8, 11, 14 -> "Lluvia fuerte"; 12 -> "Periodos de lluvia"; 15 -> "Llovizna"; 16 -> "Neblina"
        17, 26 -> "Niebla"; 18 -> "Nieve"; 19 -> "Tormenta"; 20, 23 -> "Chubascos con tormenta"; 21 -> "Granizo"; 22 -> "Helada"
        24 -> "Nubosidad de evolución"; 28 -> "Chubascos de nieve"; 29, 30 -> "Lluvia y nieve"; else -> "—"
    }

    // --- Avisos ---------------------------------------------------------------------------

    private val LISBON = ZoneId.of("Europe/Lisbon")
    /** Las horas del IPMA son de Lisboa sin zona («2026-08-08T15:00», a veces sin segundos). */
    fun parseStamp(s: String?): Instant? = s?.let { runCatching { LocalDateTime.parse(if (it.length == 16) "$it:00" else it.take(19)).atZone(LISBON).toInstant() }.getOrNull() }

    private fun phenomenon(n: String?) = when (n) {
        "Agitação Marítima" -> "Estado de la mar"; "Nevoeiro" -> "Nieblas"; "Neve" -> "Nevadas"; "Precipitação" -> "Lluvias"
        "Tempo Frio" -> "Temperaturas mínimas"; "Tempo Quente" -> "Temperaturas máximas"; "Trovoada" -> "Tormentas"; "Vento" -> "Vientos"
        else -> n ?: "Aviso"
    }

    /**
     * `warnings_www.json`: el IPMA da todas las áreas y tipos cada día, también en verde. Se
     * queda el nivel más alto de cada fenómeno, sin los ya terminados.
     */
    fun alerts(text: String, area: String, now: Instant = Instant.now()): List<AemetAlert> {
        val rows = runCatching { (AemetJson.parseToJsonElement(text) as JsonArray).filterIsInstance<JsonObject>() }.getOrDefault(emptyList())
        val best = mutableMapOf<String, AemetAlert>()
        for (r in rows.filter { it.s("idAreaAviso") == area }) {
            val level = when (r.s("awarenessLevelID")?.lowercase()) { "yellow" -> AlertLevel.AMARILLO; "orange" -> AlertLevel.NARANJA; "red" -> AlertLevel.ROJO; else -> AlertLevel.VERDE }
            if (level == AlertLevel.VERDE) continue
            val expires = parseStamp(r.s("endTime"))
            if (expires?.isBefore(now) == true) continue
            val ph = phenomenon(r.s("awarenessTypeName"))
            val a = AemetAlert(ph, area, r.s("text")?.takeIf { it.isNotEmpty() } ?: ph, level,
                LOCATIONS.firstOrNull { it.warningArea == area }?.name ?: area, parseStamp(r.s("startTime")), expires)
            if ((best[ph]?.level ?: AlertLevel.VERDE) < level) best[ph] = a
        }
        return best.values.sortedByDescending { it.level }
    }

    // --- Observaciones --------------------------------------------------------------------

    data class Station(val id: Int, val name: String, val lat: Double, val lon: Double)

    fun stations(text: String): List<Station> = runCatching {
        (AemetJson.parseToJsonElement(text) as JsonArray).filterIsInstance<JsonObject>().mapNotNull { f ->
            val c = ((f["geometry"] as? JsonObject)?.get("coordinates") as? JsonArray) ?: return@mapNotNull null
            val p = f["properties"] as? JsonObject ?: return@mapNotNull null
            Station(p["idEstacao"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null, p.s("localEstacao") ?: "",
                c.getOrNull(1)?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null, c.getOrNull(0)?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null)
        }
    }.getOrDefault(emptyList())

    /**
     * Las últimas horas de una estación, como registros de AEMET, de la más vieja a la más nueva
     * y con `fint` en UTC, que es como se leen los de AEMET. El IPMA marca lo que falta con -99.
     */
    fun observations(text: String, stationId: Int): List<ObsRecord> {
        val root = runCatching { AemetJson.parseToJsonElement(text) as JsonObject }.getOrNull() ?: return emptyList()
        val utc = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")
        return root.keys.sorted().mapNotNull { hour ->
            val row = (root[hour] as? JsonObject)?.get(stationId.toString()) as? JsonObject ?: return@mapNotNull null
            fun v(k: String) = (row[k] as? JsonPrimitive)?.doubleOrNull?.takeIf { it > -90 }
            val at = parseStamp(hour) ?: return@mapNotNull null
            ObsRecord(idema = "ipma-$stationId", fint = at.atOffset(ZoneOffset.UTC).format(utc), ta = v("temperatura"), hr = v("humidade"),
                vv = v("intensidadeVento"), prec = v("precAcumulada"), pres = v("pressao"))
        }
    }
}

/** El contorno de Portugal (`Tools/build_portugal.py`): ¿cae aquí el punto? Par-impar. */
class PortugalShape(input: InputStream) {
    private val rings: List<DoubleArray> = input.bufferedReader().readLines().filter { it.isNotBlank() }.map { l ->
        l.trim().split(' ').flatMap { p -> p.split(',').map { it.toDouble() } }.toDoubleArray()
    }

    /**
     * Lo que se manda al IPMA (después de mirar los términos de España): dentro del contorno, en
     * Madeira o Azores, o a 12 km de una de sus localidades, que la costa de Natural Earth es
     * gruesa y Ponta Delgada o una playa de Lisboa quedan fuera.
     */
    fun portugal(lat: Double, lon: Double): Boolean =
        contains(lat, lon) || (lat in 32.3..33.3 && lon in -17.4..-16.0) || (lat in 36.6..40.0 && lon in -31.6..-24.7) ||
            (Ipma.nearest(lat, lon)?.second ?: 99.0) <= 12.0

    fun contains(lat: Double, lon: Double): Boolean {
        var c = false
        for (r in rings) {
            var j = r.size - 2
            for (i in r.indices step 2) {
                val xi = r[i]; val yi = r[i + 1]; val xj = r[j]; val yj = r[j + 1]
                if ((yi > lat) != (yj > lat) && lon < (xj - xi) * (lat - yi) / (yj - yi) + xi) c = !c
                j = i
            }
        }
        return c
    }
}

