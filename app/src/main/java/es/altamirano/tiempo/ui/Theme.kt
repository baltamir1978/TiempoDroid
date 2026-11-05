package es.altamirano.tiempo.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AcUnit
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Dehaze
import androidx.compose.material.icons.filled.FilterDrama
import androidx.compose.material.icons.filled.Grain
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.NightsStay
import androidx.compose.material.icons.filled.Thunderstorm
import androidx.compose.material.icons.filled.Umbrella
import androidx.compose.material.icons.filled.WbCloudy
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import es.altamirano.tiempo.core.Sky
import es.altamirano.tiempo.core.skyOf

/** Los verdes de `AppTheme.swift`, sacados del icono (los mismos que BirdApp y RadioApp). */
val GreenDeep = Color(0xFF0B6F53)
val Green = Color(0xFF239E7B)
val GreenMid = Color(0xFF2BBE92)
val GreenBright = Color(0xFF37D094)
val Sun = Color(0xFFFFC53D)
val HeroGradient = Brush.verticalGradient(listOf(GreenBright, GreenDeep))

private val Light = lightColorScheme(
    primary = Color(0xFF1A7F60), onPrimary = Color.White, primaryContainer = Color(0xFFE6F5ED), onPrimaryContainer = GreenDeep,
    secondary = Green, background = Color(0xFFF2F2F7), onBackground = Color(0xFF1C1C1E), surface = Color(0xFFF2F2F7),
    onSurface = Color(0xFF1C1C1E), surfaceVariant = Color(0xFFE5E5EA), onSurfaceVariant = Color(0xFF6B6B70),
    surfaceContainer = Color.White, surfaceContainerLow = Color.White, surfaceContainerHigh = Color.White,
    surfaceContainerHighest = Color(0xFFE5E5EA), outlineVariant = Color(0xFFE5E5EA),
)
private val Dark = darkColorScheme(
    primary = GreenMid, onPrimary = Color.Black, primaryContainer = Color(0xFF14332A), onPrimaryContainer = GreenBright,
    secondary = GreenMid, background = Color.Black, onBackground = Color(0xFFF2F2F7), surface = Color.Black,
    onSurface = Color(0xFFF2F2F7), surfaceVariant = Color(0xFF2C2C2E), onSurfaceVariant = Color(0xFF9A9AA0),
    surfaceContainer = Color(0xFF1C1C1E), surfaceContainerLow = Color(0xFF1C1C1E), surfaceContainerHigh = Color(0xFF2C2C2E),
    surfaceContainerHighest = Color(0xFF3A3A3C), outlineVariant = Color(0xFF38383A),
)

@Composable
fun TiempoTheme(content: @Composable () -> Unit) = MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, content = content)

/** El icono y el color de cada cielo, como `WeatherIconView` / `SkyIcon` de iOS. */
fun skyIcon(code: String?): Pair<ImageVector, Color> = when (skyOf(code)) {
    Sky.CLEAR -> Icons.Filled.WbSunny to Color(0xFFFFCC00)
    Sky.CLEAR_NIGHT -> Icons.Filled.NightsStay to Color(0xFF5856D6)
    Sky.PARTLY -> Icons.Filled.FilterDrama to Color(0xFFFF9500)
    Sky.PARTLY_NIGHT -> Icons.Filled.FilterDrama to Color(0xFFAF52DE)
    Sky.CLOUDY -> Icons.Filled.Cloud to Color(0xFF8E8E93)
    Sky.HIGH_CLOUDS -> Icons.Filled.WbCloudy to Color(0xFF8E8E93)
    Sky.RAIN -> Icons.Filled.Umbrella to Color(0xFF007AFF)
    Sky.SHOWER -> Icons.Filled.Grain to Color(0xFF007AFF)
    Sky.THUNDER, Sky.THUNDER_RAIN -> Icons.Filled.Thunderstorm to Color(0xFFAF52DE)
    Sky.SNOW, Sky.LIGHT_SNOW -> Icons.Filled.AcUnit to Color(0xFF32ADE6)
    Sky.FOG, Sky.MIST -> Icons.Filled.Dehaze to Color(0xFFAEAEB2)
    Sky.UNKNOWN -> Icons.Filled.HelpOutline to Color(0xFF8E8E93)
}

/** Para los widgets, que no pueden pintar los iconos de la app. */
fun skyEmoji(code: String?): String = when (skyOf(code)) {
    Sky.CLEAR -> "☀️"; Sky.CLEAR_NIGHT -> "🌙"; Sky.PARTLY -> "⛅"; Sky.PARTLY_NIGHT -> "☁️"
    Sky.CLOUDY, Sky.HIGH_CLOUDS -> "☁️"; Sky.RAIN -> "🌧️"; Sky.SHOWER -> "🌦️"
    Sky.THUNDER, Sky.THUNDER_RAIN -> "⛈️"; Sky.SNOW, Sky.LIGHT_SNOW -> "❄️"; Sky.FOG, Sky.MIST -> "🌫️"; Sky.UNKNOWN -> "·"
}

/** Avisos: amarillo, naranja y rojo, como `AemetAlertBadge`. */
fun alertColor(level: Int): Color = when (level) { 3 -> Color(0xFFD92E2E); 2 -> Color(0xFFF28C1A); else -> Color(0xFFF2CC1A) }
