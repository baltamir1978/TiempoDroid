package es.altamirano.tiempo.core

import java.io.InputStream
import java.text.Normalizer
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Los sitios, sin red: los 8.122 municipios de AEMET (`Municipios.tsv`, sacado de su maestro) y
 * los ~29.000 núcleos del nomenclátor de iOS (`Nucleos.tsv`, GeoNames, CC BY 4.0). Sustituyen al
 * geocodificador, que sin Google Play no tiene servicio detrás (GrapheneOS).
 */
data class Municipio(val code: String, val name: String, val lat: Double, val lon: Double)
data class Nucleo(val name: String, val lat: Double, val lon: Double, val ine: String, val population: Int)

data class Suggestion(val code: String, val name: String, val municipality: String?, val lat: Double, val lon: Double, val rank: Int)

class Places(municipiosTsv: InputStream, nucleosTsv: InputStream, recintos: InputStream? = null, distritos: InputStream? = null) {
    val municipios: List<Municipio> = municipiosTsv.bufferedReader().lineSequence().mapNotNull { l ->
        val f = l.split('\t'); if (f.size < 4) null else Municipio(f[0], f[1], f[2].toDouble(), f[3].toDouble())
    }.toList()
    private val byCode = municipios.associateBy { it.code }

    /**
     * Ordenados por latitud (el fichero de iOS casi lo está, no del todo), con los distritos de
     * las ciudades grandes (`Distritos.tsv`), que el nomenclátor no trae: en San Blas salía
     * «Las Mercedes».
     */
    val nucleos: List<Nucleo> = listOfNotNull(nucleosTsv, distritos).flatMap { s ->
        s.bufferedReader().lineSequence().mapNotNull { l ->
            val f = l.split('\t'); if (f.size != 5) null
            else f[0].toDoubleOrNull()?.let { la -> f[1].toDoubleOrNull()?.let { lo -> Nucleo(f[4], la, lo, f[2], f[3].toIntOrNull() ?: 0) } }
        }.toList()
    }.sortedBy { it.lat }

    fun municipio(code: String) = byCode[code]

    private val terms: Recintos? = recintos?.let { Recintos(it) }

    /**
     * El municipio en cuyo término cae el punto (`Recintos.bin`); si no cae en ninguno (en el
     * mar, en Portugal) o AEMET no lo tiene, el de punto más cercano. Sólo con el punto no basta:
     * el de Madrid está en Sol, y desde San Blas queda más cerca el de Coslada.
     */
    fun nearestMunicipio(lat: Double, lon: Double): Municipio? =
        terms?.containing(lat, lon)?.let { byCode["%05d".format(it)] }
            ?: municipios.minByOrNull { sqDist(it.lat, it.lon, lat, lon) }

    /**
     * El núcleo habitado más cercano (a 4 km como mucho), dentro del municipio si se sabe: así
     * no se nombra un pueblo del concejo de al lado (`Nomenclator.nearest`).
     */
    fun nearestNucleo(lat: Double, lon: Double, ine: String? = null): Nucleo? {
        val band = MAX_KM / 111.0
        var i = lowerBound(lat - band)
        var best: Nucleo? = null; var bestKm = Double.MAX_VALUE
        while (i < nucleos.size && nucleos[i].lat <= lat + band) {
            val n = nucleos[i++]
            if (!ine.isNullOrEmpty() && n.ine.isNotEmpty() && n.ine != ine) continue
            val km = distanceKm(lat, lon, n.lat, n.lon)
            if (km < bestKm) { bestKm = km; best = n }
        }
        return if (bestKm <= MAX_KM) best else null
    }

    private fun lowerBound(lat: Double): Int {
        var lo = 0; var hi = nucleos.size
        while (lo < hi) { val mid = (lo + hi) / 2; if (nucleos[mid].lat < lat) lo = mid + 1 else hi = mid }
        return lo
    }

    // --- Buscador instantáneo (`LocationSearch.swift`) ---------------------------------------

    private val index: List<Pair<String, Suggestion>> by lazy {
        val m = municipios.map { normalize(it.name) to Suggestion(it.code, it.name, null, it.lat, it.lon, 0) }
        val n = nucleos.filter { it.ine.isNotEmpty() && byCode[it.ine] != null }
            .filter { nu -> normalize(nu.name) != normalize(byCode[nu.ine]!!.name) }
            .map { nu -> normalize(nu.name) to Suggestion(nu.ine, nu.name, byCode[nu.ine]!!.name, nu.lat, nu.lon, 1) }
        m + n
    }

    private val population: Map<String, Int> by lazy { nucleos.associate { normalize(it.name) + "|" + it.ine to it.population } }

