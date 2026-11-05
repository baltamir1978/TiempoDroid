package es.altamirano.tiempo.core

import es.altamirano.tiempo.net.Tides
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

private fun res(name: String) = ClassLoader.getSystemResourceAsStream(name)!!.readBytes()
private fun text(name: String) = String(res(name))

class AemetTest {
    @Test fun dailyToleratesNumbersAsTextAndPicksThePeriodThatHasData() {
        val d = parseDaily(text("aemet-diaria.json"))!!
        val today = d.upcomingDays(LocalDate.of(2026, 11, 5)).first()
        assertEquals(14.0, today.temperatura!!.maxima!!, 0.0)
        assertEquals("0", today.probPrecipitacion!![0].value)          // venía como número
        assertEquals("12", pickPeriod(today.estadoCielo)!!.value)      // el 00-24 viene vacío
        assertEquals("35", maxOfDay(today.rachaMax)!!.value)
        assertEquals("10", today.viento!![0].velocidad)
        // Pasado el día, ya no cuenta.
        assertEquals(1, d.upcomingDays(LocalDate.of(2026, 11, 6)).size)
    }

    @Test fun nextHoursFromNowWithRainRanges() {
        val h = parseHourly(text("aemet-horaria.json"))
        val hours = h.nextHours(LocalDateTime.of(2026, 11, 5, 20, 30))
        assertEquals(listOf(20, 21, 8), hours.map { it.hour })
        assertEquals(40, hours[0].prob)       // 20 está en «2002» (20 a 02)
        assertEquals("11n", hours[0].sky)
        assertEquals(Sky.CLEAR_NIGHT, skyOf(hours[0].sky))
    }

    @Test fun observationFreshness() {
        val now = Instant.parse("2026-11-05T12:00:00Z")
        assertEquals(9.0, freshObsTemp(ObsRecord(fint = "2026-11-05T10:30:00", ta = 9.0), now)!!, 0.0)
        assertNull(freshObsTemp(ObsRecord(fint = "2026-11-05T09:00:00", ta = 9.0), now))
        val recs = listOf(ObsRecord(fint = "a", ta = 8.0, hr = 70.0), ObsRecord(fint = "b", ta = 9.0))
        assertEquals(70.0, recs.latest { it.hr }!!, 0.0)                // el último no trae humedad
        assertEquals(9.0, recs.latestTemp()!!.ta!!, 0.0)
    }

    @Test fun canariesRunOnTheirOwnClock() {
        assertEquals(ZoneId.of("Atlantic/Canary"), timeZoneFor("38038", 28.46, -16.25))
        assertEquals(ZoneId.of("Atlantic/Canary"), timeZoneFor("", 28.1, -15.4))
        assertEquals(ZoneId.of("Europe/Madrid"), timeZoneFor("40194", 40.95, -4.12))
    }
}

class OpenMeteoTest {
    @Test fun adaptsToTheAemetShapes() {
        val f = adaptOpenMeteo(AemetJson.decodeFromString(OmForecast.serializer(), text("openmeteo-segovia.json")))
        assertEquals(7, f.daily!!.prediccion!!.dia!!.size)
        assertEquals(7, f.hourly!!.prediccion!!.dia!!.size)
        val day = f.daily.prediccion!!.dia!![0]
        assertNotNull(day.temperatura!!.maxima)
        assertTrue(skyOf(pickPeriod(day.estadoCielo)!!.value) != Sky.UNKNOWN)
        assertEquals(24, f.hourly.prediccion!!.dia!![0].temperatura!!.size)
        assertNotNull(f.obs!!.single().ta)
    }

    @Test fun wmoCodes() {
        assertEquals("11", skyCode(0, true)); assertEquals("11n", skyCode(0, false)); assertEquals("62", skyCode(99, true))
    }
}

class CapTest {
    private val tar = res("avisos-cap.tar")
    private val now = Instant.parse("2026-11-05T12:00:00Z")

