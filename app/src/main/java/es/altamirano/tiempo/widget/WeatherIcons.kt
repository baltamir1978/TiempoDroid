package es.altamirano.tiempo.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import es.altamirano.tiempo.core.Sky
import es.altamirano.tiempo.core.skyOf
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Los iconos del tiempo de los widgets, dibujados aquí (Glance no pinta vectores con degradado y
 * los emojis salen con el estilo de cada móvil). Inspirados en el aire de Breezy Weather pero
 * propios: sol con halo, luna en creciente, nubes con volumen, gotas, copos, rayo y niebla.
 * Todo en un lienzo de 100 × 100 que se escala; cada icono se guarda la primera vez.
 */
object WeatherIcons {
    private val cache = HashMap<String, Bitmap>()

    /** El icono de un código de cielo de AEMET (los de Open-Meteo y el IPMA ya vienen traducidos a ellos). */
    fun sky(context: Context, code: String?, sizeDp: Int): Bitmap {
        val px = (sizeDp * context.resources.displayMetrics.density).roundToInt().coerceAtLeast(8)
        return synchronized(cache) { cache.getOrPut("sky:$code:$px") { draw(px) { c -> paintSky(c, code) } } }
    }

    /** Iconos pequeños de una tinta: gota (humedad), viento, casa, orto y ocaso. */
    fun glyph(context: Context, kind: String, ink: Int, sizeDp: Int): Bitmap {
        val px = (sizeDp * context.resources.displayMetrics.density).roundToInt().coerceAtLeast(8)
        return synchronized(cache) { cache.getOrPut("g:$kind:$ink:$px") { draw(px) { c -> paintGlyph(c, kind, ink) } } }
    }

    /** La luna con su fase de verdad: `phase` 0 nueva, 0,5 llena, 1 nueva otra vez. */
    fun moon(context: Context, phase: Double, sizeDp: Int): Bitmap {
        val px = (sizeDp * context.resources.displayMetrics.density).roundToInt().coerceAtLeast(8)
        val step = (phase.coerceIn(0.0, 1.0) * 60).roundToInt()
        return synchronized(cache) { cache.getOrPut("m:$step:$px") { draw(px) { c -> paintMoonPhase(c, step / 60.0) } } }
    }

    private fun draw(px: Int, body: (Canvas) -> Unit): Bitmap {
        val b = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        c.scale(px / 100f, px / 100f)
        body(c)
        return b
    }

    private fun paint() = Paint(Paint.ANTI_ALIAS_FLAG)

    // --- Piezas ----------------------------------------------------------------------------

    private fun sun(c: Canvas, cx: Float, cy: Float, r: Float) {
        val p = paint()
        // Halo suave y rayos cortos redondeados.
        p.shader = RadialGradient(cx, cy, r * 1.9f, intArrayOf(0x55FFD54F, 0x00FFD54F), null, Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, r * 1.9f, p); p.shader = null
        p.color = 0xFFFFB300.toInt(); p.strokeWidth = r * 0.22f; p.strokeCap = Paint.Cap.ROUND; p.style = Paint.Style.STROKE
        for (i in 0 until 8) {
            val a = Math.PI / 4 * i
            c.drawLine(cx + (cos(a) * r * 1.3).toFloat(), cy + (sin(a) * r * 1.3).toFloat(), cx + (cos(a) * r * 1.6).toFloat(), cy + (sin(a) * r * 1.6).toFloat(), p)
        }
        p.style = Paint.Style.FILL; p.color = 0xFF000000.toInt()
        p.shader = LinearGradient(cx - r, cy - r, cx + r, cy + r, 0xFFFFE27A.toInt(), 0xFFFFA000.toInt(), Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, r, p)
    }

