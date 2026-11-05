package es.altamirano.tiempo.core

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sin

/**
 * Lunas nuevas y llenas (Meeus, cap. 49) y eventos astronómicos: equinoccios y solsticios
 * (cap. 27), eclipses y sucesos planetarios en tabla, lluvias de estrellas, superlunas y los
 * amaneceres y atardeceres extremos del sitio. Traducido de `MoonPhasesService.swift`,
 * `AstroEventsService.swift` y `SunMoonService.solarTurningPoints`.
 */
data class PhaseEvent(val full: Boolean, val at: Instant) {
    val label get() = if (full) "Luna llena" else "Luna nueva"
    val emoji get() = if (full) "🌕" else "🌑"
}

data class AstroEvent(val label: String, val emoji: String, val details: String?, val at: Instant)

object Astro {
    private val Double.rad get() = this * PI / 180
    private fun jdeToInstant(jde: Double) = Instant.ofEpochMilli(((jde - 2440587.5) * 86_400_000).toLong())

    /** Las `count` lunas nuevas y llenas siguientes a `from`. */
    fun phases(from: Instant, count: Int = 8): List<PhaseEvent> {
        val d = from.atZone(ZoneOffset.UTC)
        val k0 = floor((d.year + d.dayOfYear / 365.25 - 2000.0) * 12.3685) - 2
        val out = mutableListOf<PhaseEvent>()
        var k = k0
        while (out.count { !it.at.isBefore(from) } < count) {
            out += PhaseEvent(false, phase(k)); out += PhaseEvent(true, phase(k + 0.5)); k += 1
        }
        return out.filter { !it.at.isBefore(from) }.sortedBy { it.at }.take(count)
    }

    private fun phase(k: Double): Instant {
        val t = k / 1236.85; val t2 = t * t; val t3 = t2 * t; val t4 = t3 * t
        var jde = 2451550.09766 + 29.530588861 * k + 0.00015437 * t2 - 0.000000150 * t3 + 0.00000000073 * t4
        val e = 1 - 0.002516 * t - 0.0000074 * t2
        val m = (2.5534 + 29.10535670 * k - 0.0000014 * t2 - 0.00000011 * t3).rad
        val mp = (201.5643 + 385.81693528 * k + 0.0107582 * t2 + 0.00001238 * t3 - 0.000000058 * t4).rad
        val f = (160.7108 + 390.67050284 * k - 0.0016118 * t2 - 0.00000227 * t3 + 0.000000011 * t4).rad
        val om = (124.7746 - 1.56375588 * k + 0.0020672 * t2 + 0.00000215 * t3).rad
        jde += -0.40720 * sin(mp) + 0.17241 * e * sin(m) + 0.01608 * sin(2 * mp) + 0.01039 * sin(2 * f) +
            0.00739 * e * sin(mp - m) - 0.00514 * e * sin(mp + m) + 0.00208 * e * e * sin(2 * m) -
            0.00111 * sin(mp - 2 * f) - 0.00057 * sin(mp + 2 * f) + 0.00056 * e * sin(2 * mp + m) -
            0.00042 * sin(3 * mp) + 0.00042 * e * sin(m + 2 * f) + 0.00038 * e * sin(m - 2 * f) -
            0.00024 * e * sin(2 * mp - m) - 0.00017 * sin(om) - 0.00007 * sin(mp + 2 * m) +
            0.00004 * sin(2 * mp - 2 * f) + 0.00004 * sin(3 * m) + 0.00003 * sin(mp + m - 2 * f) +
            0.00003 * sin(2 * mp + 2 * f) - 0.00003 * sin(mp + m + 2 * f) + 0.00003 * sin(mp - m + 2 * f) -
            0.00002 * sin(mp - m - 2 * f) - 0.00002 * sin(3 * mp + m) + 0.00002 * sin(4 * mp)
        val a = listOf(
            0.000325 to 299.77 + 0.107408 * k - 0.009173 * t2, 0.000165 to 251.88 + 0.016321 * k,
            0.000164 to 251.83 + 26.651886 * k, 0.000126 to 349.42 + 36.412478 * k, 0.000110 to 84.66 + 18.206239 * k,
            0.000062 to 141.74 + 53.303771 * k, 0.000060 to 207.14 + 2.453732 * k, 0.000056 to 154.84 + 7.306860 * k,
            0.000047 to 34.52 + 27.261239 * k, 0.000042 to 207.19 + 0.121824 * k, 0.000040 to 291.34 + 1.844379 * k,
            0.000037 to 161.72 + 24.198154 * k, 0.000035 to 239.56 + 25.513099 * k, 0.000023 to 331.55 + 3.592518 * k,
        )
        for ((c, deg) in a) jde += c * sin(deg.rad)
        return jdeToInstant(jde)
    }

