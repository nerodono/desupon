package moe.desupon.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/** Brand glyphs on a 24-unit grid, single-color so `Icon(tint = …)` recolors them. */
object DesuponGlyphs {
    /** Spout tip in the 24-unit grid, for effects anchored to it (e.g. falling drops). */
    const val SpoutTipX = 20.3f
    const val SpoutTipY = 8.7f

    val WateringCan: ImageVector by lazy {
        glyph("WateringCan") {
            fill {
                moveTo(5f, 11f); horizontalLineTo(15f); lineTo(15.8f, 18.6f)
                quadTo(15.95f, 20f, 14.5f, 20f); horizontalLineTo(5.5f)
                quadTo(4.05f, 20f, 4.2f, 18.6f); close()
                moveTo(14.9f, 14.6f); lineTo(19.4f, 8.4f); lineTo(20.8f, 9.4f); lineTo(15.5f, 17.2f); close()
                circle(SpoutTipX, SpoutTipY, 1.4f)
            }
            stroke {
                moveTo(6.6f, 11f); curveTo(6.6f, 6.4f, 13.4f, 6.4f, 13.4f, 11f)
            }
        }
    }

    val Sprout: ImageVector by lazy {
        glyph("Sprout") {
            fill {
                moveTo(12f, 13f); curveTo(9f, 13.5f, 5.5f, 12f, 4.5f, 7.5f)
                curveTo(8.5f, 7f, 11.5f, 9f, 12f, 13f); close()
                moveTo(12f, 11.5f); curveTo(12.5f, 7f, 15.5f, 4f, 20f, 4f)
                curveTo(20f, 8.5f, 16.5f, 11.5f, 12f, 11.5f); close()
            }
            stroke {
                moveTo(12f, 20.5f); verticalLineTo(11.5f)
                moveTo(7f, 20.5f); horizontalLineTo(17f)
            }
        }
    }

    val WiltedLeaf: ImageVector by lazy {
        glyph("WiltedLeaf") {
            fill {
                moveTo(15f, 8.2f); curveTo(18.8f, 9f, 20.6f, 12.8f, 19.4f, 17.8f)
                curveTo(16f, 16.2f, 14.2f, 12.4f, 15f, 8.2f); close()
                moveTo(8.4f, 14.2f); curveTo(6f, 14.6f, 4.4f, 16.4f, 4.2f, 19f)
                curveTo(6.6f, 18.4f, 8.2f, 16.6f, 8.4f, 14.2f); close()
            }
            stroke {
                moveTo(8f, 20.5f); curveTo(8f, 14.5f, 10f, 9.5f, 15f, 8.2f)
                moveTo(3f, 20.5f); horizontalLineTo(13f)
            }
        }
    }

    val Drop: ImageVector by lazy {
        glyph("Drop") {
            fill {
                moveTo(12f, 3f); curveTo(12f, 3f, 6f, 10f, 6f, 14.5f)
                arcTo(6f, 6f, 0f, isMoreThanHalf = false, isPositiveArc = false, x1 = 18f, y1 = 14.5f)
                curveTo(18f, 10f, 12f, 3f, 12f, 3f); close()
            }
        }
    }
}

private class GlyphScope(val builder: ImageVector.Builder) {
    fun fill(block: PathBuilder.() -> Unit) {
        builder.path(fill = SolidColor(Color.Black), pathBuilder = block)
    }

    fun stroke(block: PathBuilder.() -> Unit) {
        builder.path(
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.6f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
            pathBuilder = block,
        )
    }
}

private fun glyph(name: String, block: GlyphScope.() -> Unit): ImageVector =
    ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).also { GlyphScope(it).block() }.build()

private fun PathBuilder.circle(cx: Float, cy: Float, r: Float) {
    moveTo(cx - r, cy)
    arcToRelative(r, r, 0f, isMoreThanHalf = true, isPositiveArc = true, dx1 = 2 * r, dy1 = 0f)
    arcToRelative(r, r, 0f, isMoreThanHalf = true, isPositiveArc = true, dx1 = -2 * r, dy1 = 0f)
    close()
}
