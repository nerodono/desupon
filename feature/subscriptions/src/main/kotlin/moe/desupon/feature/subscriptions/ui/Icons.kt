package moe.desupon.feature.subscriptions.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/** The few stroke icons this screen needs, so it does not pull in the icon library. */
internal object SubsIcons {
    val Add: ImageVector by lazy {
        stroke("Add") {
            moveTo(12f, 5f); verticalLineTo(19f)
            moveTo(5f, 12f); horizontalLineTo(19f)
        }
    }

    val Back: ImageVector by lazy {
        stroke("Back") {
            moveTo(19f, 12f); horizontalLineTo(5f)
            moveTo(11f, 6f); lineTo(5f, 12f); lineTo(11f, 18f)
        }
    }

    val Close: ImageVector by lazy {
        stroke("Close") {
            moveTo(6f, 6f); lineTo(18f, 18f)
            moveTo(18f, 6f); lineTo(6f, 18f)
        }
    }

    val DragHandle: ImageVector by lazy {
        stroke("DragHandle") {
            moveTo(5f, 7f); horizontalLineTo(19f)
            moveTo(5f, 12f); horizontalLineTo(19f)
            moveTo(5f, 17f); horizontalLineTo(19f)
        }
    }

    val Eye: ImageVector by lazy {
        stroke("Eye") {
            moveTo(2f, 12f)
            curveTo(4.5f, 7f, 8f, 5f, 12f, 5f)
            curveTo(16f, 5f, 19.5f, 7f, 22f, 12f)
            curveTo(19.5f, 17f, 16f, 19f, 12f, 19f)
            curveTo(8f, 19f, 4.5f, 17f, 2f, 12f)
            close()
            moveTo(15f, 12f)
            arcToRelative(3f, 3f, 0f, isMoreThanHalf = true, isPositiveArc = true, dx1 = -6f, dy1 = 0f)
            arcToRelative(3f, 3f, 0f, isMoreThanHalf = true, isPositiveArc = true, dx1 = 6f, dy1 = 0f)
        }
    }

    val EyeOff: ImageVector by lazy {
        stroke("EyeOff") {
            moveTo(2f, 12f)
            curveTo(4.5f, 7f, 8f, 5f, 12f, 5f)
            curveTo(16f, 5f, 19.5f, 7f, 22f, 12f)
            curveTo(19.5f, 17f, 16f, 19f, 12f, 19f)
            curveTo(8f, 19f, 4.5f, 17f, 2f, 12f)
            close()
            moveTo(4f, 4f); lineTo(20f, 20f)
        }
    }

    val More: ImageVector by lazy {
        ImageVector.Builder("More", 24.dp, 24.dp, 24f, 24f).apply {
            for (y in listOf(5f, 12f, 19f)) {
                path(fill = SolidColor(Color.Black)) {
                    moveTo(10.2f, y)
                    arcToRelative(1.8f, 1.8f, 0f, isMoreThanHalf = true, isPositiveArc = true, dx1 = 3.6f, dy1 = 0f)
                    arcToRelative(1.8f, 1.8f, 0f, isMoreThanHalf = true, isPositiveArc = true, dx1 = -3.6f, dy1 = 0f)
                    close()
                }
            }
        }.build()
    }

    private fun stroke(name: String, block: PathBuilder.() -> Unit): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
                pathBuilder = block,
            )
        }.build()
}
