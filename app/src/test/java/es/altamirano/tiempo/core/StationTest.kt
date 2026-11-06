package es.altamirano.tiempo.core

import es.altamirano.tiempo.data.WeatherRepo
import org.junit.Assert.assertEquals
import org.junit.Test

/** Los nombres de estación como `LocationStore.shortStationName` de iOS. */
class StationTest {
    @Test fun stationNamesLikeIos() {
        val n = WeatherRepo::stationName
        assertEquals("Llanes", n("LLANES"))
        assertEquals("Niembru-Llanes", n("EVC_NIEMBRU-LLANES"))
        assertEquals("El Goloso", n("MADRID, EL GOLOSO"))
        assertEquals("Retiro", n("MADRID/RETIRO"))
        assertEquals("Almería, Aeropuerto", n("ALMERÍA/AEROPUERTO"))
        assertEquals("Carreña de Cabrales", n("CARREÑA DE CABRALES"))
        assertEquals("Santa Eulalia del Campo", n("SANTA EULALIA  DEL CAMPO"))
        assertEquals("Sotres Parque Nacional Picos de Europa", n("SOTRES  PARQUE NACIONAL PICOS DE EUROPA"))
    }
}
