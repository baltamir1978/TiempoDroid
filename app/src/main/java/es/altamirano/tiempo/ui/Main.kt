@file:OptIn(ExperimentalMaterial3Api::class)

package es.altamirano.tiempo.ui

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Brightness4
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FormatListBulleted
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import es.altamirano.tiempo.app
import es.altamirano.tiempo.core.Places
import es.altamirano.tiempo.core.SunMoon
import es.altamirano.tiempo.core.TempPalette
import es.altamirano.tiempo.core.TempScale
import es.altamirano.tiempo.core.Tide
import es.altamirano.tiempo.core.portsByDistance
import es.altamirano.tiempo.data.LocationStore
import es.altamirano.tiempo.data.SavedLocation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

private val Spanish = Locale("es", "ES")
private val hhmm = DateTimeFormatter.ofPattern("HH:mm")

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { TiempoTheme { Root() } }
    }
}

@Composable
private fun Root() {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var page by rememberSaveable { mutableIntStateOf(0) }
    Scaffold(containerColor = MaterialTheme.colorScheme.background, bottomBar = {
        NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
            listOf(Triple("Tiempo", Icons.Filled.Cloud, 0), Triple("Sol·Luna", Icons.Filled.Brightness4, 1), Triple("Ajustes", Icons.Filled.Settings, 2)).forEach { (label, icon, i) ->
                NavigationBarItem(tab == i, { tab = i }, { Icon(icon, null) }, label = { Text(label) },
                    colors = NavigationBarItemDefaults.colors(selectedIconColor = MaterialTheme.colorScheme.primary,
                        selectedTextColor = MaterialTheme.colorScheme.primary, indicatorColor = MaterialTheme.colorScheme.primaryContainer))
            }
        }
    }) { padding ->
        Box(Modifier.padding(bottom = padding.calculateBottomPadding()).fillMaxSize()) {
            when (tab) {
                0 -> WeatherTab(page) { page = it }
                1 -> CosmosTab(page)
                else -> SettingsTab()
            }
        }
    }
}

// --- Tiempo: una página por ubicación ------------------------------------------------------

@Composable
private fun WeatherTab(initialPage: Int, onPage: (Int) -> Unit) {
    val app = LocalContext.current.app
    val saved by app.locations.saved.collectAsStateWithLifecycle()
    val current by app.locations.current.collectAsStateWithLifecycle()
    val pages by app.weather.pages.collectAsStateWithLifecycle()
    val all = listOfNotNull(current) + saved
    val scope = rememberCoroutineScope()
    var manage by remember { mutableStateOf(false) }
    val askLocation = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) scope.launch { app.locations.locate()?.let { l -> app.snapshots.save(l, app.weather.load(l)) } }
    }

    LaunchedEffect(Unit) {
        if (app.locations.hasPermission()) app.locations.locate() else askLocation.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
    }
    if (manage) { BackHandler { manage = false }; LocationsSheet { manage = false }; return }
    if (all.isEmpty()) return
    val pager = rememberPagerState(initialPage.coerceIn(0, all.lastIndex)) { all.size }
    LaunchedEffect(pager.currentPage) { onPage(pager.currentPage) }
    val loc = all[pager.currentPage.coerceIn(0, all.lastIndex)]

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Column {
                    Text((if (loc.isCurrent) "📍 " else "") + loc.name, fontWeight = FontWeight.Bold, maxLines = 1)
                    Row(horizontalArrangement = Arrangement.spacedBy(5.dp), modifier = Modifier.padding(top = 3.dp)) {
                        all.indices.forEach { i ->
                            Box(Modifier.size(6.dp).clip(CircleShape).background(if (i == pager.currentPage) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)))
                        }
                    }
                }
            },
            actions = {
                IconButton(onClick = { scope.launch { app.snapshots.save(loc, app.weather.load(loc, force = true)) } }) { Icon(Icons.Filled.Refresh, "Actualizar") }
                IconButton(onClick = { manage = true }) { Icon(Icons.Filled.FormatListBulleted, "Ciudades") }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background, actionIconContentColor = MaterialTheme.colorScheme.primary),
        )
        HorizontalPager(pager, Modifier.fillMaxSize(), beyondViewportPageCount = 1) { i ->
            val l = all[i]
            val key = LocationStore.keyOf(l)
            val w = pages[key] ?: remember(key) { app.weather.primed(l) }
            // Cada página carga la suya (y la de al lado se prepara por adelantado).
            LaunchedEffect(key, l.municipio) { if (!w.loading) app.snapshots.save(l, app.weather.load(l)) }
            CityPage(l, w)
        }
    }
}

