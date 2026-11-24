package com.noop.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.semantics.clearAndSetSemantics
import kotlin.math.sin

/**
 * Fork addition (Rick): the "Indigo" theme. Dark ribbed charcoal background, white text, and an
 * electric blue-violet accent, taken from a music-player screen Rick liked (#4F2FFF).
 *
 * Picked from Settings > Appearance > Preset > Indigo (or Accent > Indigo). It changes:
 *  - the chrome accent (buttons, links, toggles, selection) via [Palette.accent]
 *  - the backdrop behind every screen that uses the shared backdrop helpers ([IndigoTextureBackdrop])
 *  - the interval timer's colours (work = indigo, rest = soft violet)
 * NOOP's data colour worlds (recovery, effort, sleep charts) are left alone on purpose, so those numbers
 * still read the way they do everywhere else.
 */
object IndigoTheme {
    val active: Boolean get() = AccentPrefs.color == AccentColor.INDIGO

    /** The exact colour from the screenshot. Used as the accent on the light scheme. */
    val brand = Color(0xFF4F2FFF)

    /** Dark-scheme accent: the same hue lifted a little so dark button labels on it stay readable. */
    val accentDark = Color(0xFF7B5CFF)
    val accentHoverDark = Color(0xFF9B85FF)
    val accentHoverLight = Color(0xFF6A50FF)

    /** Interval timer colours. */
    val work = Color(0xFF7B5CFF)
    val rest = Color(0xFFB9A8FF)
    val workStops: List<Pair<Float, Color>> = listOf(0f to Color(0xFF3B1FE0), 1f to Color(0xFF8E75FF))
    val restStops: List<Pair<Float, Color>> = listOf(0f to Color(0xFF6C5AB8), 1f to Color(0xFFCBBEFF))

    fun accent(isLight: Boolean): Color = if (isLight) brand else accentDark
    fun accentHover(isLight: Boolean): Color = if (isLight) accentHoverLight else accentHoverDark
}

/**
 * The textured backdrop: near-black charcoal with fine diagonal ribs that bend gently, a faint indigo
 * glow low on the left, and one thin deep-red streak.
 *
 * Performance: the texture is thousands of tiny path segments, far too much to re-stroke every frame
 * (NOOP's liquid gauges and tilt effects redraw constantly, which made the app sluggish). So it is
 * rendered ONCE into a bitmap per screen size, cached app-wide in [IndigoTextureCache], and each frame
 * just draws that bitmap. Decorative only (hidden from TalkBack). On the light scheme it falls back to
 * the plain canvas.
 */
@Composable
fun IndigoTextureBackdrop() {
    val light = Palette.isLight
    val canvasColor = Palette.surfaceBase
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clearAndSetSemantics {}
            // Own render layer, so other content redrawing never re-records this one.
            .graphicsLayer {}
            .drawWithCache {
                val bmp = if (light) null else IndigoTextureCache.get(size.width.toInt(), size.height.toInt())
                onDrawBehind {
                    if (bmp == null) drawRect(canvasColor) else drawImage(bmp)
                }
            },
    )
}

/** One rendered texture, reused by every screen and frame; re-rendered only when the size changes. */
internal object IndigoTextureCache {
    private var cached: ImageBitmap? = null
    private var cw = 0
    private var ch = 0

    @Synchronized
    fun get(w: Int, h: Int): ImageBitmap? {
        if (w <= 0 || h <= 0) return null
        val c = cached
        if (c != null && cw == w && ch == h) return c
        val bmp = runCatching { render(w, h) }.getOrNull() ?: return null
        cached = bmp; cw = w; ch = h
        return bmp
    }

    private fun render(wi: Int, hi: Int): ImageBitmap {
        val bmp = ImageBitmap(wi, hi)
        val canvas = Canvas(bmp)
        val w = wi.toFloat()
        val h = hi.toFloat()
        CanvasDrawScope().draw(
            density = Density(1f),
            layoutDirection = LayoutDirection.Ltr,
            canvas = canvas,
            size = Size(w, h),
        ) {
            // Ribs run up-right; x advances `slope` px for every px climbed.
            val slope = 1.45f
            val gap = 11f
            val steps = 18
            val ribs = Path()
            val count = ((w + h * slope) / gap).toInt() + 4
            for (i in -2 until count) {
                val c = -h * slope + i * gap
                for (s in 0..steps) {
                    val y = h * (1f - s / steps.toFloat())
                    val bx = c + (h - y) * slope
                    val x = bx + 14f * sin(y / 240f + bx / 380f) + 8f * sin(bx / 160f - y / 500f)
                    if (s == 0) ribs.moveTo(x, y) else ribs.lineTo(x, y)
                }
            }
            drawRect(Color(0xFF141216))
            drawPath(ribs, Color(0xFF09080B).copy(alpha = 0.75f), style = Stroke(width = 3.4f))
            translate(left = 2.2f) {
                drawPath(ribs, Color(0xFF3E3747).copy(alpha = 0.7f), style = Stroke(width = 1.4f))
            }
            drawRect(
                Brush.radialGradient(
                    colors = listOf(Color.Transparent, Color(0xCC08070A)),
                    center = Offset(w * 0.5f, h * 0.42f),
                    radius = maxOf(w, h) * 0.85f,
                ),
            )
            drawRect(
                Brush.radialGradient(
                    colors = listOf(IndigoTheme.brand.copy(alpha = 0.16f), Color.Transparent),
                    center = Offset(w * 0.12f, h * 0.9f),
                    radius = maxOf(w, h) * 0.6f,
                ),
            )
            val streakStart = Offset(w * 0.62f, 0f)
            val streakEnd = Offset(w * 0.95f, h * 0.75f)
            val streak = Brush.linearGradient(
                colors = listOf(Color(0xAAA0182A), Color(0x00A0182A)),
                start = streakStart,
                end = streakEnd,
            )
            // Soft edge: a wide faint pass, then the core.
            drawLine(streak, streakStart, streakEnd, strokeWidth = 34f, alpha = 0.18f)
            drawLine(streak, streakStart, streakEnd, strokeWidth = 16f, alpha = 0.35f)
            drawLine(streak, streakStart, streakEnd, strokeWidth = 7f)
        }
        return bmp
    }
}
