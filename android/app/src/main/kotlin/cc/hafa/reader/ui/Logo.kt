package cc.hafa.reader.ui

import android.provider.Settings
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import cc.hafa.reader.R
import kotlin.math.ceil
import kotlin.math.floor

private const val VIEW_SIZE = 64f
private const val CORNER = 15f
private const val CENTER_X = 19f
private const val CENTER_Y = 45f
private const val DOT_RADIUS = 6f
private const val SPACING = 13.2f
private const val FIRST_RADIUS = 16.8f
private const val STROKE = 8.1f
private const val STEP_SECONDS = 0.7
private const val NANOS_PER_SECOND = 1e9
private val SLOTS = listOf(-1, 0, 1, 2)
private val NAVY = Color(0xFF16233F)
private val AMBER = Color(0xFFF39A1E)
private val WHITE = Color.White
private val BLUE = Color(0xFF8FB4EC)

/** One arc of the logo at a moment of its animation. */
private data class Wave(val radius: Float, val width: Float, val color: Color)

private fun smooth(value: Float): Float {
    val clamped = value.coerceIn(0f, 1f)
    return clamped * clamped * (3 - 2 * clamped)
}

/** The arcs after [phase] steps outward; even whole numbers match the still logo. */
private fun wavesAt(phase: Double): List<Wave> {
    val whole = floor(phase).toInt()
    val fraction = (phase - whole).toFloat()
    return SLOTS.map { slot ->
        val radius = FIRST_RADIUS + SPACING * (slot + fraction)
        val grow = smooth((radius - (FIRST_RADIUS - SPACING)) / SPACING)
        val shrink = smooth(1 - (radius - (FIRST_RADIUS + SPACING)) / SPACING)
        Wave(radius, STROKE * minOf(grow, shrink), if ((slot - whole) % 2 == 0) WHITE else BLUE)
    }
}

/** Whether the system lets apps animate; off when its animator duration scale is zero. */
@Composable
fun animationsOn(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f
    }
}

/**
 * The H Reader logo: a navy tile, an amber dot and two arcs. While [loading],
 * the arcs move outward from the dot; when it stops, they finish the step
 * they are on. Still when the system turns animations off. [labeled] gives
 * it the app's name for screen readers; off where the name is written beside it.
 */
@Composable
fun Logo(modifier: Modifier = Modifier, loading: Boolean = false, labeled: Boolean = true) {
    val animate = animationsOn()
    var phase by remember { mutableDoubleStateOf(0.0) }
    LaunchedEffect(loading, animate) {
        if (animate && (loading || phase % 2 != 0.0)) {
            val stopAt = if (loading) null else ceil(phase / 2) * 2
            var last = withFrameNanos { now -> now }
            while (true) {
                val now = withFrameNanos { frame -> frame }
                phase += (now - last) / NANOS_PER_SECOND / STEP_SECONDS
                last = now
                if (stopAt != null && phase >= stopAt) {
                    phase = stopAt
                    break
                }
            }
        }
    }
    val label = stringResource(R.string.app_name)
    Canvas(if (labeled) modifier.semantics { contentDescription = label } else modifier) {
        val unit = size.minDimension / VIEW_SIZE
        scale(unit, pivot = Offset.Zero) {
            val tile = Path().apply {
                addRoundRect(
                    RoundRect(0f, 0f, VIEW_SIZE, VIEW_SIZE, CornerRadius(CORNER, CORNER)),
                )
            }
            drawPath(tile, NAVY)
            clipPath(tile) {
                for (wave in wavesAt(phase)) {
                    if (wave.width > 0.01f) {
                        drawArc(
                            color = wave.color,
                            startAngle = -90f,
                            sweepAngle = 90f,
                            useCenter = false,
                            topLeft = Offset(CENTER_X - wave.radius, CENTER_Y - wave.radius),
                            size = Size(wave.radius * 2, wave.radius * 2),
                            style = Stroke(width = wave.width, cap = StrokeCap.Round),
                        )
                    }
                }
            }
            drawCircle(AMBER, radius = DOT_RADIUS, center = Offset(CENTER_X, CENTER_Y))
        }
    }
}