/** Las ciudades seguidas y el buscador instantáneo, sin red (`LocationManagerSheet`). */
@Composable
private fun LocationsSheet(onClose: () -> Unit) {
    val app = LocalContext.current.app
    val saved by app.locations.saved.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf(emptyList<es.altamirano.tiempo.core.Suggestion>()) }
    LaunchedEffect(query) { results = withContext(Dispatchers.Default) { app.places.search(query) } }
    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Text("Ciudades", fontWeight = FontWeight.Bold) }, actions = { TextButton(onClick = onClose) { Text("Listo") } },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background))
        OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().padding(16.dp, 4.dp), singleLine = true,
            placeholder = { Text("Buscar municipio o pueblo") }, leadingIcon = { Icon(Icons.Filled.Search, null) }, shape = RoundedCornerShape(12.dp))
        LazyColumn(Modifier.fillMaxSize()) {
            if (query.isBlank()) {
                items(saved, key = { LocationStore.keyOf(it) }) { l ->
                    ListItem(headlineContent = { Text(l.name) }, supportingContent = l.province?.let { { Text(it) } },
                        trailingContent = { IconButton(onClick = { app.locations.remove(l) }) { Icon(Icons.Filled.Delete, "Quitar", tint = MaterialTheme.colorScheme.error) } },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            } else items(results, key = { it.code + it.name + it.lat }) { s ->
                ListItem(headlineContent = { Text(s.name) },
                    supportingContent = { Text(listOfNotNull(s.municipality, s.code.take(2).let(::provinceName)).joinToString(" · ")) },
                    leadingContent = { Icon(Icons.Filled.Add, null, tint = MaterialTheme.colorScheme.primary) },
                    modifier = Modifier.clickable {
                        app.locations.add(SavedLocation(s.code, s.name, s.municipality ?: provinceName(s.code.take(2)), s.lat, s.lon))
                        query = ""
                    }, colors = ListItemDefaults.colors(containerColor = Color.Transparent))
            }
        }
    }
}

private fun provinceName(code: String): String? = mapOf(
    "01" to "Álava", "02" to "Albacete", "03" to "Alicante", "04" to "Almería", "05" to "Ávila", "06" to "Badajoz", "07" to "Baleares",
    "08" to "Barcelona", "09" to "Burgos", "10" to "Cáceres", "11" to "Cádiz", "12" to "Castellón", "13" to "Ciudad Real", "14" to "Córdoba",
    "15" to "A Coruña", "16" to "Cuenca", "17" to "Girona", "18" to "Granada", "19" to "Guadalajara", "20" to "Gipuzkoa", "21" to "Huelva",
    "22" to "Huesca", "23" to "Jaén", "24" to "León", "25" to "Lleida", "26" to "La Rioja", "27" to "Lugo", "28" to "Madrid", "29" to "Málaga",
    "30" to "Murcia", "31" to "Navarra", "32" to "Ourense", "33" to "Asturias", "34" to "Palencia", "35" to "Las Palmas", "36" to "Pontevedra",
    "37" to "Salamanca", "38" to "Santa Cruz de Tenerife", "39" to "Cantabria", "40" to "Segovia", "41" to "Sevilla", "42" to "Soria",
    "43" to "Tarragona", "44" to "Teruel", "45" to "Toledo", "46" to "Valencia", "47" to "Valladolid", "48" to "Bizkaia", "49" to "Zamora",
    "50" to "Zaragoza", "51" to "Ceuta", "52" to "Melilla",
)[code]

