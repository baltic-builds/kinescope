package com.kinescope.app

import android.content.Context
import android.provider.Settings
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp

// Patch 55: the heartbeat line of the Home dashboard. It replaces the speed readout: a green pulse that travels along the
// line while the bypass carries traffic, a red flat line when the connection is lost (see `DashPulse` for what each state
// means). It is the one continuous animation in the app, and it is tied to a MEASURED state (the tunnel's traffic verdict),
// never to the engine merely having started. With the system's animations switched off it holds still (one beat, drawn
// once), so nothing moves for a person who asked for that.

/** How long one beat takes to travel its own width: about 55 beats a minute. */
private const val BEAT_PERIOD_MS = 1_100f

/** False when the person turned the system's animations off (the animator duration scale is 0). */
private fun animationsEnabled(context: Context): Boolean =
    Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f

@Composable
internal fun HeartbeatLine(pulse: DashPulse, description: String, modifier: Modifier = Modifier) {
    val colors = YtOfflineExtras.colors
    val color = when (pulse) {
        DashPulse.ALIVE -> colors.pulseAlive
        DashPulse.LOST -> colors.pulseLost
        DashPulse.IDLE -> MaterialTheme.colorScheme.outline
    }
    val context = LocalContext.current
    val systemAnimates = remember { animationsEnabled(context) }
    val animate = pulse == DashPulse.ALIVE && systemAnimates
    val phase = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(animate) {
        if (!animate) return@LaunchedEffect
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                if (last != 0L) phase.floatValue = (phase.floatValue + (now - last) / (BEAT_PERIOD_MS * 1_000_000f)) % 1f
                last = now
            }
        }
    }
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(36.dp)
            .clearAndSetSemantics { contentDescription = description }
    ) {
        drawHeartbeat(pulse, color, if (animate) phase.floatValue else 0f)
    }
}

private fun DrawScope.drawHeartbeat(pulse: DashPulse, color: androidx.compose.ui.graphics.Color, phase: Float) {
    val width = size.width
    val middle = size.height / 2f
    val strokeWidth = 2.5.dp.toPx()
    if (pulse != DashPulse.ALIVE) {
        drawLine(color = color, start = Offset(0f, middle), end = Offset(width, middle), strokeWidth = strokeWidth, cap = StrokeCap.Round)
        return
    }
    val beatWidth = 120.dp.toPx()
    val amplitude = size.height * 0.42f
    val step = 2.dp.toPx()
    val path = Path()
    var x = 0f
    var first = true
    while (x <= width) {
        // The line scrolls to the left: the place within the beat at x moves on with the phase.
        val t = ((x / beatWidth) + phase) % 1f
        val y = middle - heartbeatSample(t) * amplitude
        if (first) {
            path.moveTo(x, y)
            first = false
        } else {
            path.lineTo(x, y)
        }
        x += step
    }
    // The line fades out at both ends, so the pulse seems to come from nowhere and go nowhere.
    val fade = Brush.horizontalGradient(
        0f to color.copy(alpha = 0f),
        0.14f to color,
        0.86f to color,
        1f to color.copy(alpha = 0f),
        startX = 0f,
        endX = width
    )
    drawPath(path = path, brush = fade, style = Stroke(width = strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round))
}
