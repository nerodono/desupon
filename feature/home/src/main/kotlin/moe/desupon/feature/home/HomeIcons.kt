package moe.desupon.feature.home

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/** Card icons on a 24-unit grid, stroked so `Icon(tint = …)` recolors them. */
internal object HomeIcons {
    val Traffic: ImageVector by lazy {
        outline("Traffic") {
            moveTo(8f, 19f); verticalLineTo(5f); moveTo(4f, 9f); lineTo(8f, 5f); lineTo(12f, 9f)
            moveTo(16f, 5f); verticalLineTo(19f); moveTo(12f, 15f); lineTo(16f, 19f); lineTo(20f, 15f)
        }
    }

    val Calendar: ImageVector by lazy {
        outline("Calendar") {
            moveTo(6f, 5f); horizontalLineTo(18f); quadTo(20f, 5f, 20f, 7f); verticalLineTo(18f)
            quadTo(20f, 20f, 18f, 20f); horizontalLineTo(6f); quadTo(4f, 20f, 4f, 18f); verticalLineTo(7f)
            quadTo(4f, 5f, 6f, 5f); close()
            moveTo(4f, 10f); horizontalLineTo(20f)
            moveTo(8f, 3f); verticalLineTo(7f); moveTo(16f, 3f); verticalLineTo(7f)
        }
    }

    val Bolt: ImageVector by lazy {
        outline("Bolt") {
            moveTo(13f, 3f); lineTo(5f, 14f); horizontalLineTo(12f); lineTo(11f, 21f); lineTo(19f, 10f); horizontalLineTo(12f); close()
        }
    }

    val Refresh: ImageVector by lazy {
        outline("Refresh") {
            moveTo(19.5f, 12f); arcTo(7.5f, 7.5f, 0f, true, true, 17.3f, 6.7f)
            moveTo(18f, 3f); verticalLineTo(7.5f); horizontalLineTo(13.5f)
        }
    }

    /** Points right; rotate 90° for "expanded". */
    val Chevron: ImageVector by lazy {
        outline("Chevron") { moveTo(9f, 5f); lineTo(16f, 12f); lineTo(9f, 19f) }
    }

    val Plus: ImageVector by lazy {
        outline("Plus") { moveTo(12f, 5f); verticalLineTo(19f); moveTo(5f, 12f); horizontalLineTo(19f) }
    }

    val Cross: ImageVector by lazy {
        outline("Cross") { moveTo(6f, 6f); lineTo(18f, 18f); moveTo(18f, 6f); lineTo(6f, 18f) }
    }

    private fun outline(name: String, block: PathBuilder.() -> Unit): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).path(
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.8f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
            pathBuilder = block,
        ).build()
}
