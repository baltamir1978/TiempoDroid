package es.altamirano.tiempo.core

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class I18nTest {
    private fun load(lang: String) = I18n.install(lang, File("src/main/assets/i18n/$lang.tsv").readText())

    @Test fun exactPatternsAndCase() {
        load("en"); load("eu"); load("gl")
        assertEquals("Next 48 hours", I18n.tr("Próximas 48 horas", "en"))
        assertEquals("OUTDOOR", I18n.tr("EXTERIOR", "en"))
        assertEquals("  Now", I18n.tr("  Ahora", "en"))
        assertEquals("Rises 08:12 · sets 19:40", I18n.tr("Sale 08:12 · se pone 19:40", "en"))
        // El hueco también se traduce si está en la tabla.
        assertEquals("Thunderstorms · level yellow", I18n.tr("Tormentas · nivel amarillo", "en"))
        assertEquals("Moderate · max 7", I18n.tr("Moderado · máx 7", "en"))
        assertEquals("From today evenings start getting longer · 17:48", I18n.tr("Desde hoy las tardes empiezan a alargarse · 17:48", "en"))
        // Orden cambiado en euskera.
        assertEquals("Retiro estazioa · 3,2 km-ra", I18n.tr("Estación Retiro · a 3,2 km", "eu"))
        assertEquals("Perseidak izar-euria", I18n.tr("Lluvia de Perseidas", "eu"))
        assertEquals("Pouco nubrado", I18n.tr("Poco nuboso", "gl"))
        // Lo que no está, tal cual; y el español, siempre tal cual.
        assertEquals("Villaviciosa", I18n.tr("Villaviciosa", "en"))
        assertEquals("Próximas 48 horas", I18n.tr("Próximas 48 horas", "es"))
    }
}
