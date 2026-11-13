package es.altamirano.tiempo.net

import es.altamirano.common.secure.SecureFile
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import es.altamirano.tiempo.BuildConfig
import es.altamirano.tiempo.core.NetatmoApiError
import es.altamirano.tiempo.core.NetatmoDevice
import es.altamirano.tiempo.core.NetatmoJson
import es.altamirano.tiempo.core.parseMeasure
import es.altamirano.tiempo.core.parseStationsData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID

private const val API = "https://api.netatmo.com"
private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

/** Lo propio de Tiempo: credenciales de dev.netatmo.com y su sesión (`AppConfiguration`). */
@Serializable
data class NetatmoAccount(
    val clientId: String = "", val clientSecret: String = "", val refreshToken: String = "",
    val accessToken: String = "", val expiresAt: Long = 0,
) {
    val hasCredentials get() = clientId.isNotBlank() && clientSecret.isNotBlank()
    val hasSession get() = hasCredentials && refreshToken.isNotBlank()

    companion object {
        /** Como `Secrets.swift` en iOS: de `local.properties`, nunca en el repositorio. */
        val fallback get() = NetatmoAccount(BuildConfig.NETATMO_CLIENT_ID, BuildConfig.NETATMO_CLIENT_SECRET, BuildConfig.NETATMO_REFRESH_TOKEN)
    }
}

/**
 * La sesión de Calefacción, compartida: Netatmo cambia el `refresh_token` en cada renovación
 * y el viejo deja de valer, así que dos apps no pueden renovar la misma sesión por su cuenta.
 * Calefacción es la dueña y le da a Tiempo un `access_token` válido por su proveedor
 * (`es.altamirano.calefaccion.netatmo`), que sólo contesta a Tiempo firmada con su clave. Aquí
 * se comprueba lo mismo al revés: que quien contesta es Calefacción con la suya.
 */
class SharedSession(private val context: Context) {
    private var cached: Pair<String, Long>? = null

    fun available(): Boolean = providerPackage() != null

    private fun providerPackage(): String? {
        val info = runCatching { context.packageManager.resolveContentProvider(AUTHORITY, 0) }.getOrNull() ?: return null
        if (info.packageName != CALEFACCION) return null
        return info.packageName.takeIf { signedBy(context, it, CALEFACCION_CERTS) }
    }

    /** `stale`: el token que acaba de fallar, para que Calefacción renueve aunque no haya caducado. */
    suspend fun token(stale: String? = null): String? = withContext(Dispatchers.IO) {
        cached?.let { (t, exp) -> if (t != stale && System.currentTimeMillis() < exp - 120_000) return@withContext t }
        providerPackage() ?: return@withContext null
        val b: Bundle = runCatching {
            context.contentResolver.call(Uri.parse("content://$AUTHORITY"), "token", stale, null)
        }.getOrNull() ?: return@withContext null
        val t = b.getString("access_token") ?: return@withContext null
        cached = t to b.getLong("expires_at", System.currentTimeMillis() + 3600_000)
        t
    }

    fun forget() { cached = null }

    companion object {
        const val AUTHORITY = "es.altamirano.calefaccion.netatmo"
        const val CALEFACCION = "es.altamirano.calefaccion"
        /** SHA-256 del certificado de release de Calefacción; el de depuración de este equipo, sólo al depurar. */
        val CALEFACCION_CERTS = setOfNotNull(
            "B9BDC5D8F4389F719D0DF38DE8F18F9F065DA8A2FA155F87F1241EA5E99937F1",
            "1389502CE36317BFB67513DF22DD4033809FE39B1183D67B295C0E26814BFA53".takeIf { BuildConfig.DEBUG },
        )

        fun signedBy(context: Context, pkg: String, certs: Set<String>): Boolean = runCatching {
            val info = context.packageManager.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES)
            val signers = info.signingInfo?.apkContentsSigners ?: return false
            signers.any { s -> MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02X".format(it) } in certs }
        }.getOrDefault(false)
    }
}

sealed class NetatmoError(message: String) : Exception(message) {
    class NotConfigured : NetatmoError("Netatmo no está configurado. Ve a Ajustes.")
    class Http(val status: Int, val code: Int?, detail: String?) : NetatmoError("Netatmo: ${detail ?: "error $status"}${code?.let { " ($it)" } ?: ""}")
    class Network(e: IOException) : NetatmoError(e.message ?: "sin red")
    class OAuth(reason: String) : NetatmoError(reason)
}

/**
 * La API de estaciones de Netatmo (`NetatmoService.swift`). **Netatmo limita las consultas
 * periódicas desde el 1-mar-2027**: la estación se pide como mucho cada 10 minutos (es lo que
 * tarda en medir) y la respuesta se guarda en disco, la misma para la app y el widget.
 */
