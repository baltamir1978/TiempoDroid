package es.altamirano.tiempo.widget

import androidx.compose.runtime.Composable
import androidx.glance.GlanceModifier
import androidx.glance.text.TextStyle
import es.altamirano.tiempo.core.tr

/** El `Text` de los widgets, traducido (`I18n`). */
@Composable
fun Text(text: String, modifier: GlanceModifier = GlanceModifier, style: TextStyle = androidx.glance.text.TextDefaults.defaultTextStyle, maxLines: Int = Int.MAX_VALUE) =
    androidx.glance.text.Text(tr(text), modifier, style, maxLines)
