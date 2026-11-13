package es.altamirano.tiempo.data

import android.content.Context
import es.altamirano.tiempo.core.Embalses
import es.altamirano.tiempo.core.Reservoir
import es.altamirano.tiempo.core.Week
import es.altamirano.tiempo.net.DiskCache
import es.altamirano.tiempo.net.USER_AGENT
import es.altamirano.tiempo.net.http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.time.LocalDate

data class EmbalsesState(val list: List<Reservoir> = emptyList(), val loading: Boolean = false, val error: String? = null)

/**
 * Los embalses de MITECO, con la última respuesta buena en disco: el boletín sale los martes, así
 * que se vuelve a pedir cada 6 h (la lista) o 12 h (el histórico de cada embalse), y si falla la
 * red se enseña lo guardado.
 */
class EmbalsesRepo(context: Context) {
    private val cache = DiskCache(File(context.cacheDir, "embalses"))
    private val prefs = context.getSharedPreferences("embalses", Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(EmbalsesState(cache.load("lista")?.let { runCatching { Embalses.parseCurrent(it.first.decodeToString()) }.getOrNull() } ?: emptyList()))
    val state: StateFlow<EmbalsesState> = _state.asStateFlow()

    /** Los favoritos, en el orden en que se marcaron. */
    private val _favorites = MutableStateFlow(prefs.getString("favoritos", "")!!.split(',').mapNotNull { it.toIntOrNull() })
    val favorites: StateFlow<List<Int>> = _favorites.asStateFlow()
    fun toggleFavorite(id: Int) {
        _favorites.value = if (id in _favorites.value) _favorites.value - id else _favorites.value + id
        prefs.edit().putString("favoritos", _favorites.value.joinToString(",")).apply()
    }

    private suspend fun fetch(url: String): ByteArray = withContext(Dispatchers.IO) {
        http.newCall(Request.Builder().url(url).header("User-Agent", USER_AGENT).build()).execute().use {
            if (!it.isSuccessful) throw IOException("MITECO: HTTP ${it.code}")
            it.body?.bytes() ?: ByteArray(0)
        }
    }

    suspend fun load(force: Boolean = false) {
        val age = cache.load("lista")?.second
        if (!force && age != null && age < 6 * 3600 && _state.value.list.isNotEmpty()) return
        _state.value = _state.value.copy(loading = true, error = null)
        _state.value = try {
            val bytes = fetch(Embalses.CURRENT_URL)
            val list = withContext(Dispatchers.Default) { Embalses.parseCurrent(bytes.decodeToString()) }
            if (list.isEmpty()) throw IOException("MITECO no ha devuelto embalses")
            cache.save("lista", bytes)
            EmbalsesState(list)
        } catch (e: Exception) {
            _state.value.copy(loading = false, error = e.message ?: "Sin conexión")
        }
    }

    /** Las semanas de un embalse en los últimos once años. */
    suspend fun history(id: Int, force: Boolean = false): List<Week> {
        val key = "historico-$id"
        val cached = cache.load(key)
        if (!force && cached != null && cached.second < 12 * 3600) runCatching { return Embalses.parseHistory(cached.first.decodeToString()) }
        return try {
            val bytes = fetch(Embalses.historyUrl(id, LocalDate.now().minusYears(11).withDayOfMonth(1)))
            val weeks = withContext(Dispatchers.Default) { Embalses.parseHistory(bytes.decodeToString()) }
            if (weeks.isNotEmpty()) cache.save(key, bytes)
            weeks
        } catch (e: Exception) {
            cached?.let { runCatching { Embalses.parseHistory(it.first.decodeToString()) }.getOrNull() } ?: throw e
        }
    }
}
