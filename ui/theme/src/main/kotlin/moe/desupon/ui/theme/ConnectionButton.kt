package moe.desupon.ui.theme

import android.animation.ValueAnimator
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

@Immutable
sealed interface ConnectionButtonState {
    data object Disconnected : ConnectionButtonState

    /** One entry per subscription, in display order: true once it has a live server. */
    @Immutable
    data class Checking(val subscriptionsAlive: List<Boolean>) : ConnectionButtonState

    data object Connecting : ConnectionButtonState
    data object Connected : ConnectionButtonState
    data object Switching : ConnectionButtonState
    data object Error : ConnectionButtonState
}

/**
 * Round connect button with a caption below. States differ by glyph, ring style and text, not color alone.
 *
 * @param label caption override (e.g. "Проверяю 12/48 · 3 подписки"); null shows the state's default, desu-aware.
 * @param timer shown under the glyph in [ConnectionButtonState.Connected], styled with [timer] typography.
 * @param showCaption false leaves the caption to the caller, so the button measures as exactly [size].
 */
@Composable
fun ConnectionButton(
    state: ConnectionButtonState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    enabled: Boolean = true,
    size: Dp = 200.dp,
    timer: (@Composable () -> Unit)? = null,
    showCaption: Boolean = true,
) {
    val colors = MaterialTheme.colorScheme
    val brand = DesuponTheme.brand
    val animate = remember { ValueAnimator.areAnimatorsEnabled() }
    val kind = state.kind

    StateHaptics(kind)

    val spin = loop(animate && kind in SpinningKinds, durationMs = if (kind == Kind.Connecting) 1200 else 2400)
    val drops = loop(animate && kind in PouringKinds, durationMs = 900)
    val breath = loop(animate && kind == Kind.Connected, durationMs = 2000, reverse = true, static = 0.5f)

    val segments = remember(state) {
        (state as? ConnectionButtonState.Checking)?.let { checkingSegments(it.subscriptionsAlive.size) }.orEmpty()
    }
    val alive = (state as? ConnectionButtonState.Checking)?.subscriptionsAlive.orEmpty()

    val description = stringResource(R.string.desupon_cb_cd)
    val stateText = stateDescriptionFor(state)
    val clickLabel = stringResource(
        if (kind == Kind.Disconnected || kind == Kind.Error) R.string.desupon_cb_action_connect
        else R.string.desupon_cb_action_disconnect,
    )

    Column(
        modifier = modifier.alpha(if (enabled) 1f else 0.38f),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(size)
                .drawBehind {
                    drawButton(
                        kind = kind,
                        colors = ButtonColors(
                            outline = colors.outline,
                            outlineVariant = colors.outlineVariant,
                            primary = colors.primary,
                            tertiary = colors.tertiary,
                            error = colors.error,
                            surfaceContainer = colors.surfaceContainer,
                            primaryContainer = colors.primaryContainer,
                            glow = brand.suiDream,
                        ),
                        segments = segments,
                        alive = alive,
                        spin = spin.value,
                        breath = breath.value,
                        orbVisible = animate,
                    )
                }
                .clip(CircleShape)
                .semantics(mergeDescendants = true) {
                    contentDescription = description
                    stateDescription = stateText
                }
                .clickable(
                    enabled = enabled,
                    role = Role.Button,
                    onClickLabel = clickLabel,
                    onClick = onClick,
                ),
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                AnimatedContent(
                    targetState = kind.glyph,
                    transitionSpec = {
                        (fadeIn(tween(220)) + scaleIn(tween(220), initialScale = 0.85f)) togetherWith fadeOut(tween(120))
                    },
                    label = "glyph",
                ) { glyph ->
                    Glyph(glyph, tint = glyphTint(kind), drops = drops, size = size * GlyphRatio)
                }
                if (kind == Kind.Connected && timer != null) {
                    Spacer(Modifier.height(4.dp))
                    CompositionLocalProvider(
                        LocalContentColor provides colors.onPrimaryContainer,
                        LocalTextStyle provides MaterialTheme.typography.timer,
                        content = timer,
                    )
                }
            }
        }
        if (showCaption) {
            Spacer(Modifier.height(24.dp))
            AnimatedContent(
                targetState = label ?: defaultLabel(kind),
                transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(120)) },
                label = "caption",
            ) { text ->
                Text(
                    text = text,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (kind == Kind.Error) colors.error else colors.onSurface,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/** Ticking "HH:MM:SS" with tabular figures; inherits color from [ConnectionButton]'s timer slot. */
@Composable
fun ConnectionTimer(elapsedSeconds: Long, modifier: Modifier = Modifier) {
    Text(text = formatElapsed(elapsedSeconds), modifier = modifier, style = MaterialTheme.typography.timer)
}

fun formatElapsed(totalSeconds: Long): String {
    val s = totalSeconds.coerceAtLeast(0)
    val h = s / 3600
    val m = s % 3600 / 60
    val sec = s % 60
    return String.format(Locale.ROOT, "%02d:%02d:%02d", h, m, sec)
}

internal data class ArcSegment(val startDegrees: Float, val sweepDegrees: Float)

/** Splits the ring clockwise from 12 o'clock into [count] equal arcs separated by gaps. */
internal fun checkingSegments(count: Int, maxGapDegrees: Float = 8f): List<ArcSegment> {
    if (count <= 1) return listOf(ArcSegment(-90f, 360f))
    val slot = 360f / count
    val gap = min(maxGapDegrees, slot * 0.3f)
    return List(count) { i -> ArcSegment(-90f + i * slot + gap / 2, slot - gap) }
}

internal enum class Kind(val glyph: GlyphKind) {
    Disconnected(GlyphKind.DryCan),
    Checking(GlyphKind.PouringCan),
    Connecting(GlyphKind.PouringCan),
    Connected(GlyphKind.Sprout),
    Switching(GlyphKind.PouringCan),
    Error(GlyphKind.Wilted),
}

internal enum class GlyphKind { DryCan, PouringCan, Sprout, Wilted }

internal val ConnectionButtonState.kind: Kind
    get() = when (this) {
        ConnectionButtonState.Disconnected -> Kind.Disconnected
        is ConnectionButtonState.Checking -> Kind.Checking
        ConnectionButtonState.Connecting -> Kind.Connecting
        ConnectionButtonState.Connected -> Kind.Connected
        ConnectionButtonState.Switching -> Kind.Switching
        ConnectionButtonState.Error -> Kind.Error
    }

private val SpinningKinds = setOf(Kind.Checking, Kind.Connecting, Kind.Switching)
private val PouringKinds = setOf(Kind.Checking, Kind.Connecting, Kind.Switching)
private const val GlyphRatio = 0.36f
private const val PourTiltDegrees = 30f

@Composable
private fun loop(
    running: Boolean,
    durationMs: Int,
    reverse: Boolean = false,
    static: Float = 0f,
): State<Float> {
    if (!running) return remember(static) { mutableFloatStateOf(static) }
    return rememberInfiniteTransition(label = "loop").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMs, easing = if (reverse) FastOutSlowInEasing else LinearEasing),
            repeatMode = if (reverse) RepeatMode.Reverse else RepeatMode.Restart,
        ),
        label = "loop",
    )
}