    /** Los eventos desde `from` hasta un año después, del sitio y en su hora. */
    fun events(from: Instant, lat: Double, lon: Double, zone: ZoneId): List<AstroEvent> {
        val year = from.atZone(zone).year
        val all = (year - 1..year + 3).flatMap { equinoxes(it) } + eclipses() + meteors(year, zone) +
            supermoons(from) + planets(zone) + turningPoints(year, lat, lon, zone)
        val horizon = from.atZone(zone).plusYears(1).toInstant()
        return all.filter { !it.at.isBefore(from) && !it.at.isAfter(horizon) }.sortedBy { it.at }
    }

    private fun equinoxes(year: Int): List<AstroEvent> {
        val k = (year - 2000.0) / 1000.0
        val march = 2451623.80984 + 365242.37404 * k + 0.05169 * k * k - 0.00411 * k * k * k - 0.00057 * k.pow(4)
        val june = 2451716.56767 + 365241.62603 * k + 0.00325 * k * k + 0.00888 * k * k * k - 0.00030 * k.pow(4)
        val sept = 2451810.21715 + 365242.01767 * k - 0.11575 * k * k + 0.00337 * k * k * k + 0.00078 * k.pow(4)
        val dec = 2451900.05952 + 365242.74049 * k - 0.06223 * k * k - 0.00823 * k * k * k + 0.00032 * k.pow(4)
        return listOf(
            AstroEvent("Equinoccio de primavera", "🌸", null, jdeToInstant(corrected(march))),
            AstroEvent("Solsticio de verano", "☀️", null, jdeToInstant(corrected(june))),
            AstroEvent("Equinoccio de otoño", "🍂", null, jdeToInstant(corrected(sept))),
            AstroEvent("Solsticio de invierno", "❄️", null, jdeToInstant(corrected(dec))),
        )
    }

    private fun corrected(jde0: Double): Double {
        val t = (jde0 - 2451545.0) / 36525.0
        val w = 35999.373 * t - 2.47
        val dl = 1 + 0.0334 * cos(w.rad) + 0.0007 * cos(2 * w.rad)
        val terms = listOf(
            Triple(485.0, 324.96, 1934.136), Triple(203.0, 337.23, 32964.467), Triple(199.0, 342.08, 20.186),
            Triple(182.0, 27.85, 445267.112), Triple(156.0, 73.14, 45036.886), Triple(136.0, 171.52, 22518.443),
            Triple(77.0, 222.54, 65928.934), Triple(74.0, 41.10, 3034.906), Triple(70.0, 213.92, 63235.085),
            Triple(58.0, 74.03, 16859.071), Triple(52.0, 177.41, 26895.292), Triple(50.0, 28.70, 4443.772),
            Triple(45.0, 28.24, 63351.548), Triple(44.0, 18.91, 78495.651), Triple(29.0, 207.19, 253.032),
            Triple(18.0, 29.57, 9037.513), Triple(17.0, 19.35, 37935.000), Triple(16.0, 154.84, 2281.226),
            Triple(14.0, 45.70, 20601.048), Triple(12.0, 44.57, 23871.000), Triple(12.0, 55.72, 22279.690),
            Triple(9.0, 23.39, 10263.100), Triple(8.0, 28.36, 1222.114), Triple(7.0, 154.84, 9584.000),
        )
        val s = terms.sumOf { (a, b, c) -> a * cos((b + c * t).rad) }
        return jde0 + 0.00001 * s / dl
    }

    /** NASA / Espenak, en UTC. */
    private fun eclipses() = listOf(
        "2026-02-17T12:12" to Triple("Eclipse de luna penumbral", "🌝", "Penumbral, visible Europa"),
        "2026-08-12T17:46" to Triple("Eclipse de sol total", "🌑", "Total, visible N. Atlántico, Groenlandia"),
        "2027-02-06T22:00" to Triple("Eclipse de luna penumbral", "🌝", "Penumbral"),
        "2027-08-02T10:07" to Triple("Eclipse de sol total", "🌑", "Total, visible N. África, Oriente Medio"),
        "2028-01-26T15:13" to Triple("Eclipse de luna total", "🌕", "Total, visible Europa, África, Asia"),
        "2028-07-22T02:57" to Triple("Eclipse de sol total", "🌑", "Total, visible Australia, Nueva Zelanda"),
        "2029-01-14T17:21" to Triple("Eclipse de luna total", "🌕", "Total, visible Europa, América"),
        "2029-06-12T04:06" to Triple("Eclipse de luna parcial", "🌗", "Parcial"),
        "2029-07-11T15:37" to Triple("Eclipse de sol parcial", "🌑", "Parcial, hemisferio sur"),
        "2030-01-05T17:55" to Triple("Eclipse de luna penumbral", "🌝", "Penumbral"),
        "2030-06-01T06:21" to Triple("Eclipse de sol anular", "🌑", "Anular, visible N. África, Asia"),
        "2030-11-25T06:52" to Triple("Eclipse de sol total", "🌑", "Total, visible Namibia, Australia"),
    ).map { (t, e) -> AstroEvent(e.first, e.second, e.third, LocalDateTime.parse(t).toInstant(ZoneOffset.UTC)) }

