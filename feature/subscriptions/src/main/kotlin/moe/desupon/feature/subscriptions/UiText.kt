package moe.desupon.feature.subscriptions

import android.content.Context
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource

/** A string resource with its arguments, resolved at render time; arguments may themselves be [UiText]. */
internal sealed interface UiText {
    data class Res(@StringRes val id: Int, val args: List<Any> = emptyList()) : UiText

    data class Plural(@PluralsRes val id: Int, val count: Int) : UiText
}

internal fun UiText.resolve(context: Context): String = when (this) {
    is UiText.Res -> context.getString(id, *args.map { if (it is UiText) it.resolve(context) else it }.toTypedArray())
    is UiText.Plural -> context.resources.getQuantityString(id, count, count)
}

@Composable
@ReadOnlyComposable
internal fun UiText.resolve(): String = when (this) {
    is UiText.Res -> {
        val context = LocalContext.current
        stringResource(id, *args.map { if (it is UiText) it.resolve(context) else it }.toTypedArray())
    }
    is UiText.Plural -> pluralStringResource(id, count, count)
}
