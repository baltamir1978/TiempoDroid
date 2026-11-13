@file:OptIn(ExperimentalMaterial3Api::class)

package es.altamirano.tiempo.ui

import es.altamirano.common.ui.isWide
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.style.TextAlign
import es.altamirano.tiempo.core.Astro
import es.altamirano.tiempo.core.AstroEvent
import es.altamirano.tiempo.core.IHM_STATIONS
import es.altamirano.tiempo.core.PhaseEvent
import es.altamirano.tiempo.core.TideStation
import es.altamirano.tiempo.core.portsByDistance
import android.Manifest
import android.content.Intent
import android.widget.Toast
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.rememberDatePickerState
import java.time.ZoneOffset
import kotlinx.coroutines.flow.MutableStateFlow
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
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

/** El idioma de las fechas: el de la app (`I18n`). */
private val Spanish: Locale get() = es.altamirano.tiempo.core.I18n.locale()
private val hhmm = DateTimeFormatter.ofPattern("HH:mm")

/** Lo que pide abrir un widget (`WidgetDeepLink`): la pestaña y, en Mareas, bajar hasta su tarjeta. */
private val deepLink = MutableStateFlow<Pair<String, Long>?>(null)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handle(intent)
        setContent { TiempoTheme { Root() } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        val uri = intent?.data ?: return
        when (uri.scheme) {
            "tiempo" -> deepLink.value = (uri.host ?: "") to System.nanoTime()
            // La vuelta del inicio de sesión de Netatmo en el navegador.
            "tiempo-netatmo" -> app.scope.launch {
                try {
                    app.netatmo.api.handleCallback(uri)
                    app.netatmo.recheck()
                    app.netatmo.load(force = true)
                    Toast.makeText(this@MainActivity, es.altamirano.tiempo.core.tr("Sesión de Netatmo iniciada"), Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(this@MainActivity, es.altamirano.tiempo.core.tr(e.message ?: "No se pudo iniciar sesión"), Toast.LENGTH_LONG).show()
                }
            }
        }
        setIntent(Intent(intent).setData(null))
    }
}

private const val TAB_WEATHER = 0; private const val TAB_COSMOS = 1; private const val TAB_ACTUAL = 2; private const val TAB_SETTINGS = 3