    @Test fun keepsTheHighestCurrentLevelInSpanishThatCoversThePoint() {
        val alerts = Cap.parse(tar, 40.95, -4.12, now)
        val a = alerts.single()
        assertEquals("Nevadas", a.phenomenon)
        assertEquals(AlertLevel.NARANJA, a.level)     // el rojo ya caducó; el inglés no cuenta
        assertEquals("Sierra de Segovia", a.area)
    }

    @Test fun pointOutsideHasNoAlerts() = assertTrue(Cap.parse(tar, 43.4, -4.75, now).isEmpty())

    @Test fun areaFromProvince() {
        assertEquals(67, Cap.area("40194")); assertEquals(63, Cap.area("33036")); assertEquals(65, Cap.area("38038"))
    }
}

class TidesTest {
    @Test fun ihmTimesAreUtc() {
        val tides = Tides.parseIhm(text("ihm-llanes-20260701.json"), LocalDate.of(2026, 7, 1))
        assertEquals(4, tides.size)
        val first = tides[0]
        assertTrue(first.high)
        // 04:11 UTC es 06:11 en Llanes en verano: sin esto la pleamar salía dos horas antes.
        assertEquals("06:11", first.at.atZone(ZoneId.of("Europe/Madrid")).toLocalTime().toString())
    }

    @Test fun portugalUsesTheNearestSpanishPortNotLisbon() {
        val near = portsByDistance(38.72, -9.14).first().first   // Lisboa
        assertFalse(near.id == "31")
    }
}

class SunMoonTest {
    private val madrid = ZoneId.of("Europe/Madrid")

    @Test fun madridSummerSolstice() {
        val s = SunMoon.sun(LocalDate.of(2026, 6, 21), 40.4168, -3.7038, madrid)
        // Orto 6:44 y ocaso 21:48 (hora oficial, CEST); se admiten 3 minutos.
        assertClose("06:44", s.sunrise!!, madrid)
        assertClose("21:48", s.sunset!!, madrid)
    }

    @Test fun tenerifeIsAnHourBehindOnTheClock() {
        val z = ZoneId.of("Atlantic/Canary")
        val s = SunMoon.sun(LocalDate.of(2026, 12, 21), 28.46, -16.25, z)
        assertClose("07:53", s.sunrise!!, z)   // comprobado con la ecuación del USNO
    }

    @Test fun moonPhases() {
        // Luna llena del 24-nov-2026 (hacia las 15:53 UTC).
        val m = SunMoon.moon(LocalDate.of(2026, 11, 24), 40.4, -3.7, madrid, Instant.parse("2026-11-24T16:00:00Z"))
        assertEquals(MoonPhase.FULL, m.phase)
        assertTrue(m.illumination > 0.95)
        val next = SunMoon.nextPhases(Instant.parse("2026-11-20T00:00:00Z")).first()
        assertEquals(MoonPhase.FULL, next.first)
        assertTrue(Math.abs(next.second.epochSecond - Instant.parse("2026-11-24T15:53:00Z").epochSecond) < 18 * 3600)
    }

    private fun assertClose(hhmm: String, at: Instant, zone: ZoneId) {
        val t = at.atZone(zone).toLocalTime()
        val want = java.time.LocalTime.parse(hhmm)
        val diff = Math.abs(t.toSecondOfDay() - want.toSecondOfDay())
        assertTrue("$t frente a $hhmm", diff <= 180)
    }
}

class TempScaleTest {
    @Test fun bandsReadTheRoundedTemperature() {
        val s = TempScale.BANDS
        assertEquals(0x1F3A93, s.color(0.0))
        assertEquals(0x2E7BD6, s.color(1.0))
        assertEquals(0x1FAFAF, s.color(9.6))      // se enseña «10°»
        assertEquals(0xD62828, s.color(45.0))
        assertEquals(0x5B2C91, TempScale.CLASSIC.color(-20.0))
        assertEquals(0x6CC06A, TempScale.CLASSIC.color(22.0))   // verde, no amarillo, a 20–24°
    }

