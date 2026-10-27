package moe.desupon.ui.theme

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.res.stringResource

/**
 * Playful copy toggle. Settings, logs, JSON views and content descriptions must ignore it.
 * A resource qualifier can't express this: 4-char locale variants must start with a digit.
 */
val LocalDesuMode = staticCompositionLocalOf { false }

/** A neutral string and its desu-mode counterpart. */
@Immutable
data class DesuText(val neutral: String, val desu: String) {
    fun pick(desuMode: Boolean): String = if (desuMode) desu else neutral
}

@Composable
@ReadOnlyComposable
fun desuText(neutral: String, desu: String): String = if (LocalDesuMode.current) desu else neutral

@Composable
@ReadOnlyComposable
fun desuText(text: DesuText): String = text.pick(LocalDesuMode.current)

@Composable
@ReadOnlyComposable
fun desuStringResource(@StringRes neutral: Int, @StringRes desu: Int, vararg formatArgs: Any): String =
    stringResource(if (LocalDesuMode.current) desu else neutral, *formatArgs)