// --- Sol·Luna y mareas ---------------------------------------------------------------------

@Composable
private fun CosmosTab(page: Int) {
    val app = LocalContext.current.app
    val saved by app.locations.saved.collectAsStateWithLifecycle()
    val current by app.locations.current.collectAsStateWithLifecycle()
    val all = listOfNotNull(current) + saved
    val loc = all.getOrNull(page) ?: all.firstOrNull() ?: return
    val zone = loc.zone
    val today = LocalDate.now(zone)
    val sun = remember(loc, today) { SunMoon.sun(today, loc.lat, loc.lon, zone) }
    val moon = remember(loc, today) { SunMoon.moon(today, loc.lat, loc.lon, zone, Instant.now()) }
    val phases = remember(today) { SunMoon.nextPhases(Instant.now()) }
    val ports = remember(loc) { portsByDistance(loc.lat, loc.lon).take(5) }
    var portIndex by remember(loc) { mutableIntStateOf(0) }
    var tides by remember(loc, portIndex) { mutableStateOf<List<Pair<LocalDate, List<Tide>>>?>(null) }
    val nearCoast = ports.first().second < 60
    LaunchedEffect(loc, portIndex) { if (nearCoast) tides = runCatching { app.tides.days(ports[portIndex].first, today) }.getOrDefault(emptyList()) }
    fun t(i: Instant?) = i?.atZone(zone)?.format(hhmm) ?: "—"

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { Text("Sol·Luna · ${loc.name}", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 24.dp)) }
        item {
            Card {
                Column {
                    CardTitle("Sol", today.format(DateTimeFormatter.ofPattern("EEEE d MMMM", Spanish)))
                    Row(Modifier.fillMaxWidth().padding(16.dp, 4.dp), horizontalArrangement = Arrangement.SpaceAround) {
                        Big("🌅", t(sun.sunrise), "Orto"); Big("☀️", t(sun.noon), "Mediodía"); Big("🌇", t(sun.sunset), "Ocaso")
                    }
                    sun.daylightMinutes?.let { Text("Duración del día: ${it / 60} h ${"%02d".format(it % 60)} min", Modifier.padding(16.dp, 10.dp),
                        fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
        }
        item {
            Card {
                Column {
                    CardTitle("Luna", "${(moon.illumination * 100).roundToInt()}% iluminada")
                    Row(Modifier.fillMaxWidth().padding(16.dp, 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(moon.phase.emoji, fontSize = 52.sp)
                        Column(Modifier.padding(start = 16.dp).weight(1f)) {
                            Text(moon.phase.label, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
                            Text("Sale ${t(moon.rise)} · se pone ${t(moon.set)}", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    HorizontalDivider(Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
                    phases.forEach { (p, at) ->
                        Row(Modifier.fillMaxWidth().padding(16.dp, 8.dp)) {
                            Text("${p.emoji}  ${p.label}", Modifier.weight(1f), fontSize = 14.sp)
                            Text(at.atZone(zone).format(DateTimeFormatter.ofPattern("EEE d MMM, HH:mm", Spanish)), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
        if (nearCoast) item {
            Card {
                Column {
                    CardTitle("Mareas · ${ports[portIndex].first.name}", "${ports[portIndex].second.roundToInt()} km")
                    Row(Modifier.padding(horizontal = 8.dp)) {
                        ports.take(3).forEachIndexed { i, (p, _) ->
                            TextButton(onClick = { portIndex = i }) { Text(p.name.substringBefore(" ("), fontSize = 12.sp,
                                fontWeight = if (i == portIndex) FontWeight.Bold else null) }
                        }
                    }
                    when (val list = tides) {
                        null -> Text("Cargando…", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        else -> list.forEach { (day, ts) ->
                            Text(if (day == today) "Hoy" else "Mañana", Modifier.padding(16.dp, 6.dp), fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                            if (ts.isEmpty()) Text("Sin datos del IHM", Modifier.padding(horizontal = 16.dp), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            ts.forEach { tide ->
                                Row(Modifier.fillMaxWidth().background(if (tide.high) Color(0x1A007AFF) else Color(0x1AFFC53D)).padding(16.dp, 8.dp)) {
                                    Text(if (tide.high) "🌊 Pleamar" else "🏖 Bajamar", Modifier.weight(1f), fontSize = 14.sp)
                                    Text(tide.at.atZone(app.tides.zone(ports[portIndex].first)).format(hhmm), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                                    Text("   ${String.format(Locale.getDefault(), "%.2f", tide.height)} m", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                    Text("© Instituto Hidrográfico de la Marina", Modifier.padding(16.dp, 10.dp), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun Big(emoji: String, value: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(emoji, fontSize = 26.sp)
        Text(value, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// --- Ajustes -------------------------------------------------------------------------------

@Composable
private fun SettingsTab() {
    val app = LocalContext.current.app
    val key by app.settings.aemetKey.collectAsStateWithLifecycle()
    val scale by app.settings.tempScale.collectAsStateWithLifecycle()
    var draft by remember(key) { mutableStateOf(key) }
    val askLocation = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { Text("Ajustes", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 24.dp)) }
        item {
            Card {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("AEMET OpenData", fontWeight = FontWeight.SemiBold)
                    OutlinedTextField(draft, { draft = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Clave") },
                        visualTransformation = PasswordVisualTransformation())
                    Row {
                        TextButton(enabled = draft != key, onClick = { app.settings.setAemetKey(draft) }) { Text("Guardar") }
                        TextButton(onClick = { draft = ""; app.settings.setAemetKey("") }) { Text("Quitar") }
                    }
                    Text("Sin clave, el tiempo sale de Open-Meteo con la misma pantalla. La clave se pide gratis en opendata.aemet.es " +
                        "y hay que renovarla cuando AEMET lo pide.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item {
            Card {
                Column(Modifier.padding(vertical = 8.dp)) {
                    CardTitle("Widgets · color según la temperatura")
                    TempScale.entries.forEach { s ->
                        Row(Modifier.fillMaxWidth().clickable { app.settings.setScale(s); app.snapshots.requestUpdate() }.padding(horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(s == scale, { app.settings.setScale(s); app.snapshots.requestUpdate() })
                            Text(s.label + if (s == TempScale.DEFAULT) " (por omisión)" else "", Modifier.weight(1f), fontSize = 14.sp)
                            // Las muestras de la escala, con su degradado de verdad, de −5° a 40°.
                            Row(Modifier.padding(end = 8.dp)) {
                                listOf(-5.0, 5.0, 15.0, 22.0, 28.0, 35.0, 40.0).forEach { t ->
                                    val (top, bot) = TempPalette.gradient(t, s)
                                    Box(Modifier.size(14.dp, 22.dp).background(Brush.verticalGradient(listOf(Color(0xFF000000.toInt() or top), Color(0xFF000000.toInt() or bot)))))
                                }
                            }
                        }
                    }
                }
            }
        }
        item {
            Card {
                Column(Modifier.padding(16.dp)) {
                    Text("Ubicación", fontWeight = FontWeight.SemiBold)
                    Text("La ubicación actual se resuelve en el móvil, sin geocodificador: municipio de AEMET más cercano y el pueblo del nomenclátor.",
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (!app.locations.hasPermission()) TextButton(onClick = { askLocation.launch(Manifest.permission.ACCESS_COARSE_LOCATION) }) {
                        Icon(Icons.Filled.MyLocation, null); Text("  Permitir la ubicación")
                    }
                }
            }
        }
        item {
            Card {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Datos", fontWeight = FontWeight.SemiBold)
                    listOf("Predicción, observaciones y avisos: © AEMET", "Sin clave, y aire, polen y UV: Open-Meteo (CC BY 4.0)",
                        "Mareas: © Instituto Hidrográfico de la Marina", "Pueblos y aldeas: GeoNames (CC BY 4.0)",
                        "Municipios: maestro de municipios de AEMET").forEach { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

