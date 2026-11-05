package es.altamirano.tiempo.data

import android.content.Context
import es.altamirano.tiempo.core.ChartData
import es.altamirano.tiempo.core.ChartPeriod
import es.altamirano.tiempo.core.ChartSource
import es.altamirano.tiempo.core.NetatmoCharts
import es.altamirano.tiempo.core.NetatmoDevice
import es.altamirano.tiempo.core.NetatmoModule
import es.altamirano.tiempo.core.NetatmoSnapshot
import es.altamirano.tiempo.net.Netatmo
import es.altamirano.tiempo.net.describe
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.Json
import java.time.ZoneId

/** Lo que pinta la pestaña Actual. */
data class NetatmoState(
    val station: NetatmoDevice? = null,
    val exterior: NetatmoModule? = null,
    val rain: NetatmoModule? = null,
    val loading: Boolean = false,
    val error: String? = null,
    val fetchedAgo: Long? = null,
)

/**
 * La estación elegida y sus módulos (`AppConfiguration`: `deviceId`, `moduleExterior`,
 * `moduleRain`, `stationLocation`). Se detectan solos desde la cuenta; si hay varias
 * estaciones, se elige en Ajustes.
 */
class NetatmoRepo(context: Context, val api: Netatmo) {
    private val prefs = context.getSharedPreferences("netatmo", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    var deviceId: String get() = prefs.getString("device", "")!!; private set(v) { prefs.edit().putString("device", v).apply() }
    var moduleExterior: String get() = prefs.getString("ext", "")!!; private set(v) { prefs.edit().putString("ext", v).apply() }
    var moduleRain: String get() = prefs.getString("rain", "")!!; private set(v) { prefs.edit().putString("rain", v).apply() }
    var stationLocation: String get() = prefs.getString("label", "")!!; private set(v) { prefs.edit().putString("label", v).apply() }

    private val _state = MutableStateFlow(NetatmoState())
    val state: StateFlow<NetatmoState> = _state.asStateFlow()

    /** Las estaciones que encontró la última detección, para elegir en Ajustes. */
    private val _stations = MutableStateFlow(api.cachedStations() ?: emptyList())
    val stations: StateFlow<List<NetatmoDevice>> = _stations.asStateFlow()

    /** La pestaña Actual sólo sale con Netatmo configurado, como en iOS. */
    private val _enabled = MutableStateFlow(api.configured)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()
    fun recheck() { _enabled.value = api.configured }

    fun snapshot(): NetatmoSnapshot? = prefs.getString("snapshot", null)?.let { runCatching { json.decodeFromString(NetatmoSnapshot.serializer(), it) }.getOrNull() }

    fun apply(d: NetatmoDevice) {
        deviceId = d.id
        moduleExterior = d.modules.firstOrNull { it.type == "NAModule1" }?.id ?: ""
        moduleRain = d.modules.firstOrNull { it.type == "NAModule3" }?.id ?: ""
        stationLocation = d.label
        _state.update { NetatmoState() }
    }

    /**
     * Pide la estación (como mucho cada 10 min, la caché lo decide), elige la configurada o la
     * primera y guarda la instantánea para el widget. Devuelve si cambió algo.
     */
    suspend fun load(force: Boolean = false): NetatmoSnapshot? {
        if (!api.configured) { _state.value = NetatmoState(error = "Configura Netatmo en Ajustes"); return null }
        _state.update { it.copy(loading = true, error = null) }
        return try {
            val (devices, age) = api.stations(force)
            _stations.value = devices
            val main = devices.firstOrNull { it.id == deviceId } ?: devices.firstOrNull()
            if (main != null && (main.id != deviceId || moduleExterior.isEmpty() && moduleRain.isEmpty())) apply(main)
            val ext = main?.module(moduleExterior, "NAModule1")
            val rain = main?.modules?.firstOrNull { it.id == moduleRain } ?: main?.modules?.firstOrNull { it.type == "NAModule3" }
            _state.value = NetatmoState(main, ext, rain, fetchedAgo = age,
                error = if (main == null) "No hay estaciones meteorológicas en la cuenta de Netatmo" else null)
            main?.let { m ->
                NetatmoSnapshot.make(m, ext, rain, stationLocation.ifEmpty { m.label }).also {
                    prefs.edit().putString("snapshot", json.encodeToString(NetatmoSnapshot.serializer(), it)).apply()
                }
            }
        } catch (e: Exception) {
            _state.update { it.copy(loading = false, error = describe(e)) }
            null
        }
    }

    /** La gráfica de una tarjeta (`NetatmoChartLoader`). */
    suspend fun chart(source: ChartSource, type: String, period: ChartPeriod, zone: ZoneId): Pair<ChartData?, String?> {
        val device = deviceId.ifEmpty { return null to null }
        val isRain = source == ChartSource.EXTERIOR && type == "Rain"
        val module = when (source) {
            ChartSource.INTERIOR -> device
            ChartSource.EXTERIOR -> {
                val configured = if (isRain) moduleRain else moduleExterior
                // Pedirle `sum_rain` a la base devuelve vacío siempre, que parecía «no ha llovido».
                if (isRain && configured.isEmpty()) return null to "No hay pluviómetro configurado."
                configured.ifEmpty { device }
            }
        }
        val begin = System.currentTimeMillis() / 1000 - period.days * 86400L
        return try {
            val pts = if (isRain) rainMeasure(device, module, period.scale, begin)
                else api.measure(device, module, period.scale, type, begin)
            val chart = NetatmoCharts.build(pts, period, isRain, zone)
            chart to (if (chart == null) "El módulo no devolvió medidas." else null)
        } catch (e: Exception) {
            null to describe(e)
        }
    }

    /**
     * Netatmo contesta «Service temporarily unavailable» a un `module_id` que ya no es de la
     * estación, igual que a una caída. Así que: en tiempo real, luego normal, y luego con el
     * pluviómetro vuelto a detectar (uno cambiado tiene otro id).
     */
    private suspend fun rainMeasure(device: String, module: String, scale: String, begin: Long): List<Pair<Long, Double>> {
        val rt = runCatching { api.measure(device, module, scale, "sum_rain", begin, realTime = true) }
        rt.getOrNull()?.takeIf { it.isNotEmpty() }?.let { return it }
        runCatching { api.measure(device, module, scale, "sum_rain", begin) }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { return it }
        val detected = runCatching { api.stations().first }.getOrNull()
            ?.let { ds -> (ds.firstOrNull { it.id == device } ?: ds.firstOrNull())?.modules?.firstOrNull { it.type == "NAModule3" }?.id }
        if (detected != null && detected != module) {
            runCatching { api.measure(device, detected, scale, "sum_rain", begin, realTime = true) }.getOrNull()
                ?.takeIf { it.isNotEmpty() }?.let { moduleRain = detected; return it }
        }
        return rt.getOrThrow()
    }
}