@Composable
private fun StateHaptics(kind: Kind) {
    val haptics = LocalHapticFeedback.current
    var last by remember { mutableStateOf(kind) }
    LaunchedEffect(kind) {
        if (kind == last) return@LaunchedEffect
        when (kind) {
            Kind.Connected -> haptics.performHapticFeedback(HapticFeedbackType.Confirm)
            Kind.Error -> haptics.performHapticFeedback(HapticFeedbackType.Reject)
            Kind.Switching -> haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
            else -> Unit
        }
        last = kind
    }
}

@Composable
private fun glyphTint(kind: Kind): Color {
    val c = MaterialTheme.colorScheme
    return when (kind) {
        Kind.Disconnected -> c.onSurfaceVariant
        Kind.Checking, Kind.Connecting -> c.primary
        Kind.Connected, Kind.Switching -> c.onPrimaryContainer
        Kind.Error -> c.error
    }
}

@Composable
private fun defaultLabel(kind: Kind): String = when (kind) {
    Kind.Disconnected -> desuStringResource(R.string.desupon_cb_label_disconnected, R.string.desupon_cb_label_disconnected_desu)
    Kind.Checking -> desuStringResource(R.string.desupon_cb_label_checking, R.string.desupon_cb_label_checking_desu)
    Kind.Connecting -> desuStringResource(R.string.desupon_cb_label_connecting, R.string.desupon_cb_label_connecting_desu)
    Kind.Connected -> desuStringResource(R.string.desupon_cb_label_connected, R.string.desupon_cb_label_connected_desu)
    Kind.Switching -> desuStringResource(R.string.desupon_cb_label_switching, R.string.desupon_cb_label_switching_desu)
    Kind.Error -> desuStringResource(R.string.desupon_cb_label_error, R.string.desupon_cb_label_error_desu)
}

