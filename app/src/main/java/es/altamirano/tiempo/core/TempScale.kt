package es.altamirano.tiempo.core

import kotlin.math.cbrt
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * El fondo de los widgets según la temperatura, **copiado exacto** de `TempScale`
 * (`Shared/WidgetShared.swift`) y `TempPalette` (`WidgetTheme.swift`). Bruno eligió las escalas
 * con mucho detalle. Como la leyenda de un mapa: un color fijo por tramo, sin mezclas; el tramo
 * sale de la temperatura **redondeada**, la que enseña el widget.
 */
enum class TempScale(val label: String, val colors: IntArray) {
    BANDS("Pocas bandas", intArrayOf(0x1F3A93, 0x2E7BD6, 0x1FAFAF, 0x3CB371, 0xF4C430, 0xF07F24, 0xD62828)),
    CLASSIC("Mapa clásico", intArrayOf(0x5B2C91, 0x2E3FA8, 0x1F66D1, 0x1B9AE0, 0x19B3B0, 0x2E9E5B, 0x6CC06A, 0xF2D335, 0xF7922F, 0xE5432A, 0xA3172B)),
    DIVERGING("Blanco en el medio", intArrayOf(0x053061, 0x1B4F95, 0x2166AC, 0x4393C3, 0x92C5DE, 0xD1E5F0, 0xF7F7F7, 0xFDDBC7, 0xF4A582, 0xD6604D, 0xB2182B)),
    EARTH("Tierra", intArrayOf(0x2B3440, 0x37475A, 0x45607A, 0x5B7F9C, 0x7FA0B5, 0xA8BFA0, 0xD9C58F, 0xD9A15E, 0xC8733F, 0xA4442E, 0x6E2620)),
    NEON("Neón", intArrayOf(0x3A0CA3, 0x3F37C9, 0x4361EE, 0x3A86FF, 0x00B4D8, 0x00D1A0, 0x9EF01A, 0xFFD60A, 0xFF8C1A, 0xFF4122, 0xD90429)),
    NIGHT("Nocturna", intArrayOf(0x0B1030, 0x15235E, 0x1D3A8A, 0x0F4A6B, 0x0E5E5E, 0x1F5E3A, 0x7A4E12, 0x8F3F10, 0x9C3A0F, 0x8C1C1C, 0x5C0B1A));

    /** Borde inferior de cada tramo después del primero (el primero se queda todo lo más frío). */
    private val edges: IntArray get() = if (this == BANDS) intArrayOf(1, 10, 18, 26, 32, 38) else intArrayOf(-5, 0, 5, 10, 15, 20, 25, 30, 35, 40)

    fun color(temp: Double): Int {
        val t = Math.round(temp).toInt()
        val band = edges.indexOfLast { t >= it }.let { if (it < 0) 0 else it + 1 }
        return colors[band]
    }

    companion object { val DEFAULT = BANDS }
}

/** El degradado y la tinta del widget sobre cada temperatura (`TempPalette`). */
object TempPalette {
    /** Texto azul marino para los fondos claros: el blanco no se lee sobre amarillos pálidos. */
    const val DARK_INK = 0x0B2236

    /** Arriba el color del tramo; abajo, el mismo tono un paso más oscuro en OKLab (no mezclado con negro). */
    fun gradient(temp: Double, scale: TempScale): Pair<Int, Int> {
        val top = scale.color(temp)
        return top to bottom(top)
    }

    /** ¿Tinta oscura? La que más contraste dé en el peor extremo del degradado. */
    fun prefersDarkInk(temp: Double, scale: TempScale): Boolean {
        val (top, bot) = gradient(temp, scale)
        val onWhite = min(contrast(0xFFFFFF, top), contrast(0xFFFFFF, bot))
        val onDark = min(contrast(DARK_INK, top), contrast(DARK_INK, bot))
        return onDark > onWhite
    }

    fun bottom(rgb: Int): Int {
        val (l, a, b) = oklab(rgb)
        return srgb(max(0.0, l - 0.10), a, b)
    }

    private fun ch(rgb: Int, shift: Int) = ((rgb shr shift) and 0xFF) / 255.0
    private fun linear(v: Double) = if (v <= 0.04045) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
    private fun gamma(v0: Double): Double { val v = min(1.0, max(0.0, v0)); return if (v <= 0.0031308) 12.92 * v else 1.055 * v.pow(1 / 2.4) - 0.055 }

    fun oklab(rgb: Int): Triple<Double, Double, Double> {
        val r = linear(ch(rgb, 16)); val g = linear(ch(rgb, 8)); val b = linear(ch(rgb, 0))
        val l = cbrt(0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b)
        val m = cbrt(0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b)
        val s = cbrt(0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b)
        return Triple(0.2104542553 * l + 0.7936177850 * m - 0.0040720468 * s,
            1.9779984951 * l - 2.4285922050 * m + 0.4505937099 * s,
            0.0259040371 * l + 0.7827717662 * m - 0.8086757660 * s)
    }

    private fun srgb(L: Double, A: Double, B: Double): Int {
        val l = (L + 0.3963377774 * A + 0.2158037573 * B).pow(3)
        val m = (L - 0.1055613458 * A - 0.0638541728 * B).pow(3)
        val s = (L - 0.0894841775 * A - 1.2914855480 * B).pow(3)
        val r = gamma(4.0767416621 * l - 3.3077115913 * m + 0.2309699292 * s)
        val g = gamma(-1.2684380046 * l + 2.6097574011 * m - 0.3413193965 * s)
        val b = gamma(-0.0041960863 * l - 0.7034186147 * m + 1.7076147010 * s)
        return ((r * 255).roundToInt() shl 16) or ((g * 255).roundToInt() shl 8) or (b * 255).roundToInt()
    }

    private fun contrast(a: Int, b: Int): Double {
        fun lum(c: Int) = 0.2126 * linear(ch(c, 16)) + 0.7152 * linear(ch(c, 8)) + 0.0722 * linear(ch(c, 0))
        val x = lum(a); val y = lum(b)
        return (max(x, y) + 0.05) / (min(x, y) + 0.05)
    }
}
