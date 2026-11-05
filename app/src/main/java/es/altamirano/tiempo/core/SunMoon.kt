package es.altamirano.tiempo.core

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.tan

/**
 * Sol y luna, traducido línea a línea de `SunMoonService.swift` para que den las mismas horas
 * que la app de iOS: el algoritmo de la NOAA para el sol y una luna simplificada.
 */
data class SunTimes(val sunrise: Instant?, val noon: Instant?, val sunset: Instant?) {
    val daylightMinutes: Long? get() = if (sunrise != null && sunset != null && sunset.isAfter(sunrise)) (sunset.epochSecond - sunrise.epochSecond) / 60 else null
}

enum class MoonPhase(val label: String, val emoji: String) {
    NEW("Luna nueva", "🌑"), WAXING_CRESCENT("Creciente iluminante", "🌒"), FIRST_QUARTER("Cuarto creciente", "🌓"),
    WAXING_GIBBOUS("Gibosa creciente", "🌔"), FULL("Luna llena", "🌕"), WANING_GIBBOUS("Gibosa menguante", "🌖"),
    LAST_QUARTER("Cuarto menguante", "🌗"), WANING_CRESCENT("Menguante iluminante", "🌘"),
}

data class MoonInfo(val rise: Instant?, val set: Instant?, val phase: MoonPhase, val illumination: Double, val ageDays: Double)

object SunMoon {
    private const val SYNODIC = 29.530588853
    private const val REF_NEW_MOON_JD = 2451550.259

    private val Double.rad get() = this * PI / 180
    private val Double.deg get() = this * 180 / PI

    fun julianDay(at: Instant): Double {
        val z = at.atZone(ZoneOffset.UTC)
        var y = z.year.toDouble(); var m = z.monthValue.toDouble()
        val d = z.dayOfMonth + z.hour / 24.0 + z.minute / 1440.0 + z.second / 86400.0
        if (m <= 2) { y -= 1; m += 12 }
        val a = floor(y / 100); val b = 2 - a + floor(a / 4)
        return floor(365.25 * (y + 4716)) + floor(30.6001 * (m + 1)) + d + b - 1524.5
    }

    /** Orto, mediodía y ocaso del día `date` (como en iOS: el día UTC del instante dado). */
    fun sun(date: LocalDate, lat: Double, lon: Double, zone: ZoneId): SunTimes {
        // iOS calcula con el instante «ahora» del día pedido y la medianoche UTC de ese día.
        val at = ZonedDateTime.of(date.atTime(12, 0), zone).toInstant()
        val jd = julianDay(at)
        val t = (jd - 2451545.0) / 36525.0
        val l0 = (280.46646 + t * (36000.76983 + t * 0.0003032)) % 360
        val m = (357.52911 + t * (35999.05029 - 0.0001537 * t)) % 360
        val c = sin(m.rad) * (1.914602 - t * (0.004817 + 0.000014 * t)) + sin(2 * m.rad) * (0.019993 - 0.000101 * t) + sin(3 * m.rad) * 0.000289
        val omega = 125.04 - 1934.136 * t
        val lambda = l0 + c - 0.00569 - 0.00478 * sin(omega.rad)
        val eps0 = 23.0 + (26.0 + (21.448 - t * (46.8150 + t * (0.00059 - t * 0.001813))) / 60.0) / 60.0
        val eps = eps0 + 0.00256 * cos(omega.rad)
        val sinDec = sin(eps.rad) * sin(lambda.rad)
        val dec = asin(sinDec)
        val eqT = equationOfTime(l0, m, eps)
        val noonUT = (720 - 4 * lon - eqT) / 1440.0
        val cosHA = (cos(90.833.rad) - sin(lat.rad) * sinDec) / (cos(lat.rad) * cos(dec))
        val midnight = at.atZone(ZoneOffset.UTC).toLocalDate().atStartOfDay().toInstant(ZoneOffset.UTC)
        fun at(fraction: Double) = midnight.plusMillis((fraction * 86_400_000).toLong())
        if (cosHA.isNaN() || cosHA > 1 || cosHA < -1) return SunTimes(null, at(noonUT), null)
        val ha = acos(cosHA).deg
        return SunTimes(at(noonUT - ha / 360), at(noonUT), at(noonUT + ha / 360))
    }

    private fun equationOfTime(l0: Double, m: Double, eps: Double): Double {
        val y = tan(eps.rad / 2).let { it * it }
        val e = 0.016708634
        val v = y * sin(2 * l0.rad) - 2 * e * sin(m.rad) + 4 * e * y * sin(m.rad) * cos(2 * l0.rad) -
            0.5 * y * y * sin(4 * l0.rad) - 1.25 * e * e * sin(2 * m.rad)
        return 4 * v.deg
    }