@Composable
private fun stateDescriptionFor(state: ConnectionButtonState): String = when (state) {
    ConnectionButtonState.Disconnected -> stringResource(R.string.desupon_cb_state_disconnected)
    is ConnectionButtonState.Checking -> {
        val total = state.subscriptionsAlive.size
        pluralStringResource(R.plurals.desupon_cb_state_checking, total, state.subscriptionsAlive.count { it }, total)
    }
    ConnectionButtonState.Connecting -> stringResource(R.string.desupon_cb_state_connecting)
    ConnectionButtonState.Connected -> stringResource(R.string.desupon_cb_state_connected)
    ConnectionButtonState.Switching -> stringResource(R.string.desupon_cb_state_switching)
    ConnectionButtonState.Error -> stringResource(R.string.desupon_cb_state_error)
}

@Composable
private fun Glyph(kind: GlyphKind, tint: Color, drops: State<Float>, size: Dp) {
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        when (kind) {
            GlyphKind.DryCan -> Icon(DesuponGlyphs.WateringCan, null, Modifier.size(size), tint)
            GlyphKind.PouringCan -> {
                Icon(DesuponGlyphs.WateringCan, null, Modifier.size(size).rotate(PourTiltDegrees), tint)
                Canvas(Modifier.size(size)) { drawDrops(tint, drops.value) }
            }
            GlyphKind.Sprout -> Icon(DesuponGlyphs.Sprout, null, Modifier.size(size), tint)
            GlyphKind.Wilted -> Icon(DesuponGlyphs.WiltedLeaf, null, Modifier.size(size), tint)
        }
    }
}

/** Three staggered drops falling from the tilted spout; [progress] loops 0..1. */
private fun DrawScope.drawDrops(color: Color, progress: Float) {
    val unit = size.width / 24f
    val tip = rotateAround(DesuponGlyphs.SpoutTipX, DesuponGlyphs.SpoutTipY, 12f, 12f, PourTiltDegrees)
    val fall = size.height * 0.45f
    repeat(3) { i ->
        val p = (progress + i / 3f) % 1f
        drawCircle(
            color = color.copy(alpha = (1f - p) * color.alpha),
            radius = unit * (1.1f - 0.3f * p),
            center = Offset(tip.x * unit + p * unit * 1.5f, tip.y * unit + unit * 1.6f + p * fall),
        )
    }
}

internal fun rotateAround(x: Float, y: Float, cx: Float, cy: Float, degrees: Float): Offset {
    val rad = degrees * PI.toFloat() / 180f
    val dx = x - cx
    val dy = y - cy
    return Offset(cx + dx * cos(rad) - dy * sin(rad), cy + dx * sin(rad) + dy * cos(rad))
}

