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

    /** A provider feed: what a subscription is. */
    val Feed: ImageVector by lazy {
        stroke("Feed") {
            moveTo(5f, 11f)
            arcToRelative(8f, 8f, 0f, isMoreThanHalf = false, isPositiveArc = true, dx1 = 8f, dy1 = 8f)
            moveTo(5f, 5f)
            arcToRelative(14f, 14f, 0f, isMoreThanHalf = false, isPositiveArc = true, dx1 = 14f, dy1 = 14f)
            moveTo(6f, 18f); lineTo(6f, 18.01f)
        }
    }

    /** One server's key: what a config is. */
    val Key: ImageVector by lazy {
        stroke("Key") {
            moveTo(12f, 15f)
            arcToRelative(4f, 4f, 0f, isMoreThanHalf = true, isPositiveArc = true, dx1 = -8f, dy1 = 0f)
            arcToRelative(4f, 4f, 0f, isMoreThanHalf = true, isPositiveArc = true, dx1 = 8f, dy1 = 0f)
            moveTo(10.9f, 12.1f); lineTo(19f, 4f)
            moveTo(16f, 7f); lineTo(18.5f, 9.5f)
            moveTo(13.5f, 9.5f); lineTo(15.5f, 11.5f)
        }
    }

    /** A pasted list of servers. */
    val Lines: ImageVector by lazy {
        stroke("Lines") {
            moveTo(9f, 6f); horizontalLineTo(20f)
            moveTo(9f, 12f); horizontalLineTo(20f)
            moveTo(9f, 18f); horizontalLineTo(20f)
            moveTo(4.5f, 6f); lineTo(4.5f, 6.01f)
            moveTo(4.5f, 12f); lineTo(4.5f, 12.01f)
            moveTo(4.5f, 18f); lineTo(4.5f, 18.01f)
        }
    }

    val Delete: ImageVector by lazy {
        stroke("Delete") {
            moveTo(4f, 7f); horizontalLineTo(20f)
            moveTo(9f, 7f); verticalLineTo(4f); horizontalLineTo(15f); verticalLineTo(7f)
            moveTo(6f, 7f); lineTo(7f, 20f); horizontalLineTo(17f); lineTo(18f, 7f)
        }
    }

    /** Start picking items. */
    val Select: ImageVector by lazy {
        stroke("Select") {
            moveTo(4f, 4f); horizontalLineTo(20f); verticalLineTo(20f); horizontalLineTo(4f); close()
            moveTo(8f, 12f); lineTo(11f, 15f); lineTo(16f, 9f)
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
