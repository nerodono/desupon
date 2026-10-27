package moe.desupon.feature.home

import android.animation.ValueAnimator
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import moe.desupon.ui.theme.ConnectionButton
import moe.desupon.ui.theme.ConnectionButtonState
import moe.desupon.ui.theme.DesuponGlyphs
import moe.desupon.ui.theme.DesuponTheme
import org.koin.compose.viewmodel.koinViewModel
import moe.desupon.ui.theme.R as ThemeR

internal object SimpleTags {
    const val LOGO = "simple_logo"
    const val BUTTON = "simple_button"
    const val STATUS = "simple_status"
    const val EXPIRED = "simple_expired"
    const val ADVANCED = "simple_advanced"
    const val ARROW = "simple_arrow"
}

/** Simple mode's only screen; [onExit] runs once the user confirms switching to the full UI. Window insets are the caller's concern. */
@Composable
fun SimpleHomeScreen(onExit: () -> Unit, modifier: Modifier = Modifier) {
    val viewModel = koinViewModel<SimpleHomeViewModel>()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val deniedText = stringResource(R.string.simple_vpn_denied)
    val unavailableText = stringResource(R.string.simple_vpn_unavailable)
    var confirmExit by rememberSaveable { mutableStateOf(false) }

    val connect = rememberVpnConsent(
        onGranted = viewModel::connect,
        onDenied = { scope.launch { snackbar.showSnackbar(deniedText) } },
        onUnavailable = { scope.launch { snackbar.showSnackbar(unavailableText) } },
    )

    Box(modifier) {
        state?.let {
            SimpleContent(
                state = it,
                onConnect = connect,
                onDisconnect = viewModel::disconnect,
                onAdvanced = { confirmExit = true },
                modifier = Modifier.fillMaxSize(),
            )
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }

    if (confirmExit) ExitDialog(onStay = { confirmExit = false }, onLeave = {
        confirmExit = false
        onExit()
    })
}

/** Staying is the prominent button and what back or a tap outside does; leaving takes the plain text button. */
@Composable
private fun ExitDialog(onStay: () -> Unit, onLeave: () -> Unit) {
    AlertDialog(
        onDismissRequest = onStay,
        title = { Text(stringResource(R.string.simple_exit_title)) },
        text = { Text(stringResource(R.string.simple_exit_text)) },
        confirmButton = { Button(onClick = onStay) { Text(stringResource(R.string.simple_exit_stay)) } },
        dismissButton = { TextButton(onClick = onLeave) { Text(stringResource(R.string.simple_exit_leave)) } },
    )
}

/** The button takes whatever height the texts leave, so large fonts shrink it instead of pushing anything off screen. */
@Composable
internal fun SimpleContent(
    state: SimpleUiState,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onAdvanced: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.padding(horizontal = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(
            imageVector = DesuponGlyphs.Sprout,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 16.dp).size(48.dp).testTag(SimpleTags.LOGO),
        )
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            ConnectionButton(
                state = state.button,
                onClick = if (state.action == ButtonAction.CONNECT) onConnect else onDisconnect,
                enabled = state.buttonEnabled,
                size = (minOf(maxWidth, maxHeight) * BUTTON_FILL).coerceAtMost(MAX_BUTTON),
                showCaption = false,
                modifier = Modifier.testTag(SimpleTags.BUTTON),
            )
        }
        // Users tap the hint instead of the button, so while off the hint and its arrow connect too.
        val offHint = state.status == SimpleStatus.TAP_TO_CONNECT
        val connectLabel = stringResource(ThemeR.string.desupon_cb_action_connect)
        if (offHint) HintArrow(onClick = onConnect, modifier = Modifier.padding(vertical = 4.dp).testTag(SimpleTags.ARROW))
        Text(
            text = statusText(state.status),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .then(if (offHint) Modifier.clickable(onClickLabel = connectLabel, onClick = onConnect) else Modifier)
                .testTag(SimpleTags.STATUS),
        )
        if (state.expired) {
            Spacer(Modifier.height(12.dp))
            Column(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}.testTag(SimpleTags.EXPIRED)) {
                Text(
                    text = stringResource(R.string.home_card_subscription),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = stringResource(R.string.home_expired),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        Spacer(Modifier.height(32.dp))
        TextButton(
            onClick = onAdvanced,
            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant),
            modifier = Modifier.padding(bottom = 8.dp).testTag(SimpleTags.ADVANCED),
        ) {
            Text(stringResource(R.string.simple_advanced))
        }
    }
}

/** Points up from the hint at the button with a slow bounce toward it; still when system animations are off. */
@Composable
private fun HintArrow(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val animate = remember { ValueAnimator.areAnimatorsEnabled() }
    val lift = if (animate) {
        rememberInfiniteTransition(label = "hint arrow").animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(ARROW_BOUNCE_MS, easing = FastOutSlowInEasing), RepeatMode.Reverse),
            label = "lift",
        )
    } else {
        null
    }
    val color = MaterialTheme.colorScheme.primary
    val scale = LocalDensity.current.fontScale.coerceIn(1f, ARROW_MAX_SCALE)
    Canvas(
        modifier
            .size(width = ARROW_WIDTH * scale, height = ARROW_HEIGHT * scale)
            .clickable(onClickLabel = stringResource(ThemeR.string.desupon_cb_action_connect), onClick = onClick),
    ) {
        val stroke = size.width * 0.16f
        val bounce = ARROW_BOUNCE.toPx() * scale
        val tipY = bounce + stroke / 2
        val head = size.width / 2 - stroke / 2
        translate(top = -bounce * (lift?.value ?: 0f)) {
            val tip = Offset(size.width / 2, tipY)
            drawLine(color, tip, Offset(tip.x, size.height - stroke / 2), stroke, StrokeCap.Round)
            val arrowHead = Path().apply {
                moveTo(tip.x - head, tip.y + head)
                lineTo(tip.x, tip.y)
                lineTo(tip.x + head, tip.y + head)
            }
            drawPath(arrowHead, color, style = Stroke(stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}

private val ARROW_WIDTH = 36.dp
private val ARROW_HEIGHT = 48.dp
private val ARROW_BOUNCE = 6.dp
private const val ARROW_MAX_SCALE = 1.5f
private const val ARROW_BOUNCE_MS = 900

private const val BUTTON_FILL = 0.9f
private val MAX_BUTTON = 320.dp

@Composable
private fun statusText(status: SimpleStatus): String = stringResource(
    when (status) {
        SimpleStatus.TAP_TO_CONNECT -> R.string.simple_tap_to_connect
        SimpleStatus.NO_SERVERS -> R.string.simple_no_servers
        SimpleStatus.CHECKING -> ThemeR.string.desupon_cb_label_checking
        SimpleStatus.CONNECTING -> ThemeR.string.desupon_cb_label_connecting
        SimpleStatus.SWITCHING -> ThemeR.string.desupon_cb_label_switching
        SimpleStatus.CONNECTED -> ThemeR.string.desupon_cb_label_connected
        SimpleStatus.RETRYING -> R.string.simple_retrying
    },
)

@Preview
@Composable
private fun SimpleRetryingPreview() {
    DesuponTheme {
        Surface(color = MaterialTheme.colorScheme.background) {
            SimpleContent(
                state = SimpleUiState(ConnectionButtonState.Error, SimpleStatus.RETRYING, ButtonAction.DISCONNECT, expired = true),
                onConnect = {},
                onDisconnect = {},
                onAdvanced = {},
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