    private fun crescent(c: Canvas, cx: Float, cy: Float, r: Float) {
        val layer = c.saveLayer(cx - r * 2, cy - r * 2, cx + r * 2, cy + r * 2, null)
        val p = paint()
        p.shader = RadialGradient(cx, cy, r * 1.7f, intArrayOf(0x40B9CCFF, 0x00B9CCFF), null, Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, r * 1.7f, p)
        p.color = 0xFF000000.toInt()
        p.shader = LinearGradient(cx - r, cy - r, cx + r, cy + r, 0xFFF3F6FF.toInt(), 0xFFA9BEF2.toInt(), Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, r, p)
        p.shader = null; p.color = 0xFF000000.toInt(); p.xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
        c.drawCircle(cx + r * 0.55f, cy - r * 0.38f, r * 0.86f, p)
        c.restoreToCount(layer)
    }

    /** Una nube de tres lóbulos sobre una base plana; `dark` para las de lluvia y tormenta. */
    private fun cloud(c: Canvas, x: Float, y: Float, w: Float, dark: Boolean, back: Boolean = false) {
        val h = w * 0.58f
        val path = Path().apply {
            addRoundRect(RectF(x, y + h * 0.45f, x + w, y + h), h * 0.28f, h * 0.28f, Path.Direction.CW)
            addCircle(x + w * 0.30f, y + h * 0.50f, w * 0.20f, Path.Direction.CW)
            addCircle(x + w * 0.56f, y + h * 0.36f, w * 0.27f, Path.Direction.CW)
            addCircle(x + w * 0.80f, y + h * 0.60f, w * 0.17f, Path.Direction.CW)
        }
        val p = paint()
        // Sombra debajo, para que la nube se separe del fondo verde.
        c.save(); c.translate(0f, w * 0.045f)
        p.color = 0x2A0B2236; c.drawPath(path, p)
        c.restore()
        val (top, bottom) = when {
            dark && back -> 0xFF8C9BA8.toInt() to 0xFF64727F.toInt()
            dark -> 0xFFB7C3CD.toInt() to 0xFF7D8C99.toInt()
            back -> 0xFFE4EAF0.toInt() to 0xFFC3CDD7.toInt()
            else -> 0xFFFFFFFF.toInt() to 0xFFDDE5EC.toInt()
        }
        // Otro Paint: el alfa de la sombra también apagaría el degradado.
        c.drawPath(path, paint().apply { shader = LinearGradient(0f, y, 0f, y + h, top, bottom, Shader.TileMode.CLAMP) })
    }

    private fun drops(c: Canvas, x: Float, y: Float, n: Int) {
        val p = paint().apply { color = 0xFF3FB5F5.toInt(); strokeWidth = 5.5f; strokeCap = Paint.Cap.ROUND; style = Paint.Style.STROKE }
        val gap = 15f
        val x0 = x - gap * (n - 1) / 2
        for (i in 0 until n) { val dx = x0 + gap * i; val dy = if (i % 2 == 0) 0f else 6f; c.drawLine(dx + 3, y + dy, dx - 2, y + dy + 12, p) }
    }

    private fun flakes(c: Canvas, x: Float, y: Float, n: Int) {
        val p = paint().apply { color = 0xFFFFFFFF.toInt(); strokeWidth = 2.6f; strokeCap = Paint.Cap.ROUND; style = Paint.Style.STROKE }
        val o = paint().apply { color = 0x553D7FB8; strokeWidth = 5.2f; strokeCap = Paint.Cap.ROUND; style = Paint.Style.STROKE }
        val gap = 17f
        val x0 = x - gap * (n - 1) / 2
        for (i in 0 until n) {
            val cx = x0 + gap * i; val cy = y + if (i % 2 == 0) 2f else 10f
            for (pp in listOf(o, p)) for (k in 0 until 3) {
                val a = Math.PI / 3 * k
                c.drawLine(cx - (cos(a) * 6).toFloat(), cy - (sin(a) * 6).toFloat(), cx + (cos(a) * 6).toFloat(), cy + (sin(a) * 6).toFloat(), pp)
            }
        }
    }