    @Test fun inkPicksTheBetterContrast() {
        assertTrue(TempPalette.prefersDarkInk(28.0, TempScale.BANDS))   // amarillo
        assertFalse(TempPalette.prefersDarkInk(-3.0, TempScale.BANDS))  // azul oscuro
        assertFalse(TempPalette.prefersDarkInk(22.0, TempScale.NIGHT))
        // El fondo baja a un tono más oscuro del mismo color.
        val (top, bot) = TempPalette.gradient(28.0, TempScale.BANDS)
        assertTrue(TempPalette.oklab(bot).first < TempPalette.oklab(top).first)
    }
}

class PlacesTest {
    private val places = Places(File("src/main/assets/Municipios.tsv").inputStream(), File("src/main/assets/Nucleos.tsv").inputStream(),
        File("src/main/assets/Recintos.bin").inputStream(), File("src/main/assets/Distritos.tsv").inputStream())

    @Test fun sanBlasIsMadridNotCoslada() {
        // Canillejas: el punto de Coslada está más cerca que el de Madrid (Sol).
        assertEquals("28079", places.nearestMunicipio(40.4410, -3.6080)?.code)
        assertEquals("28079", places.nearestMunicipio(40.4300, -3.6100)?.code)
        assertEquals("28049", places.nearestMunicipio(40.4238, -3.5613)?.code)
        assertEquals("San Blas-Canillejas", places.nearestNucleo(40.4300, -3.6100, "28079")?.name)
        assertEquals("33036", places.nearestMunicipio(43.4200, -4.7550)?.code) // Llanes
        assertEquals("38038", places.nearestMunicipio(28.4682, -16.2546)?.code) // Santa Cruz de Tenerife
    }

    @Test fun villageNotMunicipality() {
        // En Niembro, el municipio es Llanes pero el sitio es Niembro.
        val m = places.nearestMunicipio(43.438, -4.842)!!
        assertEquals("33036", m.code)
        assertEquals("Niembro", places.nearestNucleo(43.438, -4.842, m.code)!!.name)
    }

    @Test fun instantSearchFindsVillagesToo() {
        val r = places.search("niembro")
        assertEquals("Niembro", r.first().name)
        assertEquals("Llanes", r.first().municipality)
        assertEquals("Segovia", places.search("Segov").first().name)
    }
}

class AstroTest {
    private val madrid = java.time.ZoneId.of("Europe/Madrid")

    @Test fun novemberMoons() {
        val p = Astro.phases(Instant.parse("2026-11-01T00:00:00Z"), 2)
        // Luna nueva el 9-nov a las 07:02 UTC y llena el 24-nov a las 14:53 UTC.
        assertFalse(p[0].full); assertTrue(Math.abs(p[0].at.epochSecond - Instant.parse("2026-11-09T07:02:00Z").epochSecond) < 300)
        assertTrue(p[1].full); assertTrue(Math.abs(p[1].at.epochSecond - Instant.parse("2026-11-24T14:53:00Z").epochSecond) < 300)
    }

    @Test fun winterSolsticeAndShowers() {
        val ev = Astro.events(Instant.parse("2026-11-01T00:00:00Z"), 40.43, -3.61, madrid)
        val sol = ev.first { it.label == "Solsticio de invierno" }
        assertTrue(Math.abs(sol.at.epochSecond - Instant.parse("2026-12-21T20:50:00Z").epochSecond) < 600)
        assertTrue(ev.any { it.label == "Lluvia de Gemínidas" })
        assertTrue(ev.any { it.label == "Atardecer más temprano" })
        assertTrue(ev.zipWithNext().all { (a, b) -> !a.at.isAfter(b.at) })
    }
}