    fun moon(date: LocalDate, lat: Double, lon: Double, zone: ZoneId, now: Instant = ZonedDateTime.of(date.atTime(12, 0), zone).toInstant()): MoonInfo {
        var age = (julianDay(now) - REF_NEW_MOON_JD) % SYNODIC
        if (age < 0) age += SYNODIC
        val illumination = (1 - cos(2 * PI * age / SYNODIC)) / 2
        val (rise, set) = moonRiseSet(date, lat, lon, zone)
        return MoonInfo(rise, set, phaseOf(age), illumination, age)
    }

    fun phaseOf(age: Double): MoonPhase = when {
        age < 1.85 -> MoonPhase.NEW; age < 5.54 -> MoonPhase.WAXING_CRESCENT; age < 9.23 -> MoonPhase.FIRST_QUARTER
        age < 12.92 -> MoonPhase.WAXING_GIBBOUS; age < 16.61 -> MoonPhase.FULL; age < 20.30 -> MoonPhase.WANING_GIBBOUS
        age < 23.99 -> MoonPhase.LAST_QUARTER; age < 27.68 -> MoonPhase.WANING_CRESCENT; else -> MoonPhase.NEW
    }

    private fun moonRiseSet(date: LocalDate, lat: Double, lon: Double, zone: ZoneId): Pair<Instant?, Instant?> {
        val midnight = date.atStartOfDay(zone).toInstant()
        var rise: Instant? = null; var set: Instant? = null
        var prev = moonAltitude(midnight, lat, lon)
        for (h in 1..24) {
            val alt = moonAltitude(midnight.plusSeconds(h * 3600L), lat, lon)
            if (prev < 0 && alt >= 0 && rise == null) rise = midnight.plusMillis(((h - 1 + -prev / (alt - prev)) * 3_600_000).toLong())
            if (prev >= 0 && alt < 0 && set == null) set = midnight.plusMillis(((h - 1 + prev / (prev - alt)) * 3_600_000).toLong())
            prev = alt
        }
        return rise to set
    }

    private fun moonAltitude(at: Instant, lat: Double, lon: Double): Double {
        val jd = julianDay(at)
        val t = (jd - 2451545.0) / 36525.0
        val l = (218.3164477 + 481267.88123421 * t) % 360
        val d = (297.8501921 + 445267.1114034 * t) % 360
        val m = (357.5291092 + 35999.0502909 * t) % 360
        val mp = (134.9633964 + 477198.8675055 * t) % 360
        val f = (93.2720950 + 483202.0175233 * t) % 360
        val dl = (6288774 * sin(mp.rad) + 1274027 * sin((2 * d - mp).rad) + 658314 * sin((2 * d).rad) -
            185116 * sin(m.rad) - 114332 * sin((2 * f).rad)) / 1_000_000.0
        val db = (5128122 * sin(f.rad) + 280602 * sin((mp + f).rad) + 277693 * sin((mp - f).rad)) / 1_000_000.0
        val mLon = (l + dl) % 360; val mLat = db
        val eps = 23.4393 - 0.0000004 * t
        val dec = asin(sin(mLat.rad) * cos(eps.rad) + cos(mLat.rad) * sin(eps.rad) * sin(mLon.rad))
        val ra = atan2(sin(mLon.rad) * cos(eps.rad) - tan(mLat.rad) * sin(eps.rad), cos(mLon.rad))
        val gmst = (280.46061837 + 360.98564736629 * (jd - 2451545.0)) % 360
        val lha = (gmst + lon - ra.deg) % 360
        return asin(sin(lat.rad) * sin(dec) + cos(lat.rad) * cos(dec) * cos(lha.rad)).deg
    }

    /** Próximas lunas nueva, creciente, llena y menguante (por la edad lunar). */
    fun nextPhases(from: Instant, count: Int = 4): List<Pair<MoonPhase, Instant>> {
        var age = (julianDay(from) - REF_NEW_MOON_JD) % SYNODIC
        if (age < 0) age += SYNODIC
        val marks = listOf(0.0 to MoonPhase.NEW, SYNODIC / 4 to MoonPhase.FIRST_QUARTER, SYNODIC / 2 to MoonPhase.FULL, 3 * SYNODIC / 4 to MoonPhase.LAST_QUARTER)
        val out = mutableListOf<Pair<MoonPhase, Instant>>()
        var cycle = 0
        while (out.size < count) {
            for ((mark, phase) in marks) {
                val days = mark + cycle * SYNODIC - age
                if (days > 0 && out.size < count) out += phase to from.plusMillis((days * 86_400_000).toLong())
            }
            cycle++
        }
        return out
    }
}
