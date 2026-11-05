package es.altamirano.tiempo.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.Instant
import java.time.ZoneId

class NewTest {
    private fun res(name: String) = File(javaClass.classLoader!!.getResource(name)!!.toURI()).readText()

    @Test fun netatmoStation() {
        val d = parseStationsData(res("netatmo-stations.json")).single()
        assertEquals("Real Sitio de San Ildefonso · 1192 m", d.label)
        assertEquals(40.9017, d.lat!!, 1e-6)
        val ext = d.module(null, "NAModule1")!!; val rain = d.module(null, "NAModule3")!!
        val s = NetatmoSnapshot.make(d, ext, rain, "La Granja")
        assertEquals(7.3, s.temperature!!, 1e-9); assertEquals(1018.6, s.pressure!!, 1e-9)
        assertEquals(0.2, s.rain!!, 1e-9); assertEquals(4.6, s.rainToday!!, 1e-9)
        assertEquals(20.4, s.tempIn!!, 1e-9); assertEquals(0, s.co2Level)
        assertEquals(22, rain.batteryPercent)
    }

    @Test(expected = NetatmoApiError::class) fun netatmoError() {
        parseStationsData("""{"error":{"code":26,"message":"User usage reached"}}""")
    }

    @Test fun measureAndChart() {
        val pts = parseMeasure("""{"body":{"1762300800":[1.5],"1762290000":[0.0],"1762311600":[null],"1762322400":[3.0]},"status":"ok"}""")
        assertEquals(listOf(1762290000L, 1762300800L, 1762322400L), pts.map { it.first })
        val c = NetatmoCharts.build(pts, ChartPeriod.WEEK, isRain = true, zone = ZoneId.of("Europe/Madrid"))!!
        assertEquals(4.5, c.total!!, 1e-9); assertEquals(2, c.rainyDays); assertEquals(3.0, c.max!!, 1e-9)
        assertNull(NetatmoCharts.build(emptyList(), ChartPeriod.WEEK, false, ZoneId.of("UTC")))
    }

    @Test fun ipmaForecast() {
        val f = Ipma.adapt(res("ipma-porto.json"))
        val days = f.daily!!.prediccion!!.dia!!
        assertTrue(days.size >= 3)
        assertEquals(16.2, days[0].temperatura!!.maxima!!, 1e-9)
        assertEquals("13", days[0].estadoCielo!![0].value)
        val hours = f.hourly!!.prediccion!!.dia!!.flatMap { it.temperatura!! }
        assertTrue(hours.size > 24)
        // -99 en la probabilidad es «sin dato», no se pinta.
        assertTrue(f.hourly!!.prediccion!!.dia!!.flatMap { it.probPrecipitacion!! }.none { it.value!!.startsWith("-") })
    }

    @Test fun ipmaWarnings() {
        val a = Ipma.alerts(res("ipma-warnings.json"), "PTO", Instant.parse("2026-11-05T12:00:00Z"))
        assertTrue(a.isNotEmpty())
        assertEquals("Lluvias", a.first().phenomenon)
        assertEquals("Porto", a.first().area)
        assertTrue(Ipma.alerts(res("ipma-warnings.json"), "PTO", Instant.parse("2026-12-01T00:00:00Z")).isEmpty())
    }

    @Test fun ipmaSourceAndZones() {
        assertEquals(ZoneId.of("Atlantic/Azores"), Ipma.location("pt-3420300")!!.zone)
        assertEquals(PortugalSource.IPMA, Ipma.source("pt-1131200", 41.15, -8.61, null))
        // Nazaré: con Leiria, pero a más de 25 km → Open-Meteo, salvo que se elija.
        assertEquals(PortugalSource.OPEN_METEO, Ipma.source("pt-1100900", 39.6012, -9.0709, null))
        assertEquals(PortugalSource.IPMA, Ipma.source("pt-1100900", 39.6012, -9.0709, PortugalSource.IPMA))
    }

    @Test fun portugalShape() {
        val shape = File("src/main/assets/Portugal.txt").inputStream().use { PortugalShape(it) }
        assertTrue(shape.contains(38.88, -7.16))     // Elvas
        assertTrue(shape.contains(32.65, -16.91))    // Funchal
        assertTrue(shape.portugal(37.74, -25.67))    // Ponta Delgada
        assertFalse(shape.portugal(37.19, -7.40))   // Ayamonte (ya en España por sus términos, pero tampoco aquí)
        assertFalse(shape.contains(38.88, -6.97))    // Badajoz
        assertFalse(shape.contains(40.42, -3.70))    // Madrid
    }

    @Test fun tideCurve() {
        val t0 = Instant.parse("2026-07-01T00:00:00Z")
        val c = TideCurve(listOf(Tide(t0, 1.0, false), Tide(t0.plusSeconds(6 * 3600), 4.0, true), Tide(t0.plusSeconds(12 * 3600), 1.0, false)))
        assertEquals(2.5, c.height(t0.plusSeconds(3 * 3600)), 1e-9)
        assertEquals(4.0, c.height(t0.plusSeconds(6 * 3600)), 1e-9)
        assertTrue(c.next(t0.plusSeconds(3600))!!.high)
        assertNotNull(c.next(t0.plusSeconds(7 * 3600)))
    }
}
