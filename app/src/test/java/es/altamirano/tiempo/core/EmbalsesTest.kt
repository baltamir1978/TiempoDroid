package es.altamirano.tiempo.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate

/** Con el boletín del 10-nov-2026, comparado a mano con lo que enseña embalses.net. */
class EmbalsesTest {
    private fun res(name: String) = File(javaClass.classLoader!!.getResource(name)!!.toURI()).readText()

    @Test fun currentWeek() {
        val list = Embalses.parseCurrent(res("embalses-mapa.json"))
        assertEquals(375, list.size)
        val a = list.single { it.name == "Alcántara" }
        assertEquals(15, a.id); assertEquals(3160.0, a.capacity, 0.0); assertEquals(1427.0, a.current, 0.0)
        assertEquals(LocalDate.of(2026, 11, 10), a.date)
        assertEquals("Tajo", a.basin)
        // En grados (outSR=4326), cerca de la presa.
        assertEquals(39.7, a.lat, 0.1); assertEquals(-6.9, a.lon, 0.1)
        assertEquals(45.16, a.percent, 0.01)
        // España: embalses.net dice 55,31 %.
        assertEquals(55.3, Embalses.total(list).percent, 0.2)
    }

    @Test fun lastYearAndTenYearMean() {
        val h = Embalses.parseHistory(res("embalse-alcantara.json"))
        val day = LocalDate.of(2026, 11, 10)
        assertEquals(1427.0, h.last().hm3, 0.0)
        // embalses.net: misma semana 2025, 1.681 hm³; media de 10 años, 1.729.
        assertEquals(1681.0, Embalses.sameWeek(h, day)!!.hm3, 0.0)
        assertEquals(1729.0, Embalses.tenYearMean(h, day)!!, 0.5)
    }

    @Test fun hydrologicalYear() {
        assertEquals(LocalDate.of(2026, 10, 1), Embalses.hydroYearStart(LocalDate.of(2026, 11, 10)))
        assertEquals(LocalDate.of(2025, 10, 1), Embalses.hydroYearStart(LocalDate.of(2026, 9, 30)))
        val c = Embalses.yearCurves(Embalses.parseHistory(res("embalse-alcantara.json")), LocalDate.of(2026, 11, 10))
        assertEquals(6, c.thisYear.size)                       // seis martes desde el 1 de octubre
        assertTrue(c.lastYear.size in 51..53)
        assertTrue(c.mean.size >= 50)
        assertTrue(c.thisYear.all { it.first in 0..41 })
    }

    @Test fun colorBands() {
        assertEquals(Embalses.color(10.0), Embalses.color(19.9))
        assertTrue(Embalses.color(19.9) != Embalses.color(20.0))
        assertEquals(Embalses.color(90.0), Embalses.color(100.0))
    }
}