    private fun bolt(c: Canvas, x: Float, y: Float) {
        val path = Path().apply {
            moveTo(x + 4, y); lineTo(x - 9, y + 19); lineTo(x - 1, y + 19); lineTo(x - 6, y + 34)
            lineTo(x + 10, y + 12); lineTo(x + 2, y + 12); lineTo(x + 8, y); close()
        }
        val p = paint()
        p.shader = LinearGradient(0f, y, 0f, y + 34, 0xFFFFE066.toInt(), 0xFFFFA000.toInt(), Shader.TileMode.CLAMP)
        c.drawPath(path, p); p.shader = null
        p.style = Paint.Style.STROKE; p.strokeWidth = 1.5f; p.color = 0x66B26A00; p.strokeJoin = Paint.Join.ROUND
        c.drawPath(path, p)
    }

    private fun fog(c: Canvas, y: Float, lines: Int) {
        val p = paint().apply { strokeWidth = 6f; strokeCap = Paint.Cap.ROUND; style = Paint.Style.STROKE }
        for (i in 0 until lines) {
            p.color = if (i % 2 == 0) 0xE6E8EEF3.toInt() else 0xB3D5DEE6.toInt()
            val inset = if (i % 2 == 0) 18f else 28f
            c.drawLine(inset, y + i * 11f, 100 - inset, y + i * 11f, p)
        }
    }

    // --- Cielos ----------------------------------------------------------------------------

    private fun paintSky(c: Canvas, code: String?) {
        val n = code?.filter { it.isDigit() }?.toIntOrNull() ?: 0
        val night = code?.endsWith("n") == true
        fun orb(cx: Float, cy: Float, r: Float) = if (night) crescent(c, cx, cy, r * 1.05f) else sun(c, cx, cy, r)
        when (skyOf(code)) {
            Sky.CLEAR -> sun(c, 50f, 50f, 22f)
            Sky.CLEAR_NIGHT -> crescent(c, 50f, 50f, 28f)
            Sky.PARTLY, Sky.PARTLY_NIGHT, Sky.HIGH_CLOUDS -> {
                // 12 poco nuboso: el sol manda; 13 intervalos y 17 nubes altas: más nube.
                if (n == 12) { orb(58f, 40f, 19f); cloud(c, 14f, 50f, 52f, false) }
                else { orb(62f, 36f, 17f); cloud(c, 12f, 44f, 66f, false) }
            }
            Sky.CLOUDY -> {
                if (n == 14) { orb(68f, 32f, 13f); cloud(c, 10f, 38f, 74f, false) }
                else { cloud(c, 38f, 22f, 52f, false, back = true); cloud(c, 10f, 40f, 70f, false) }
            }
            Sky.RAIN -> {
                if (n == 23) orb(68f, 26f, 12f) else cloud(c, 40f, 14f, 48f, true, back = true)
                cloud(c, 12f, 28f, 72f, n >= 25); drops(c, 48f, 76f, 3)
            }
            Sky.SHOWER -> { orb(66f, 28f, 14f); cloud(c, 12f, 32f, 68f, false); drops(c, 46f, 76f, 2) }
            Sky.THUNDER -> { cloud(c, 40f, 12f, 48f, true, back = true); cloud(c, 12f, 26f, 72f, true); bolt(c, 50f, 60f) }
            Sky.THUNDER_RAIN -> { cloud(c, 40f, 12f, 48f, true, back = true); cloud(c, 12f, 26f, 72f, true); bolt(c, 56f, 60f); drops(c, 32f, 72f, 2) }
            Sky.SNOW -> { cloud(c, 12f, 22f, 76f, false); flakes(c, 50f, 74f, 3) }
            Sky.LIGHT_SNOW -> { orb(66f, 28f, 14f); cloud(c, 12f, 30f, 70f, false); flakes(c, 46f, 76f, 2) }
            Sky.FOG -> { cloud(c, 16f, 18f, 68f, false, back = true); fog(c, 60f, 3) }
            Sky.MIST -> { orb(50f, 36f, 16f); fog(c, 58f, 3) }
            Sky.UNKNOWN -> cloud(c, 14f, 32f, 72f, false, back = true)
        }
    }