class Netatmo(private val context: Context) {
    private val store = SecureFile(context, "netatmo.bin", alias = "tiempo")
    val shared = SharedSession(context)
    private val cache = DiskCache(File(context.cacheDir, "netatmo"))
    private val refreshLock = Mutex()
    private val stateFile = SecureFile(context, "netatmo-oauth-state.bin", alias = "tiempo")
    /** En disco: Android puede matar la app mientras se inicia sesión en el navegador. */
    private var pendingState: String?
        get() = stateFile.read()
        set(value) { if (value == null) stateFile.clear() else stateFile.write(value) }

    private val _account = MutableStateFlow(load())
    val account: StateFlow<NetatmoAccount> = _account.asStateFlow()

    private fun load(): NetatmoAccount =
        store.read()?.let { runCatching { json.decodeFromString<NetatmoAccount>(it) }.getOrNull() } ?: NetatmoAccount.fallback

    private fun save(a: NetatmoAccount) { store.write(json.encodeToString(NetatmoAccount.serializer(), a)); _account.value = a }

    /** Hay con qué hablar con Netatmo: la sesión de Calefacción o la propia. */
    val configured get() = _account.value.hasSession || shared.available()

    fun saveCredentials(clientId: String, clientSecret: String, refreshToken: String) {
        val a = _account.value
        save(a.copy(clientId = clientId.trim(), clientSecret = clientSecret.trim(), refreshToken = refreshToken.trim(),
            accessToken = if (refreshToken.trim() == a.refreshToken) a.accessToken else "", expiresAt = if (refreshToken.trim() == a.refreshToken) a.expiresAt else 0))
    }

    fun logout() = save(_account.value.copy(refreshToken = "", accessToken = "", expiresAt = 0))

    // --- Inicio de sesión en el navegador ---------------------------------------------------

    fun authorizationUri(): Uri {
        val a = _account.value
        if (!a.hasCredentials) throw NetatmoError.NotConfigured()
        val state = UUID.randomUUID().toString().also { pendingState = it }
        return Uri.parse("$API/oauth2/authorize").buildUpon()
            .appendQueryParameter("client_id", a.clientId).appendQueryParameter("redirect_uri", REDIRECT_URI)
            .appendQueryParameter("scope", SCOPE).appendQueryParameter("state", state)
            .appendQueryParameter("response_type", "code").build()
    }

    suspend fun handleCallback(uri: Uri) {
        uri.getQueryParameter("error")?.let { throw NetatmoError.OAuth(it) }
        val code = uri.getQueryParameter("code") ?: throw NetatmoError.OAuth("No llegó el código de autorización")
        val expected = pendingState
        pendingState = null
        if (expected == null || uri.getQueryParameter("state") != expected) throw NetatmoError.OAuth("El parámetro «state» no coincide")
        val a = _account.value
        save(token(a, mapOf("grant_type" to "authorization_code", "client_id" to a.clientId, "client_secret" to a.clientSecret,
            "code" to code, "redirect_uri" to REDIRECT_URI, "scope" to SCOPE)))
    }

    // --- Tokens ---------------------------------------------------------------------------

    private suspend fun ownToken(stale: String? = null): String? = refreshLock.withLock {
        val a = _account.value
        if (!a.hasSession) return null
        if (a.accessToken.isNotEmpty() && a.accessToken != stale && System.currentTimeMillis() < a.expiresAt - 120_000) return a.accessToken
        val fresh = token(a, mapOf("grant_type" to "refresh_token", "refresh_token" to a.refreshToken,
            "client_id" to a.clientId, "client_secret" to a.clientSecret))
        save(fresh)
        fresh.accessToken
    }

    private suspend fun token(a: NetatmoAccount, params: Map<String, String>): NetatmoAccount = withContext(Dispatchers.IO) {
        val req = Request.Builder().url("$API/oauth2/token").post(FormBody.Builder().apply { params.forEach { (k, v) -> add(k, v) } }.build()).build()
        val (code, text) = try { http.newCall(req).execute().use { it.code to (it.body?.string() ?: "") } } catch (e: IOException) { throw NetatmoError.Network(e) }
        val o = runCatching { NetatmoJson.parseToJsonElement(text) as JsonObject }.getOrNull()
        if (code !in 200..299 || o?.get("access_token") == null) {
            throw NetatmoError.OAuth("Netatmo no renovó la sesión: ${o?.get("error")?.jsonPrimitive?.content ?: "error $code"}")
        }
        val expires = o["expires_in"]?.jsonPrimitive?.longOrNull ?: o["expire_in"]?.jsonPrimitive?.longOrNull ?: 10800
        a.copy(accessToken = o["access_token"]!!.jsonPrimitive.content,
            refreshToken = o["refresh_token"]?.jsonPrimitive?.content ?: a.refreshToken,
            expiresAt = System.currentTimeMillis() + expires * 1000)
    }

