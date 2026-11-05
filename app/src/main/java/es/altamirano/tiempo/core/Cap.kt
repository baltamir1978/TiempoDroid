package es.altamirano.tiempo.core

import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler
import java.io.ByteArrayInputStream
import java.time.Instant
import java.time.OffsetDateTime
import javax.xml.parsers.SAXParserFactory

/** Avisos de AEMET (Meteoalerta, formato CAP). Traducción de `AEMETAlertsService.swift`. */
enum class AlertLevel { VERDE, AMARILLO, NARANJA, ROJO;
    companion object { fun of(name: String) = when (name.lowercase()) { "amarillo" -> AMARILLO; "naranja" -> NARANJA; "rojo" -> ROJO; else -> VERDE } }
}

data class AemetAlert(
    val phenomenon: String, val code: String, val event: String, val level: AlertLevel,
    val area: String, val onset: Instant?, val expires: Instant?,
)

object Cap {
    /** Provincia (dos primeras cifras del código INE) → área de Meteoalerta de su comunidad. */
    private val provinceToArea = mapOf(
        4 to 61, 11 to 61, 14 to 61, 18 to 61, 21 to 61, 23 to 61, 29 to 61, 41 to 61,
        22 to 62, 44 to 62, 50 to 62, 33 to 63, 7 to 64, 35 to 65, 38 to 65, 39 to 66,
        5 to 67, 9 to 67, 24 to 67, 34 to 67, 37 to 67, 40 to 67, 42 to 67, 47 to 67, 49 to 67,
        2 to 68, 13 to 68, 16 to 68, 19 to 68, 45 to 68, 8 to 69, 17 to 69, 25 to 69, 43 to 69,
        6 to 70, 10 to 70, 15 to 71, 27 to 71, 32 to 71, 36 to 71, 28 to 72, 30 to 73, 31 to 74,
        1 to 75, 20 to 75, 48 to 75, 26 to 76, 3 to 77, 12 to 77, 46 to 77, 51 to 78, 52 to 79,
    )

    fun area(municipio: String): Int? = municipio.take(2).toIntOrNull()?.let { provinceToArea[it] }

    /**
     * Los avisos del `.tar` que cubren el punto, uno por fenómeno con su nivel más alto. Los
     * periodos ya terminados se descartan (si no, un aviso del domingo pasado seguía saliendo).
     */
    fun parse(tar: ByteArray, lat: Double, lon: Double, now: Instant = Instant.now()): List<AemetAlert> {
        val best = mutableMapOf<String, AemetAlert>()
        for ((_, xml) in tarEntries(tar)) {
            for (info in parseDocument(xml)) {
                if (!info.language.startsWith("es") || info.level == AlertLevel.VERDE) continue
                if (info.expires?.isBefore(now) == true) continue
                val area = info.areas.firstOrNull { a -> a.second.any { pointInPolygon(lat, lon, it) } } ?: continue
                val alert = AemetAlert(info.phenomenon, info.code, info.event, info.level, area.first, info.onset, info.expires)
                val existing = best[info.phenomenon]
                if (existing == null || existing.level < alert.level) best[info.phenomenon] = alert
            }
        }
        return best.values.sortedByDescending { it.level }
    }

    /** Un `.tar` POSIX: (nombre, contenido) de cada fichero. */
    fun tarEntries(data: ByteArray): List<Pair<String, ByteArray>> {
        val out = mutableListOf<Pair<String, ByteArray>>()
        var offset = 0
        while (offset + 512 <= data.size) {
            val header = data.copyOfRange(offset, offset + 512)
            if (header.all { it == 0.toByte() }) break
            val name = cString(header, 0, 100)
            val size = cString(header, 124, 12).trim().toIntOrNull(8) ?: 0
            offset += 512
            if (size > 0 && offset + size <= data.size && name.isNotEmpty()) out += name to data.copyOfRange(offset, offset + size)
            offset += (size + 511) / 512 * 512
        }
        return out
    }

    private fun cString(b: ByteArray, start: Int, len: Int): String {
        val end = (start until minOf(start + len, b.size)).firstOrNull { b[it] == 0.toByte() } ?: minOf(start + len, b.size)
        return String(b, start, end - start, Charsets.UTF_8)
    }

    class Info(
        var language: String = "", var event: String = "", var phenomenon: String = "", var code: String = "",
        var level: AlertLevel = AlertLevel.VERDE, var onset: Instant? = null, var expires: Instant? = null,
        val areas: MutableList<Pair<String, List<List<Pair<Double, Double>>>>> = mutableListOf(),
    )

    fun parseDocument(xml: ByteArray): List<Info> {
        val infos = mutableListOf<Info>()
        val handler = object : DefaultHandler() {
            var cur = Info(); var text = StringBuilder(); var areaDesc = ""; var polys = mutableListOf<List<Pair<Double, Double>>>()
            var lastValueName = ""
            override fun startElement(uri: String?, localName: String?, qName: String, attributes: Attributes?) {
                text = StringBuilder()
                when (local(qName)) { "info" -> cur = Info(); "area" -> { areaDesc = ""; polys = mutableListOf() } }
            }
            override fun characters(ch: CharArray, start: Int, length: Int) { text.append(ch, start, length) }
            override fun endElement(uri: String?, localName: String?, qName: String) {
                val t = text.toString().trim()
                when (local(qName)) {
                    "language" -> cur.language = t
                    "event" -> cur.event = t
                    "onset" -> cur.onset = time(t)
                    "expires" -> cur.expires = time(t)
                    "valueName" -> lastValueName = t
                    "value" -> when {
                        "fenomeno" in lastValueName -> { val p = t.split(";"); cur.code = p.first(); cur.phenomenon = p.getOrElse(1) { t } }
                        "nivel" in lastValueName -> cur.level = AlertLevel.of(t)
                    }
                    "areaDesc" -> areaDesc = t
                    "polygon" -> {
                        val pts = t.split(' ', '\n').mapNotNull { pair ->
                            val c = pair.split(","); if (c.size == 2) c[0].toDoubleOrNull()?.let { la -> c[1].toDoubleOrNull()?.let { lo -> la to lo } } else null
                        }
                        if (pts.size >= 3) polys += pts
                    }
                    "area" -> cur.areas += areaDesc to polys.toList()
                    "info" -> infos += cur
                }
                text = StringBuilder()
            }
        }
        runCatching {
            SAXParserFactory.newInstance().newSAXParser().parse(ByteArrayInputStream(xml), handler)
        }
        return infos
    }

    private fun local(q: String) = q.substringAfter(':')
    private fun time(s: String): Instant? = runCatching { OffsetDateTime.parse(s).toInstant() }.getOrNull()

    /** Lanzando un rayo (x = longitud, y = latitud). */
    fun pointInPolygon(lat: Double, lon: Double, poly: List<Pair<Double, Double>>): Boolean {
        if (poly.size < 3) return false
        var inside = false
        var j = poly.size - 1
        for (i in poly.indices) {
            val (yi, xi) = poly[i]; val (yj, xj) = poly[j]
            if ((yi > lat) != (yj > lat) && lon < (xj - xi) * (lat - yi) / (yj - yi) + xi) inside = !inside
            j = i
        }
        return inside
    }
}