@Composable
private fun Root() {
    val app = LocalContext.current.app
    var tab by rememberSaveable { mutableIntStateOf(TAB_WEATHER) }
    var page by rememberSaveable { mutableIntStateOf(0) }
    var tidesSignal by remember { mutableIntStateOf(0) }
    val netatmoOn by app.netatmo.enabled.collectAsStateWithLifecycle()
    val link by deepLink.collectAsStateWithLifecycle()
    LaunchedEffect(link) {
        when (link?.first) {
            "aemet" -> tab = TAB_WEATHER
            "netatmo" -> tab = if (netatmoOn) TAB_ACTUAL else TAB_WEATHER
            "cosmos" -> tab = TAB_COSMOS
            "tides" -> { tab = TAB_COSMOS; tidesSignal++ }
        }
    }
    LaunchedEffect(Unit) { app.netatmo.recheck() }
    if (tab == TAB_ACTUAL && !netatmoOn) tab = TAB_WEATHER
    // Sin clave de AEMET todo sale de Open-Meteo: la pestaña se llama «Tiempo», no «AEMET».
    val aemetKey by app.settings.aemetKey.collectAsStateWithLifecycle()
    val items = listOfNotNull(Triple(if (aemetKey.isNotBlank()) "AEMET" else "Tiempo", Icons.Filled.Cloud, TAB_WEATHER),
        Triple("Sol·Luna", Icons.Filled.Brightness4, TAB_COSMOS),
        // La pestaña de Netatmo sólo sale con Netatmo configurado, al final de la barra.
        if (netatmoOn) Triple("Actual", Icons.Filled.Thermostat, TAB_ACTUAL) else null,
        Triple("Ajustes", Icons.Filled.Settings, TAB_SETTINGS)).map { (label, icon, i) ->
        NavItem(tab == i, { tab = i }, { Icon(icon, null) }, { Text(label) })
    }
    AdaptiveNavigation(items) {
        when (tab) {
            TAB_WEATHER -> WeatherTab(page) { page = it }
            TAB_COSMOS -> CosmosTab(page, tidesSignal)
            TAB_ACTUAL -> ActualTab()
            else -> SettingsTab()
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

    // Al localizar se guarda también lo del widget: si la página de la ubicación actual no está a
    // la vista (o sigue en el mismo municipio), nadie más lo haría y el widget seguiría en el sitio de antes.
    // Cada vez que la app vuelve a primer plano, no sólo al crearse: con la app ya abierta en
    // segundo plano (lo normal), volver a ella no localizaba y el widget seguía en el sitio de antes.
    var asked by remember { mutableStateOf(false) }
    var askAlways by remember { mutableStateOf(false) }
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) {
        val job = scope.launch {
            if (app.locations.hasPermission()) {
                app.locations.locate()?.let { l -> app.snapshots.save(l, app.weather.load(l)) }
                app.locations.watch()
                // Con la app cerrada sólo se sabe dónde estás con «Permitir siempre»: se pide una vez si hay widget de la ubicación actual.
                if (!app.locations.hasBackgroundPermission() && !app.settings.askedBackgroundLocation &&
                    es.altamirano.tiempo.widget.widgetsShowCurrent(app)) askAlways = true
            } else if (!asked) { asked = true; askLocation.launch(Manifest.permission.ACCESS_COARSE_LOCATION) }
        }
        onPauseOrDispose { job.cancel() }
    }
    val askBackground = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> if (ok) app.locations.watch() }
    if (askAlways) androidx.compose.material3.AlertDialog(
        onDismissRequest = { askAlways = false; app.settings.askedBackgroundLocation = true },
        title = { Text("Widget de la ubicación actual") },
        text = { Text("Para que el widget cambie de sitio cuando te mueves sin abrir la app, Android pide dar la ubicación «Permitir siempre».") },
        confirmButton = { TextButton(onClick = { askAlways = false; app.settings.askedBackgroundLocation = true
            askBackground.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION) }) { Text("Permitir siempre") } },
        dismissButton = { TextButton(onClick = { askAlways = false; app.settings.askedBackgroundLocation = true }) { Text("Ahora no") } },
    )
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
                                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f))
                                .clickable { scope.launch { pager.animateScrollToPage(i) } })
                        }
                    }
                }
            },
            actions = {
                // Con ratón no se pasa de página con el dedo: flechas.
                if (isWide() && all.size > 1) {
                    IconButton(onClick = { scope.launch { pager.animateScrollToPage(pager.currentPage - 1) } }, enabled = pager.currentPage > 0) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Anterior") }
                    IconButton(onClick = { scope.launch { pager.animateScrollToPage(pager.currentPage + 1) } }, enabled = pager.currentPage < all.lastIndex) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Siguiente") }
                }
                IconButton(onClick = { scope.launch { app.snapshots.save(loc, app.weather.load(loc, force = true)) } }) { Icon(Icons.Filled.Refresh, "Actualizar") }
                IconButton(onClick = { manage = true }) { Icon(Icons.Filled.FormatListBulleted, "Ciudades") }
            },
            expandedHeight = 48.dp, colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background, actionIconContentColor = MaterialTheme.colorScheme.primary),
        )
        HorizontalPager(pager, Modifier.fillMaxSize(), beyondViewportPageCount = 1) { i ->
            val l = all[i]
            val key = LocationStore.keyOf(l)
            val w = pages[key] ?: remember(key) { app.weather.primed(l) }
            // Cada página carga la suya (y la de al lado se prepara por adelantado).
            // Con el nombre: de San Blas a Fuencarral el municipio es el mismo (Madrid) y el widget no se enteraba.
            LaunchedEffect(key, l.municipio, l.name) { if (!w.loading) app.snapshots.save(l, app.weather.load(l)) }
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
            expandedHeight = 48.dp, colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background))
        OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().padding(horizontal = gutter(), vertical = 4.dp), singleLine = true,
            placeholder = { Text("Buscar municipio o pueblo") }, leadingIcon = { Icon(Icons.Filled.Search, null) }, shape = RoundedCornerShape(12.dp))
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = gutter(0.dp))) {
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
private fun CosmosTab(page: Int, tidesSignal: Int = 0) {
    val app = LocalContext.current.app
    val saved by app.locations.saved.collectAsStateWithLifecycle()
    val current by app.locations.current.collectAsStateWithLifecycle()
    val all = listOfNotNull(current) + saved
    val loc = all.getOrNull(page) ?: all.firstOrNull() ?: return
    val zone = loc.zone
    val realToday = LocalDate.now(zone)
    // La fecha elegida arriba (`selectedDate` en iOS): mueve el sol, la luna, el calendario y las
    // mareas. Dentro, el día que se toque en el calendario manda sobre ella.
    var baseDate by rememberSaveable { mutableStateOf(realToday.toEpochDay()) }
    val today = LocalDate.ofEpochDay(baseDate)
    var picking by remember { mutableStateOf(false) }
    var selectedDay by remember(loc, baseDate) { mutableStateOf<LocalDate?>(null) }
    val day = selectedDay ?: today
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    LaunchedEffect(tidesSignal) { if (tidesSignal > 0) listState.animateScrollToItem(6) }
    if (picking) {
        val state = rememberDatePickerState(initialSelectedDateMillis = today.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        DatePickerDialog(onDismissRequest = { picking = false },
            confirmButton = { TextButton(onClick = {
                state.selectedDateMillis?.let { baseDate = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().toEpochDay() }
                picking = false }) { Text("Aceptar") } },
            dismissButton = { TextButton(onClick = { picking = false }) { Text("Cancelar") } }) { DatePicker(state) }
    }
    val sun = remember(loc, day) { SunMoon.sun(day, loc.lat, loc.lon, zone) }
    val moon = remember(loc, day) { SunMoon.moon(day, loc.lat, loc.lon, zone, if (day == realToday) Instant.now() else day.atTime(12, 0).atZone(zone).toInstant()) }
    val monthStart = today.withDayOfMonth(1).atStartOfDay(zone).toInstant()
    val phases = remember(today, zone) { Astro.phases(monthStart, 44).filter { !it.at.isAfter(monthStart.atZone(zone).plusYears(1).toInstant()) } }
    val events = remember(loc.lat, loc.lon, today) { Astro.events(monthStart, loc.lat, loc.lon, zone) }
    val portId by app.settings.tidePort.collectAsStateWithLifecycle()
    val port = IHM_STATIONS.firstOrNull { it.id == portId } ?: IHM_STATIONS.first { it.id == "4" }
    var tides by remember(port, day) { mutableStateOf<List<Pair<LocalDate, List<Tide>>>?>(null) }
    LaunchedEffect(port, day) { tides = runCatching { app.tides.days(port, day) }.getOrDefault(emptyList()) }
    fun t(i: Instant?) = i?.atZone(zone)?.format(hhmm) ?: "—"

    LazyColumn(Modifier.fillMaxSize().statusBarsPadding(), state = listState, contentPadding = PaddingValues(horizontal = gutter(), vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { Text("Sol·Luna · ${loc.name}", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold) }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { picking = true }) {
                    Icon(Icons.Filled.CalendarMonth, null); Text("  " + today.format(DateTimeFormatter.ofPattern("d MMM yyyy", Spanish)))
                }
                if (today != realToday) TextButton(onClick = { baseDate = realToday.toEpochDay() }) { Text("Hoy") }
            }
        }
        item {
            Card {
                Column {
                    CardTitle("Sol", day.format(DateTimeFormatter.ofPattern("EEEE d MMMM", Spanish)))
                    Row(Modifier.fillMaxWidth().padding(16.dp, 4.dp), horizontalArrangement = Arrangement.SpaceAround) {
                        Big("🌅", t(sun.sunrise), "Orto"); Big("☀️", t(sun.noon), "Mediodía"); Big("🌇", t(sun.sunset), "Ocaso")
                    }
                    sun.daylightMinutes?.let { Text("Duración del día: ${it / 60} h ${"%02d".format(it % 60)} min", Modifier.padding(16.dp, 10.dp),
                        fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    if (selectedDay != null && selectedDay != today) TextButton(onClick = { selectedDay = null }, Modifier.padding(horizontal = 8.dp)) {
                        Text(if (today == realToday) "Volver a hoy" else "Volver al ${today.format(DateTimeFormatter.ofPattern("d MMM", Spanish))}") }
                }
            }
        }
        item {
            Card {
                Column {
                    CardTitle("Luna", "${(moon.illumination * 100).roundToInt()}% iluminada")
                    Row(Modifier.fillMaxWidth().padding(16.dp, 4.dp).padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(moon.phase.emoji, fontSize = 52.sp)
                        Column(Modifier.padding(start = 16.dp).weight(1f)) {
                            Text(moon.phase.label, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
                            Text("Sale ${t(moon.rise)} · se pone ${t(moon.set)}", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
        item { MoonCalendar(today, zone, phases, events, selectedDay) { selectedDay = it } }
        item { UpcomingEvents(today, zone, events) }
        item { TidesCard(loc, port, day, today, tides, app.tides.zone(port)) { app.settings.setTidePort(it) } }
    }
}

private val RowLilac = Color(0x269B7FE0); private val RowWarm = Color(0x26FFC53D); private val GreenSoft = Color(0x26239E7B)

/** El calendario del mes: fases y eventos de cada día; tocar un día lleva a él la tarjeta del sol. */
@Composable
private fun MoonCalendar(today: LocalDate, zone: ZoneId, phases: List<PhaseEvent>, events: List<AstroEvent>,
                         selected: LocalDate?, onSelect: (LocalDate?) -> Unit) {
    var month by remember(today) { mutableStateOf(today.withDayOfMonth(1)) }
    val phaseByDay = phases.associateBy { it.at.atZone(zone).toLocalDate() }
    val eventsByDay = events.groupBy { it.at.atZone(zone).toLocalDate() }
    Card {
        Column {
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { month = month.minusMonths(1) }, enabled = month > today.withDayOfMonth(1)) { Text("‹", fontSize = 20.sp) }
                Text(month.format(DateTimeFormatter.ofPattern("LLLL yyyy", Spanish)).replaceFirstChar { it.titlecase() },
                    Modifier.weight(1f), textAlign = TextAlign.Center, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                TextButton(onClick = { month = month.plusMonths(1) }, enabled = month < today.withDayOfMonth(1).plusMonths(12)) { Text("›", fontSize = 20.sp) }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(Modifier.padding(horizontal = 10.dp).padding(top = 8.dp)) {
                java.time.DayOfWeek.entries.map { it.getDisplayName(java.time.format.TextStyle.NARROW, Spanish).uppercase(Spanish) }.forEach {
                    Text(it, Modifier.weight(1f), textAlign = TextAlign.Center, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            val lead = month.dayOfWeek.value - 1
            val cells = List(lead) { null } + (1..month.lengthOfMonth()).map { month.withDayOfMonth(it) }
            Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                cells.chunked(7).forEach { week ->
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        (week + List(7 - week.size) { null }).forEach { d ->
                            Box(Modifier.weight(1f).height(44.dp)) {
                                if (d != null) {
                                    val ph = phaseByDay[d]; val evs = eventsByDay[d].orEmpty()
                                    val isSel = d == selected
                                    val tint = when { isSel -> GreenSoft; ph != null -> if (ph.full) RowWarm else RowLilac
                                        evs.isNotEmpty() -> GreenSoft; else -> Color.Transparent }
                                    val has = ph != null || evs.isNotEmpty()
                                    Column(Modifier.fillMaxSize().clip(RoundedCornerShape(6.dp)).background(tint)
                                        .then(if (isSel || d == today) Modifier.border(if (isSel) 1.5.dp else 1.dp,
                                            if (isSel) Green else Green.copy(alpha = 0.4f), RoundedCornerShape(6.dp)) else Modifier)
                                        .clickable { onSelect(if (isSel) null else d) }
                                        .alpha(if (d < today && !isSel) 0.45f else 1f),
                                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                                        Text("${d.dayOfMonth}", fontSize = 12.sp, lineHeight = 15.sp, fontWeight = if (has) FontWeight.Bold else null,
                                            color = if (has) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                                        Text(ph?.emoji ?: evs.firstOrNull()?.emoji ?: " ", fontSize = 12.sp, lineHeight = 17.sp)
                                    }
                                    if (ph != null && evs.isNotEmpty()) Box(Modifier.align(Alignment.TopEnd).padding(3.dp).size(5.dp).clip(CircleShape).background(Green))
                                }
                            }
                        }
                    }
                }
            }
            selected?.let { sel ->
                val ph = phaseByDay[sel]; val evs = eventsByDay[sel].orEmpty()
                if (ph != null || evs.isNotEmpty()) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        ph?.let { EventRow(it.emoji, it.label, null, it.at, zone, today, if (it.full) RowWarm else RowLilac) }
                        evs.forEach { EventRow(it.emoji, it.label, it.details, it.at, zone, today, null) }
                    }
                }
            }
        }
    }
}

/** Los próximos eventos astronómicos: los de los 30 días siguientes, resaltados. */
@Composable
private fun UpcomingEvents(today: LocalDate, zone: ZoneId, events: List<AstroEvent>) {
    val next = events.filter { !it.at.atZone(zone).toLocalDate().isBefore(today) }.take(8)
    if (next.isEmpty()) return
    Card {
        Column(Modifier.padding(bottom = 10.dp)) {
            CardTitle("Eventos astronómicos")
            Column(Modifier.padding(horizontal = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                next.forEach { EventRow(it.emoji, it.label, it.details, it.at, zone, today, null) }
            }
        }
    }
}

@Composable
private fun EventRow(emoji: String, label: String, details: String?, at: Instant, zone: ZoneId, today: LocalDate, tint: Color?) {
    val d = at.atZone(zone).toLocalDate()
    val isToday = d == today; val isPast = d < today; val soon = !isPast && d <= today.plusDays(30)
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
        .background(tint ?: if (isToday) Color(0x33FFD60A) else if (soon) GreenSoft else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
        .padding(12.dp, 8.dp).alpha(if (isPast) 0.6f else 1f), verticalAlignment = Alignment.CenterVertically) {
        Text(emoji, fontSize = 22.sp)
        Column(Modifier.weight(1f).padding(start = 10.dp)) {
            Text(label, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            details?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(at.atZone(zone).format(DateTimeFormatter.ofPattern("EEE d MMM yyyy", Spanish)), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(at.atZone(zone).format(hhmm), fontSize = 14.sp, fontWeight = FontWeight.Bold)
        }
    }
}

/**
 * Las mareas del puerto elegido, también lejos de la costa (como en iOS): los más cercanos
 * primero con su distancia, que es lo que dice si ese puerto vale por tu sitio, y luego todos.
 */
@Composable
private fun TidesCard(loc: SavedLocation, port: TideStation, day: LocalDate, today: LocalDate,
                      tides: List<Pair<LocalDate, List<Tide>>>?, portZone: ZoneId, onPort: (String) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Card {
        Column(Modifier.padding(bottom = 10.dp)) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("MAREAS", Modifier.weight(1f), fontSize = 12.sp, fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, letterSpacing = 1.sp)
                Box {
                    TextButton(onClick = { menu = true }) {
                        Text("${port.name} · ${km(Places.distanceKm(loc.lat, loc.lon, port.lat, port.lon))} km ▾", fontSize = 12.sp)
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        Text("Cerca de ${loc.name}", Modifier.padding(16.dp, 6.dp), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Green)
                        portsByDistance(loc.lat, loc.lon).take(4).forEach { (p, d) ->
                            DropdownMenuItem(text = { Text("${p.name} · ${km(d)} km") }, onClick = { onPort(p.id); menu = false })
                        }
                        HorizontalDivider()
                        Text("Todos los puertos", Modifier.padding(16.dp, 6.dp), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Green)
                        IHM_STATIONS.forEach { p -> DropdownMenuItem(text = { Text(p.name) }, onClick = { onPort(p.id); menu = false }) }
                    }
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            when (tides) {
                null -> Text("Cargando…", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                else -> {
                    if (tides.isEmpty()) Text("Sin datos del IHM", Modifier.padding(16.dp), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    tides.forEach { (d, ts) ->
                        Text(d.format(DateTimeFormatter.ofPattern("EEEE d MMM", Spanish)).uppercase(Spanish), Modifier.padding(16.dp, 10.dp).padding(bottom = 0.dp),
                            fontWeight = FontWeight.Bold, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, letterSpacing = 1.sp)
                        if (ts.isEmpty()) Text("Sin datos", Modifier.padding(horizontal = 16.dp), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        ts.forEach { tide ->
                            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp).clip(RoundedCornerShape(8.dp))
                                .background(if (tide.high) Color(0x1A007AFF) else RowWarm).padding(14.dp, 7.dp)) {
                                Text(if (tide.high) "🌊 Pleamar" else "🏖️ Bajamar", Modifier.weight(1f), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                                Text(tide.at.atZone(portZone).format(hhmm), fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text("   ${String.format(Locale.getDefault(), "%.2f", tide.height)} m", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
            Text("© Instituto Hidrográfico de la Marina", Modifier.padding(16.dp, 10.dp).padding(bottom = 0.dp), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
    val askAlways = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { if (it) app.locations.watch() }
    LazyColumn(Modifier.fillMaxSize().statusBarsPadding(), contentPadding = PaddingValues(horizontal = gutter(), vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { Text("Ajustes", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold) }
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
                    // ¿Funciona? Se pregunta a AEMET de verdad, y la fecha de caducidad va dentro de la clave.
                    var status by remember(key) { mutableStateOf<String?>(null) }
                    LaunchedEffect(key) { status = if (key.isBlank()) "" else app.weather.aemet.check() ?: "ok" }
                    val exp = es.altamirano.tiempo.data.aemetKeyExpiry(key)?.let {
                        java.time.Instant.ofEpochSecond(it).atZone(java.time.ZoneId.systemDefault()).toLocalDate() }
                    when (status) {
                        null -> Text("Comprobando la clave…", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        "" -> Text("Sin clave: el tiempo sale de Open-Meteo.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        "ok" -> Text("✓ AEMET responde", fontSize = 13.sp, color = Green, fontWeight = FontWeight.SemiBold)
                        else -> Text("AEMET no disponible: " + status!!.removePrefix("AEMET: "), fontSize = 13.sp, color = Color(0xFFFF9500))
                    }
                    exp?.let {
                        val left = java.time.temporal.ChronoUnit.DAYS.between(java.time.LocalDate.now(), it)
                        Text(if (left < 0) "La clave caducó el ${it.format(DateTimeFormatter.ofPattern("d MMM yyyy", Spanish))}"
                            else "La clave caduca el ${it.format(DateTimeFormatter.ofPattern("d MMM yyyy", Spanish))} (faltan $left días)",
                            fontSize = 12.sp, color = if (left < 15) Color(0xFFFF9500) else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text("Sin clave, el tiempo sale de Open-Meteo con la misma pantalla. La clave se pide gratis en opendata.aemet.es " +
                        "y hay que renovarla cuando AEMET lo pide.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item { NetatmoSettings() }
        item {
            Card {
                Column(Modifier.padding(vertical = 8.dp)) {
                    CardTitle("Widgets · color según la temperatura")
                    TempScale.entries.forEach { s ->
                        Row(Modifier.fillMaxWidth().clickable { app.settings.setScale(s); app.snapshots.requestUpdate() }.padding(horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(s == scale, { app.settings.setScale(s); app.snapshots.requestUpdate() })
                            Text(if (s == TempScale.DEFAULT) "${s.label} (por omisión)" else s.label, Modifier.weight(1f), fontSize = 14.sp)
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
                    // Se vuelve a mirar al volver de los ajustes de Android.
                    var perm by remember { mutableStateOf(app.locations.hasPermission()) }
                    var always by remember { mutableStateOf(app.locations.hasBackgroundPermission()) }
                    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) {
                        perm = app.locations.hasPermission(); always = app.locations.hasBackgroundPermission()
                        if (always) app.locations.watch()
                        onPauseOrDispose {}
                    }
                    if (!perm) TextButton(onClick = { askLocation.launch(Manifest.permission.ACCESS_COARSE_LOCATION) }) {
                        Icon(Icons.Filled.MyLocation, null); Text("  Permitir la ubicación")
                    } else if (!always) {
                        Text("El widget de la ubicación actual sólo cambia de sitio con la app cerrada si la ubicación está en «Permitir siempre».",
                            fontSize = 12.sp, color = Color(0xFFFF9500), modifier = Modifier.padding(top = 6.dp))
                        TextButton(onClick = { askAlways.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION) }) {
                            Icon(Icons.Filled.MyLocation, null); Text("  Permitir siempre")
                        }
                    } else Text("✓ Ubicación siempre: el widget sigue tu posición con la app cerrada.", fontSize = 12.sp, color = Green,
                        modifier = Modifier.padding(top = 6.dp))
                }
            }
        }
        item {
            Card {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Datos", fontWeight = FontWeight.SemiBold)
                    listOf("Predicción, observaciones y avisos: © AEMET", "Sin clave, y aire, polen y UV: Open-Meteo (CC BY 4.0)",
                        "Mareas: © Instituto Hidrográfico de la Marina", "Portugal: IPMA (previsión, observaciones y avisos)", "Pueblos y aldeas: GeoNames (CC BY 4.0)",
                        "Municipios: maestro de municipios de AEMET").forEach { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

