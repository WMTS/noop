package com.noop.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
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
 * glow low on the left, and one thin deep-red streak, drawn in code so there is no image to manage.
 * The rib geometry is built once per size ([drawWithCache]) and reused on every frame.
 * Decorative only (hidden from TalkBack). On the light scheme it falls back to the plain canvas.
 */
@Composable
fun IndigoTextureBackdrop() {
    val light = Palette.isLight
    val canvasColor = Palette.surfaceBase
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clearAndSetSemantics {}
            .drawWithCache {
                val w = size.width
                val h = size.height
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
                val groove = Stroke(width = 3.4f)
                val ridge = Stroke(width = 1.4f)
                val vignette = Brush.radialGradient(
                    colors = listOf(Color.Transparent, Color(0xCC08070A)),
                    center = Offset(w * 0.5f, h * 0.42f),
                    radius = maxOf(w, h) * 0.85f,
                )
                val glow = Brush.radialGradient(
                    colors = listOf(IndigoTheme.brand.copy(alpha = 0.16f), Color.Transparent),
                    center = Offset(w * 0.12f, h * 0.9f),
                    radius = maxOf(w, h) * 0.6f,
                )
                val streakStart = Offset(w * 0.62f, 0f)
                val streakEnd = Offset(w * 0.95f, h * 0.75f)
                val streak = Brush.linearGradient(
                    colors = listOf(Color(0xAAA0182A), Color(0x00A0182A)),
                    start = streakStart,
                    end = streakEnd,
                )
                onDrawBehind {
                    if (light) {
                        drawRect(canvasColor)
                    } else {
                        drawRect(Color(0xFF141216))
                        drawPath(ribs, Color(0xFF09080B).copy(alpha = 0.75f), style = groove)
                        translate(left = 2.2f) {
                            drawPath(ribs, Color(0xFF3E3747).copy(alpha = 0.7f), style = ridge)
                        }
                        drawRect(vignette)
                        drawRect(glow)
                        // Soft edge: a wide faint pass, then the core.
                        drawLine(streak, streakStart, streakEnd, strokeWidth = 34f, alpha = 0.18f)
                        drawLine(streak, streakStart, streakEnd, strokeWidth = 16f, alpha = 0.35f)
                        drawLine(streak, streakStart, streakEnd, strokeWidth = 7f)
                    }
                }
            },
    )
}