    /**
     * Desde la primera letra y sin red. Primero lo que empieza por lo escrito, el municipio
     * antes que sus pueblos y, entre homónimos, el más poblado.
     */
    fun search(query: String, limit: Int = 30): List<Suggestion> {
        val q = normalize(query)
        if (q.isEmpty()) return emptyList()
        return index.asSequence().filter { q in it.first }
            .sortedWith(compareBy<Pair<String, Suggestion>>({ !it.first.startsWith(q) }, { it.second.rank },
                { -(population[it.first + "|" + it.second.code] ?: 0) }, { it.first }))
            .map { it.second }.take(limit).toList()
    }

    companion object {
        const val MAX_KM = 4.0

        fun normalize(s: String): String = Normalizer.normalize(s.trim(), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "").lowercase()

        fun distanceKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val r = 6371.0
            val dLat = (lat2 - lat1) * PI / 180; val dLon = (lon2 - lon1) * PI / 180
            val a = sin(dLat / 2) * sin(dLat / 2) + cos(lat1 * PI / 180) * cos(lat2 * PI / 180) * sin(dLon / 2) * sin(dLon / 2)
            return 2 * r * asin(sqrt(a))
        }

        private fun sqDist(lat: Double, lon: Double, la: Double, lo: Double): Double {
            val dLat = lat - la; val dLon = (lon - lo) * cos(la * PI / 180)
            return dLat * dLat + dLon * dLon
        }
    }
}

/** Los términos municipales del IGN (`Tools/build_recintos.py`): punto en polígono, par-impar. */
class Recintos(input: InputStream) {
    private class Term(val code: Int, val rings: List<ShortArray>, val x0: Int, val y0: Int, val x1: Int, val y1: Int)
    private val sx: Double; private val sy: Double; private val tx: Double; private val ty: Double
    private val terms: List<Term>

    init {
        val d = java.io.DataInputStream(input.buffered())
        val magic = ByteArray(4).also { d.readFully(it) }
        require(String(magic) == "REC1") { "Recintos.bin no válido" }
        sx = d.readDouble(); sy = d.readDouble(); tx = d.readDouble(); ty = d.readDouble()
        terms = List(d.readInt()) {
            val code = d.readInt()
            var x0 = Int.MAX_VALUE; var y0 = Int.MAX_VALUE; var x1 = Int.MIN_VALUE; var y1 = Int.MIN_VALUE
            val rings = List(d.readShort().toInt()) {
                val r = ShortArray(d.readInt() * 2)
                for (i in r.indices) r[i] = d.readShort()
                for (i in r.indices step 2) {
                    val x = r[i].toInt(); val y = r[i + 1].toInt()
                    if (x < x0) x0 = x; if (x > x1) x1 = x; if (y < y0) y0 = y; if (y > y1) y1 = y
                }
                r
            }
            Term(code, rings, x0, y0, x1, y1)
        }
    }

    /** Código INE del municipio que contiene el punto, o null. */
    fun containing(lat: Double, lon: Double): Int? {
        val x = (lon - tx) / sx; val y = (lat - ty) / sy
        return terms.firstOrNull { t -> x >= t.x0 && x <= t.x1 && y >= t.y0 && y <= t.y1 && inside(t.rings, x, y) }?.code
    }

    private fun inside(rings: List<ShortArray>, x: Double, y: Double): Boolean {
        var c = false
        for (r in rings) {
            var j = r.size - 2
            for (i in r.indices step 2) {
                val xi = r[i].toDouble(); val yi = r[i + 1].toDouble(); val xj = r[j].toDouble(); val yj = r[j + 1].toDouble()
                if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) c = !c
                j = i
            }
        }
        return c
    }
}

// --- Mareas (IHM) ---------------------------------------------------------------------------

data class TideStation(val id: String, val name: String, val lat: Double, val lon: Double, val tz: String? = null)