    /**
     * Una llamada a la API: con la sesión propia si la hay; si no, con la de Calefacción. Si el
     * token falla (401/403), se renueva y se reintenta una vez; si la de Calefacción no tiene
     * permiso para estaciones (`read_station`), se prueba la propia.
     */
    private suspend fun call(path: String, query: Map<String, String>): String {
        val sources = buildList<suspend (String?) -> String?> {
            if (_account.value.hasSession) add { s -> ownToken(s) }
            add { s -> shared.token(s) }
        }
        var last: Exception = NetatmoError.NotConfigured()
        for (source in sources) {
            var token = try { source(null) } catch (e: Exception) { last = e; null } ?: continue
            var attempt = 0
            while (true) {
                val url = "$API$path".toHttpUrl().newBuilder().apply { query.forEach { (k, v) -> addQueryParameter(k, v) } }.build()
                val (code, text) = withContext(Dispatchers.IO) {
                    try { http.newCall(Request.Builder().url(url).header("Authorization", "Bearer $token").build()).execute().use { it.code to (it.body?.string() ?: "") } }
                    catch (e: IOException) { throw NetatmoError.Network(e) }
                }
                if (code in 200..299) return text
                val err = runCatching { (NetatmoJson.parseToJsonElement(text) as JsonObject)["error"] as? JsonObject }.getOrNull()
                last = NetatmoError.Http(code, err?.get("code")?.jsonPrimitive?.content?.toIntOrNull(), err?.get("message")?.jsonPrimitive?.content)
                if ((code == 401 || code == 403) && attempt++ == 0) {
                    token = try { source(token) } catch (e: Exception) { null } ?: break
                } else break
            }
        }
        throw last
    }

    // --- Estaciones -------------------------------------------------------------------------

    /** Las estaciones de la cuenta; con caché de 10 min (`force` sólo la salta si tiene 1 min). */
    suspend fun stations(force: Boolean = false): Pair<List<NetatmoDevice>, Long> {
        val c = cache.load("stations")
        val maxAge = if (force) 60L else 600L
        if (c != null && c.second < maxAge) runCatching { return parseStationsData(String(c.first)) to c.second }
        return try {
            val text = call("/api/getstationsdata", mapOf("get_favorites" to "false"))
            val parsed = parseStationsData(text)
            cache.save("stations", text.toByteArray())
            parsed to 0L
        } catch (e: Exception) {
            c?.let { runCatching { parseStationsData(String(it.first)) to it.second }.getOrNull() } ?: throw e
        }
    }

    fun cachedStations(): List<NetatmoDevice>? = cache.load("stations")?.let { runCatching { parseStationsData(String(it.first)) }.getOrNull() }

    /**
     * `getmeasure`. Con `realTime` también sale el tramo en curso (sin él, la lluvia de las
     * últimas 3 h no aparece hasta que el tramo acaba). Caché de 10 min en la semana y de 1 h en
     * los periodos largos, que sólo cambian por días.
     */
    suspend fun measure(deviceId: String, moduleId: String, scale: String, type: String, dateBegin: Long, realTime: Boolean = false): List<Pair<Long, Double>> {
        val key = "m_${moduleId}_${type}_${scale}_${realTime}".replace(':', '-')
        val maxAge = if (scale == "3hours") 600L else 3600L
        cache.load(key)?.takeIf { it.second < maxAge }?.let { c -> runCatching { return parseMeasure(String(c.first)) } }
        val text = call("/api/getmeasure", mapOf("device_id" to deviceId, "module_id" to moduleId, "scale" to scale, "type" to type,
            "date_begin" to dateBegin.toString(), "optimize" to "false", "real_time" to realTime.toString()))
        val parsed = parseMeasure(text)
        if (parsed.isNotEmpty()) cache.save(key, text.toByteArray())
        return parsed
    }

    companion object {
        const val SCOPE = "read_station"
        /** Hay que darla de alta en dev.netatmo.com para iniciar sesión desde Tiempo. */
        const val REDIRECT_URI = "tiempo-netatmo://oauth-callback"
    }
}

/** Por si a alguien le sirve el error de Netatmo con su código. */
fun describe(e: Throwable): String = when (e) {
    is NetatmoApiError -> e.message + (e.code?.let { " ($it)" } ?: "")
    else -> e.message ?: e.toString()
}
