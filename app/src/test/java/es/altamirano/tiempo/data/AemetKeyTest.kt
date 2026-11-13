package es.altamirano.tiempo.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Base64

class AemetKeyTest {
    private fun at(day: Int, hour: Int = 12) = LocalDateTime.of(2027, 2, day, hour, 0).atZone(ZoneId.systemDefault()).toInstant()
    /** Una clave con la forma de las de AEMET (un JWT), que caduca el 14-feb-2027 a mediodía. */
    private val key = "x." + Base64.getUrlEncoder().withoutPadding()
        .encodeToString("""{"sub":"a@b.es","iat":1,"exp":${at(14).epochSecond}}""".toByteArray()) + ".y"

    @Test fun expiryComesFromTheKey() = assertEquals(at(14).epochSecond, aemetKeyExpiry(key))

    @Test fun silentUntilTwoWeeksBefore() {
        assertNull(aemetKeyWarning(key, at(14).minusSeconds(15L * 86400 + 3600).toEpochMilli()))
        assertNull(aemetKeyWarning("", at(1).toEpochMilli()))
        assertNull(aemetKeyWarning("no-es-una-clave", at(1).toEpochMilli()))
    }

    @Test fun countsTheDaysLeft() {
        assertTrue(aemetKeyWarning(key, at(1).toEpochMilli())!!.contains("en 13 días"))
        assertTrue(aemetKeyWarning(key, at(13).toEpochMilli())!!.contains("mañana"))
        assertTrue(aemetKeyWarning(key, at(14, 9).toEpochMilli())!!.contains("hoy"))
    }

    @Test fun expired() {
        assertTrue(aemetKeyWarning(key, at(14, 13).toEpochMilli())!!.contains("ha caducado"))
        assertTrue(aemetKeyWarning(key, at(20).toEpochMilli())!!.contains("ha caducado"))
    }
}