    private val showers = listOf(
        listOf("Cuadrántidas", 1, 4, 110), listOf("Líridas", 4, 22, 18), listOf("Eta Acuáridas", 5, 6, 50),
        listOf("Delta Acuáridas", 7, 30, 25), listOf("Perseidas", 8, 12, 100), listOf("Oriónidas", 10, 21, 20),
        listOf("Leónidas", 11, 17, 15), listOf("Gemínidas", 12, 14, 120), listOf("Úrsidas", 12, 22, 10),
    )

    /** El radiante está más alto antes del alba: las 05:00 del sitio como hora del pico. */
    private fun meteors(year: Int, zone: ZoneId) = (year - 1..year + 2).flatMap { y ->
        showers.map { (name, m, d, zhr) ->
            AstroEvent("Lluvia de $name", "🌠", "Pico · hasta $zhr meteoros/h",
                LocalDate.of(y, m as Int, d as Int).atTime(5, 0).atZone(zone).toInstant())
        }
    }

    private fun supermoons(from: Instant) = phases(from, 30).filter { it.full }.mapNotNull { f ->
        val km = moonDistanceKm(f.at); val txt = "${Math.round(km)} km"
        when {
            km < 360_000 -> AstroEvent("Superluna", "🌕", "Luna llena en perigeo · $txt", f.at)
            km > 405_000 -> AstroEvent("Microluna", "🌙", "Luna llena en apogeo · $txt", f.at)
            else -> null
        }
    }

    /** Distancia a la Luna (Meeus, cap. 47, los términos mayores de Σr). */
    fun moonDistanceKm(at: Instant): Double {
        val jde = at.toEpochMilli() / 86_400_000.0 + 2440587.5
        val t = (jde - 2451545.0) / 36525.0
        val d = 297.8501921 + 445267.1114034 * t - 0.0018819 * t * t
        val m = 357.5291092 + 35999.0502909 * t - 0.0001536 * t * t
        val mp = 134.9633964 + 477198.8675055 * t + 0.0087414 * t * t
        val f = 93.2720950 + 483202.0175233 * t - 0.0036539 * t * t
        val e = 1 - 0.002516 * t - 0.0000074 * t * t
        val terms = listOf(
            doubleArrayOf(0.0, 0.0, 1.0, 0.0, -20905355.0), doubleArrayOf(2.0, 0.0, -1.0, 0.0, -3699111.0),
            doubleArrayOf(2.0, 0.0, 0.0, 0.0, -2955968.0), doubleArrayOf(0.0, 0.0, 2.0, 0.0, -569925.0),
            doubleArrayOf(0.0, 1.0, 0.0, 0.0, 48888.0), doubleArrayOf(0.0, 0.0, 0.0, 2.0, -3149.0),
            doubleArrayOf(2.0, 0.0, -2.0, 0.0, 246158.0), doubleArrayOf(2.0, -1.0, -1.0, 0.0, -152138.0),
            doubleArrayOf(2.0, 0.0, 1.0, 0.0, -170733.0), doubleArrayOf(2.0, -1.0, 0.0, 0.0, -204586.0),
            doubleArrayOf(0.0, 1.0, -1.0, 0.0, -129620.0), doubleArrayOf(1.0, 0.0, 0.0, 0.0, 108743.0),
            doubleArrayOf(0.0, 1.0, 1.0, 0.0, 104755.0), doubleArrayOf(2.0, 0.0, 0.0, -2.0, 10321.0),
            doubleArrayOf(4.0, 0.0, -1.0, 0.0, 79661.0),
        )
        val sigma = terms.sumOf { (a, b, c, dd, coef) -> coef * e.pow(abs(b)) * cos((a * d + b * m + c * mp + dd * f).rad) }
        return 385000.56 + sigma / 1000.0
    }