/** Los puertos del IHM (el catálogo de `TidesService.swift`). */
val IHM_STATIONS = listOf(
    TideStation("20", "A Coruña", 43.37, -8.40), TideStation("71", "A Guarda", 41.90, -8.87), TideStation("49", "Algeciras", 36.13, -5.44),
    TideStation("14", "Alúmina Española (San Cibrao)", 43.70, -7.43), TideStation("57", "Arinaga (Gran Canaria)", 27.85, -15.39),
    TideStation("53", "Arrecife (Lanzarote)", 28.96, -13.55), TideStation("7", "Avilés (San Juan de Nieva)", 43.58, -5.95),
    TideStation("32", "Ayamonte", 37.21, -7.41), TideStation("30", "Baiona", 42.12, -8.85), TideStation("47", "Barbate", 36.19, -5.92),
    TideStation("72", "Bermeo", 43.42, -2.72), TideStation("2", "Bilbao", 43.35, -3.04), TideStation("37", "Bonanza (Sanlúcar de Barrameda)", 36.81, -6.34),
    TideStation("13", "Burela", 43.66, -7.35), TideStation("42", "Cádiz", 36.53, -6.28), TideStation("22", "Camariñas", 43.13, -9.18),
    TideStation("16", "Cariño", 43.74, -7.87), TideStation("17", "Cedeira", 43.66, -8.06), TideStation("51", "Ceuta", 35.89, -5.31),
    TideStation("39", "Chipiona", 36.74, -6.43), TideStation("15", "Cillero (Ría de Viveiro)", 43.67, -7.59), TideStation("46", "Conil", 36.28, -6.09),
    TideStation("8", "Cudillero", 43.56, -6.15), TideStation("41", "El Puerto de Santa María", 36.60, -6.23), TideStation("18", "Ferrol", 43.48, -8.24),
    TideStation("23", "Fisterra", 42.91, -9.27), TideStation("12", "Foz", 43.57, -7.26), TideStation("44", "Gallineras", 36.44, -6.21),
    TideStation("6", "Gijón", 43.55, -5.70), TideStation("64", "Granadilla (Tenerife)", 28.07, -16.50), TideStation("34", "Isla Cristina", 37.20, -7.32),
    TideStation("43", "La Carraca", 36.49, -6.20), TideStation("70", "Langosteira (A Coruña exterior)", 43.35, -8.50),
    TideStation("31", "Lisboa", 38.71, -9.14, "Europe/Lisbon"), TideStation("4", "Llanes", 43.42, -4.75),
    TideStation("63", "Los Cristianos (Tenerife)", 28.05, -16.72), TideStation("61", "Los Gigantes (Tenerife)", 28.24, -16.84),
    TideStation("21", "Malpica", 43.32, -8.81), TideStation("33", "Marina de Isla Canela", 37.18, -7.36), TideStation("28", "Marín (Ría de Pontevedra)", 42.39, -8.70),
    TideStation("36", "Mazagón (Huelva)", 37.13, -6.83), TideStation("55", "Morro Jable (Fuerteventura)", 28.05, -14.35), TideStation("9", "Navia", 43.54, -6.72),
    TideStation("1", "Pasajes", 43.32, -1.92), TideStation("58", "Pasito Blanco (Gran Canaria)", 27.75, -15.66),
    TideStation("24", "Portosín (Ría de Muros y Noia)", 42.76, -8.94), TideStation("62", "Puerto de la Cruz (Tenerife)", 28.42, -16.55),
    TideStation("67", "Puerto de la Estaca (El Hierro)", 27.78, -17.89), TideStation("56", "Puerto de la Luz (Gran Canaria)", 28.14, -15.42),
    TideStation("59", "Puerto de las Nieves (Gran Canaria)", 28.10, -15.71), TideStation("54", "Puerto del Rosario (Fuerteventura)", 28.50, -13.86),
    TideStation("35", "Punta Umbría", 37.18, -6.96), TideStation("11", "Ribadeo", 43.54, -7.04), TideStation("5", "Ribadesella", 43.46, -5.06),
    TideStation("40", "Rota", 36.62, -6.36), TideStation("19", "Sada Fontán (Ría de Betanzos)", 43.35, -8.25),
    TideStation("65", "San Sebastián de la Gomera", 28.09, -17.11), TideStation("66", "Santa Cruz de La Palma", 28.68, -17.76),
    TideStation("60", "Santa Cruz de Tenerife", 28.47, -16.25), TideStation("25", "Santa Uxía de Ribeíra (Ría de Arousa)", 42.55, -8.99),
    TideStation("45", "Sancti Petri", 36.39, -6.21), TideStation("3", "Santander", 43.46, -3.80), TideStation("27", "Sanxenxo (Ría de Pontevedra)", 42.40, -8.81),
    TideStation("38", "Sevilla", 37.33, -6.00), TideStation("50", "Sotogrande", 36.28, -5.27), TideStation("52", "Tánger", 35.78, -5.81, "Africa/Casablanca"),
    TideStation("10", "Tapia", 43.57, -6.94), TideStation("48", "Tarifa", 36.01, -5.61), TideStation("29", "Vigo", 42.24, -8.73),
    TideStation("26", "Vilagarcía (Ría de Arousa)", 42.60, -8.77),
)

/**
 * Los puertos por cercanía. De Portugal el IHM sólo sirve Lisboa, que queda estuario arriba y da
 * medio metro de más: se salta, y vale más A Guarda o Ayamonte.
 */
fun portsByDistance(lat: Double, lon: Double): List<Pair<TideStation, Double>> =
    IHM_STATIONS.filter { it.id != "31" }.map { it to Places.distanceKm(lat, lon, it.lat, it.lon) }.sortedBy { it.second }

data class Tide(val at: java.time.Instant, val height: Double, val high: Boolean)