    private fun paintGlyph(c: Canvas, kind: String, ink: Int) {
        val p = paint().apply { color = ink }
        when (kind) {
            "drop" -> c.drawPath(Path().apply {
                moveTo(50f, 10f); cubicTo(50f, 10f, 82f, 46f, 82f, 64f); cubicTo(82f, 82f, 67f, 92f, 50f, 92f)
                cubicTo(33f, 92f, 18f, 82f, 18f, 64f); cubicTo(18f, 46f, 50f, 10f, 50f, 10f); close()
            }, p)
            "wind" -> {
                p.style = Paint.Style.STROKE; p.strokeWidth = 9f; p.strokeCap = Paint.Cap.ROUND
                c.drawPath(Path().apply { moveTo(10f, 38f); lineTo(62f, 38f); cubicTo(80f, 38f, 82f, 14f, 66f, 14f) }, p)
                c.drawPath(Path().apply { moveTo(10f, 62f); lineTo(74f, 62f); cubicTo(94f, 62f, 94f, 88f, 76f, 88f) }, p)
            }
            "home" -> c.drawPath(Path().apply {
                moveTo(50f, 12f); lineTo(90f, 48f); lineTo(78f, 48f); lineTo(78f, 88f); lineTo(58f, 88f); lineTo(58f, 64f)
                lineTo(42f, 64f); lineTo(42f, 88f); lineTo(22f, 88f); lineTo(22f, 48f); lineTo(10f, 48f); close()
            }, p)
            "rise", "set" -> {
                // Medio sol en el horizonte y una flecha hacia arriba o hacia abajo.
                c.drawArc(RectF(26f, 46f, 74f, 94f), 180f, 180f, true, p)
                p.style = Paint.Style.STROKE; p.strokeWidth = 7f; p.strokeCap = Paint.Cap.ROUND
                c.drawLine(10f, 72f, 90f, 72f, p)
                val up = kind == "rise"
                c.drawLine(50f, 8f, 50f, 36f, p)
                if (up) { c.drawLine(50f, 8f, 40f, 18f, p); c.drawLine(50f, 8f, 60f, 18f, p) }
                else { c.drawLine(50f, 36f, 40f, 26f, p); c.drawLine(50f, 36f, 60f, 26f, p) }
            }
        }
    }

    /** La luna iluminada como se ve: el limbo y el terminador, que es media elipse. */
    private fun paintMoonPhase(c: Canvas, phase: Double) {
        val cx = 50f; val cy = 50f; val r = 38f
        val p = paint()
        p.shader = RadialGradient(cx, cy, r * 1.3f, intArrayOf(0x33DDE6FF, 0x00DDE6FF), null, Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, r * 1.3f, p); p.shader = null
        p.color = 0x553A4A66; c.drawCircle(cx, cy, r, p)          // la parte en sombra
        val lit = Path()
        val waxing = phase < 0.5
        val k = cos(phase * 2 * Math.PI).toFloat()               // 1 nueva, −1 llena
        val oval = RectF(cx - r * kotlin.math.abs(k), cy - r, cx + r * kotlin.math.abs(k), cy + r)
        // Medio disco del lado iluminado (derecha al crecer, en el hemisferio norte)…
        lit.addArc(RectF(cx - r, cy - r, cx + r, cy + r), if (waxing) -90f else 90f, 180f)
        // …y el terminador: la elipse que resta (creciente) o suma (gibosa).
        p.color = 0xFF000000.toInt()
        p.shader = LinearGradient(cx - r, cy - r, cx + r, cy + r, 0xFFFBFCFF.toInt(), 0xFFC4D1EE.toInt(), Shader.TileMode.CLAMP)
        val layer = c.saveLayer(0f, 0f, 100f, 100f, null)
        c.drawPath(lit, p)
        if (k > 0) { p.shader = null; p.xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR); c.drawOval(oval, p); p.xfermode = null }
        else c.drawOval(oval, p)
        c.restoreToCount(layer)
    }
}