    /** Tabla curada en iOS (jun-2026) con in-the-sky.org y otras: hora local del sitio. */
    private fun planets(zone: ZoneId): List<AstroEvent> {
        val opp = "Mejor momento del año para verlo"
        return listOf(
            Triple("2026-06-09T22:00", "Conjunción Venus–Júpiter", "🪐") to "A 1.6° al anochecer · par más brillante de 2026",
            Triple("2026-06-25T05:30", "Conjunción Mercurio–Júpiter", "🪐") to "A 3.7° al amanecer",
            Triple("2026-10-06T06:00", "Conjunción Mercurio–Venus", "🪐") to "A 5° al amanecer",
            Triple("2026-11-15T20:00", "Conjunción Marte–Júpiter", "🪐") to "A 1.2° · buena visibilidad",
            Triple("2027-07-01T21:00", "Conjunción Venus–Mercurio", "🪐") to "A 4.6° al anochecer",
            Triple("2027-08-11T20:30", "Conjunción Venus–Mercurio", "🪐") to "A 0.5° · muy juntos, cerca del Sol",
            Triple("2027-10-10T06:00", "Conjunción Venus–Mercurio", "🪐") to "A 4.2° al amanecer",
            Triple("2026-10-04T00:00", "Saturno en oposición", "🔭") to opp,
            Triple("2027-02-11T00:00", "Júpiter en oposición", "🔭") to opp,
            Triple("2027-02-19T00:00", "Marte en oposición", "🔭") to opp,
            Triple("2027-10-18T00:00", "Saturno en oposición", "🔭") to opp,
            Triple("2028-03-12T00:00", "Júpiter en oposición", "🔭") to opp,
            Triple("2028-10-30T00:00", "Saturno en oposición", "🔭") to opp,
            Triple("2029-03-25T00:00", "Marte en oposición", "🔭") to opp,
            Triple("2029-04-12T00:00", "Júpiter en oposición", "🔭") to opp,
            Triple("2029-11-13T00:00", "Saturno en oposición", "🔭") to opp,
            Triple("2030-05-13T00:00", "Júpiter en oposición", "🔭") to opp,
            Triple("2030-11-27T00:00", "Saturno en oposición", "🔭") to opp,
            Triple("2026-06-15T22:00", "Mercurio en máxima elongación", "🌟") to "Visible tras el ocaso",
            Triple("2026-08-15T22:00", "Venus en máxima elongación", "🌟") to "Lucero vespertino al anochecer",
            Triple("2026-10-12T20:30", "Mercurio en máxima elongación", "🌟") to "Visible tras el ocaso",
            Triple("2027-01-03T07:00", "Venus en máxima elongación", "🌟") to "Lucero del alba al amanecer",
            Triple("2028-03-22T21:30", "Venus en máxima elongación", "🌟") to "Lucero vespertino al anochecer",
        ).map { (e, details) -> AstroEvent(e.second, e.third, details, LocalDateTime.parse(e.first).atZone(zone).toInstant()) }
    }

    /**
     * Amanecer más temprano y atardecer más tardío (junio) y al revés (diciembre): 46 días desde
     * el día 1 de esos meses, sin cambio de hora dentro.
     */
    private fun turningPoints(year: Int, lat: Double, lon: Double, zone: ZoneId): List<AstroEvent> {
        val fmt = java.time.format.DateTimeFormatter.ofPattern("HH:mm")
        fun ev(label: String, emoji: String, note: String, at: Instant) = AstroEvent(label, emoji, "$note · ${at.atZone(zone).format(fmt)}", at)
        fun secs(i: Instant) = i.atZone(zone).toLocalTime().toSecondOfDay()
        val out = mutableListOf<AstroEvent>()
        for (y in year..year + 1) {
            for (month in listOf(6, 12)) {
                val days = (0L until 46L).map { SunMoon.sun(LocalDate.of(y, month, 1).plusDays(it), lat, lon, zone) }
                val rises = days.mapNotNull { it.sunrise }; val sets = days.mapNotNull { it.sunset }
                if (rises.isEmpty() || sets.isEmpty()) continue
                if (month == 6) {
                    out += ev("Amanecer más temprano", "🌅", "Desde hoy los amaneceres empiezan a retrasarse", rises.minBy { secs(it) })
                    out += ev("Atardecer más tardío", "🌇", "Desde hoy las tardes empiezan a acortarse", sets.maxBy { secs(it) })
                } else {
                    out += ev("Amanecer más tardío", "🌄", "Desde hoy los amaneceres empiezan a adelantarse", rises.maxBy { secs(it) })
                    out += ev("Atardecer más temprano", "🌆", "Desde hoy las tardes empiezan a alargarse", sets.minBy { secs(it) })
                }
            }
        }
        return out
    }
}
