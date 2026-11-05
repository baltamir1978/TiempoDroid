package es.altamirano.tiempo.core

import java.util.Locale

/**
 * Los idiomas (en, gl, eu, ca), como el catálogo de iOS: **la clave es el texto en español** y
 * cada idioma tiene su tabla en `assets/i18n/<idioma>.tsv` (clave, tabulador, traducción). Lo
 * variable va como `{}` en la clave; en la traducción, `{}` en el mismo orden o `{1}`, `{2}`…
 * para cambiarlo. Lo que se rellena en un hueco también se traduce si está en la tabla («nivel
 * {}» con «amarillo»). Sin tabla o sin la clave, el español tal cual.
 *
 * Se traduce al pintar (los `Text` de `ui` y `widget` pasan por aquí), así los textos que llegan
 * de AEMET en español («Intervalos nubosos») también salen en el idioma del móvil.
 */
object I18n {
    val LANGUAGES = listOf("es", "en", "gl", "eu", "ca")

    /** Quién lee las tablas (la app, desde `assets`); en las pruebas, nadie: español. */
    var loader: ((String) -> String?)? = null

    private class Table(val exact: Map<String, String>, val patterns: List<Pair<Regex, String>>) {
        val cache = HashMap<String, String>()
    }
    private val tables = HashMap<String, Table?>()

    /** El del móvil (o el elegido para la app en Ajustes de Android) si es uno de los nuestros; si no, español, como en iOS. */
    fun language(): String = Locale.getDefault().language.let { if (it in LANGUAGES) it else "es" }

    /** El idioma de las fechas: el del móvil si es uno de los nuestros; si no, español. */
    fun locale(): Locale = when (val l = language()) { "es" -> Locale("es", "ES"); "en" -> Locale.UK; else -> Locale(l, "ES") }

    @Synchronized
    private fun table(lang: String): Table? = tables.getOrPut(lang) {
        val text = loader?.invoke(lang) ?: return@getOrPut null
        parse(text)
    }

    private fun parse(text: String): Table {
        val exact = HashMap<String, String>(); val patterns = mutableListOf<Pair<Regex, String>>()
        text.lineSequence().filter { it.isNotBlank() && !it.startsWith("#") && '\t' in it }.forEach { line ->
            val (k, v) = line.split('\t', limit = 2)
            val key = k.replace("\\n", "\n"); val value = v.replace("\\n", "\n")
            if ("{}" in key) patterns += Regex("^" + key.split("{}").joinToString("(.+?)") { Regex.escape(it) } + "$", RegexOption.DOT_MATCHES_ALL) to value
            else exact[key.lowercase()] = value
        }
        return Table(exact, patterns.sortedByDescending { it.first.pattern.length })
    }

    /** Para probar con una tabla escrita a mano. */
    fun install(lang: String, text: String) { synchronized(this) { tables[lang] = parse(text) } }

    fun tr(text: String, lang: String = language()): String {
        if (lang == "es" || text.isBlank()) return text
        val t = table(lang) ?: return text
        synchronized(t) { t.cache[text]?.let { return it } }
        val core = text.trim()
        val lead = text.substring(0, text.indexOf(core.first()))
        val trail = text.substring(lead.length + core.length)
        val out = lead + (exact(t, core) ?: pattern(t, core) ?: core) + trail
        synchronized(t) { if (t.cache.size > 4000) t.cache.clear(); t.cache[text] = out }
        return out
    }

    /** Respeta las mayúsculas: «EXTERIOR» sale «OUTDOOR». */
    private fun exact(t: Table, s: String): String? {
        val v = t.exact[s.lowercase()] ?: return null
        return if (s.any { it.isLetter() } && s == s.uppercase() && s != s.lowercase()) v.uppercase(locale()) else v
    }

    private fun pattern(t: Table, s: String): String? {
        for ((re, value) in t.patterns) {
            val m = re.matchEntire(s) ?: continue
            val args = m.groupValues.drop(1).map { a -> exact(t, a.trim())?.let { a.replace(a.trim(), it) } ?: a }
            var i = 0
            return Regex("\\{(\\d*)\\}").replace(value) { r ->
                val n = r.groupValues[1].toIntOrNull()?.minus(1) ?: i++
                args.getOrElse(n) { "" }
            }
        }
        return null
    }
}

fun tr(text: String) = I18n.tr(text)