private class ButtonColors(
    val outline: Color,
    val outlineVariant: Color,
    val primary: Color,
    val tertiary: Color,
    val error: Color,
    val surfaceContainer: Color,
    val primaryContainer: Color,
    val glow: Color,
)

private fun DrawScope.drawButton(
    kind: Kind,
    colors: ButtonColors,
    segments: List<ArcSegment>,
    alive: List<Boolean>,
    spin: Float,
    breath: Float,
    orbVisible: Boolean,
) {
    val stroke = 4.dp.toPx()
    val c = center
    val outer = size.minDimension / 2
    val r = outer - stroke / 2

    if (kind == Kind.Connected) {
        val haloRadius = outer + 20.dp.toPx()
        val alpha = 0.12f + 0.16f * breath
        drawCircle(
            brush = Brush.radialGradient(
                0f to colors.glow.copy(alpha = alpha),
                outer / haloRadius to colors.glow.copy(alpha = alpha),
                1f to Color.Transparent,
                center = c,
                radius = haloRadius,
            ),
            radius = haloRadius,
            center = c,
        )
    }

    val fill = when (kind) {
        Kind.Connected -> colors.primaryContainer
        Kind.Switching -> colors.primaryContainer.copy(alpha = 0.6f)
        else -> colors.surfaceContainer
    }
    drawCircle(fill, radius = outer, center = c)

    val arcTopLeft = Offset(c.x - r, c.y - r)
    val arcSize = Size(2 * r, 2 * r)
    when (kind) {
        Kind.Disconnected -> drawCircle(colors.outline, r, c, style = Stroke(stroke))

        Kind.Checking -> {
            segments.forEachIndexed { i, seg ->
                val lit = alive.getOrElse(i) { false }
                drawArc(
                    color = if (lit) colors.primary else colors.outline,
                    startAngle = seg.startDegrees,
                    sweepAngle = seg.sweepDegrees,
                    useCenter = false,
                    topLeft = arcTopLeft,
                    size = arcSize,
                    style = Stroke(if (lit) stroke * 1.5f else stroke * 0.6f, cap = StrokeCap.Butt),
                )
            }
            if (orbVisible) {
                val angle = (-90f + 360f * spin) * PI.toFloat() / 180f
                val orb = Offset(c.x + r * cos(angle), c.y + r * sin(angle))
                val orbRadius = 8.dp.toPx()
                drawCircle(
                    brush = Brush.radialGradient(
                        0f to colors.glow,
                        0.45f to colors.glow.copy(alpha = 0.6f),
                        1f to Color.Transparent,
                        center = orb,
                        radius = orbRadius,
                    ),
                    radius = orbRadius,
                    center = orb,
                )
            }
        }

        Kind.Connecting -> {
            drawCircle(colors.outlineVariant, r, c, style = Stroke(stroke / 2))
            rotate(degrees = spin * 360f - 90f, pivot = c) {
                drawArc(
                    brush = Brush.sweepGradient(0f to Color.Transparent, 0.3f to colors.primary, center = c),
                    startAngle = 0f,
                    sweepAngle = 108f,
                    useCenter = false,
                    topLeft = arcTopLeft,
                    size = arcSize,
                    style = Stroke(stroke, cap = StrokeCap.Round),
                )
            }
        }

        Kind.Connected -> drawCircle(colors.primary, r, c, style = Stroke(stroke))

        Kind.Switching -> {
            val circumference = 2 * PI.toFloat() * r
            val period = circumference / 28f
            drawCircle(
                color = colors.tertiary,
                radius = r,
                center = c,
                style = Stroke(
                    width = stroke,
                    cap = StrokeCap.Round,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(period * 0.55f, period * 0.45f), -spin * period * 4f),
                ),
            )
        }

        Kind.Error -> {
            drawCircle(colors.error, r, c, style = Stroke(stroke))
            drawCircle(colors.error.copy(alpha = 0.5f), r - stroke * 2.5f, c, style = Stroke(stroke / 3))
        }
    }
}
