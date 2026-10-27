package moe.desupon.feature.home

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle

internal const val PART_SEPARATOR = " · "
private const val ARROW = " → "

/** «[prefix] A → B», plus «· [rest]» when given; the active step and [rest] carry [highlight]. */
internal fun chainText(prefix: String, titles: List<String>, active: Int?, rest: String?, highlight: SpanStyle): AnnotatedString =
    buildAnnotatedString {
        append(prefix)
        append(' ')
        titles.forEachIndexed { i, title ->
            if (i > 0) append(ARROW)
            if (i == active) withStyle(highlight) { append(title) } else append(title)
        }
        if (rest != null) {
            append(PART_SEPARATOR)
            withStyle(highlight) { append(rest) }
        }
    }

@Composable
internal fun chainText(line: ChainLine): AnnotatedString {
    val titles = line.steps.map { subscriptionTitle(it) }
    val prefix = stringResource(R.string.home_chain_first)
    val rest = if (line.rest) stringResource(R.string.home_chain_rest) else null
    val primary = MaterialTheme.colorScheme.primary
    return remember(prefix, titles, line.active, rest, primary) {
        chainText(prefix, titles, line.active, rest, SpanStyle(color = primary, fontWeight = FontWeight.SemiBold))
    }
}
