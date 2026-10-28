package moe.desupon.feature.settings.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/** Outline icons missing from the core icon set, on a 24-unit grid. */
internal object SettingsGlyphs {
    val Moon by lazy {
        glyph("Moon") {
            stroke {
                moveTo(20f, 14.5f)
                arcTo(8f, 8f, 0f, isMoreThanHalf = true, isPositiveArc = true, x1 = 9.5f, y1 = 4f)
                arcTo(6.5f, 6.5f, 0f, isMoreThanHalf = false, isPositiveArc = false, x1 = 20f, y1 = 14.5f)
                close()
            }
        }
    }

    val Sun by lazy {
        glyph("Sun") {
            stroke {
                circle(12f, 12f, 4f)
                moveTo(12f, 2.5f); verticalLineTo(4.5f)
                moveTo(12f, 19.5f); verticalLineTo(21.5f)
                moveTo(2.5f, 12f); horizontalLineTo(4.5f)
                moveTo(19.5f, 12f); horizontalLineTo(21.5f)
                moveTo(5.3f, 5.3f); lineTo(6.7f, 6.7f)
                moveTo(17.3f, 17.3f); lineTo(18.7f, 18.7f)
                moveTo(5.3f, 18.7f); lineTo(6.7f, 17.3f)
                moveTo(17.3f, 6.7f); lineTo(18.7f, 5.3f)
            }
        }
    }

    val Monitor by lazy {
        glyph("Monitor") {
            stroke {
                moveTo(4f, 4f); horizontalLineTo(20f); verticalLineTo(16f); horizontalLineTo(4f); close()
                moveTo(12f, 16f); verticalLineTo(20f)
                moveTo(8f, 20f); horizontalLineTo(16f)
            }
        }
    }

    val Contrast by lazy {
        glyph("Contrast") {
            stroke { circle(12f, 12f, 9f) }
            fill {
                moveTo(12f, 3f)
                arcTo(9f, 9f, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = 12f, y1 = 21f)
                close()
            }
        }
    }

    val Palette by lazy {
        glyph("Palette") {
            stroke {
                moveTo(12f, 3f)
                arcTo(9f, 9f, 0f, isMoreThanHalf = false, isPositiveArc = false, x1 = 12f, y1 = 21f)
                curveTo(13.5f, 21f, 14f, 20f, 13.3f, 18.8f)
                curveTo(12.6f, 17.6f, 13.3f, 16f, 15f, 16f)
                horizontalLineTo(17f)
                arcTo(4f, 4f, 0f, isMoreThanHalf = false, isPositiveArc = false, x1 = 21f, y1 = 12f)
                arcTo(9f, 9f, 0f, isMoreThanHalf = false, isPositiveArc = false, x1 = 12f, y1 = 3f)
                close()
            }
            fill {
                circle(7.5f, 11.5f, 1.3f)
                circle(10f, 7.5f, 1.3f)
                circle(15f, 7.8f, 1.3f)
            }
        }
    }

    val Split by lazy {
        glyph("Split") {
            stroke {
                moveTo(9f, 4f); horizontalLineTo(5f); verticalLineTo(20f); horizontalLineTo(9f)
                moveTo(15f, 4f); horizontalLineTo(19f); verticalLineTo(20f); horizontalLineTo(15f)
                moveTo(12f, 3f); verticalLineTo(21f)
            }
        }
    }

    val Lan by lazy {
        glyph("Lan") {
            stroke {
                moveTo(9.5f, 3f); horizontalLineTo(14.5f); verticalLineTo(8f); horizontalLineTo(9.5f); close()
                moveTo(12f, 8f); verticalLineTo(12f)
                moveTo(5f, 16f); verticalLineTo(12f); horizontalLineTo(19f); verticalLineTo(16f)
                moveTo(2.5f, 16f); horizontalLineTo(7.5f); verticalLineTo(21f); horizontalLineTo(2.5f); close()
                moveTo(16.5f, 16f); horizontalLineTo(21.5f); verticalLineTo(21f); horizontalLineTo(16.5f); close()
            }
        }
    }

    val Ipv4 by lazy {
        glyph("Ipv4") {
            stroke {
                moveTo(3f, 5f); horizontalLineTo(21f); verticalLineTo(19f); horizontalLineTo(3f); close()
                moveTo(13.5f, 8.5f); lineTo(9f, 14f); horizontalLineTo(15f)
                moveTo(13.5f, 8.5f); verticalLineTo(16f)
            }
        }
    }

    val Globe by lazy {
        glyph("Globe") {
            stroke {
                circle(12f, 12f, 9f)
                moveTo(12f, 3f)
                arcTo(4f, 9f, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = 12f, y1 = 21f)
                arcTo(4f, 9f, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = 12f, y1 = 3f)
                moveTo(3f, 12f); horizontalLineTo(21f)
            }
        }
    }

    val Tag by lazy {
        glyph("Tag") {
            stroke {
                moveTo(3f, 12.5f); lineTo(11.5f, 4f); horizontalLineTo(20f); verticalLineTo(12.5f)
                lineTo(11.5f, 21f); close()
            }
            fill { circle(15.8f, 8.2f, 1.4f) }
        }
    }

    val Code by lazy {
        glyph("Code") {
            stroke {
                moveTo(8f, 7f); lineTo(3f, 12f); lineTo(8f, 17f)
                moveTo(16f, 7f); lineTo(21f, 12f); lineTo(16f, 17f)
            }
        }
    }

    val Shield by lazy {
        glyph("Shield") {
            stroke {
                moveTo(12f, 3f); lineTo(20f, 6f); verticalLineTo(11f)
                curveTo(20f, 16f, 16.5f, 19.5f, 12f, 21f)
                curveTo(7.5f, 19.5f, 4f, 16f, 4f, 11f)
                verticalLineTo(6f); close()
                moveTo(8.5f, 12f); lineTo(11f, 14.5f); lineTo(15.5f, 9.5f)
            }
        }
    }

    val App by lazy {
        glyph("App") {
            stroke {
                moveTo(7f, 4f); horizontalLineTo(17f)
                arcTo(3f, 3f, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = 20f, y1 = 7f)
                verticalLineTo(17f)
                arcTo(3f, 3f, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = 17f, y1 = 20f)
                horizontalLineTo(7f)
                arcTo(3f, 3f, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = 4f, y1 = 17f)
                verticalLineTo(7f)
                arcTo(3f, 3f, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = 7f, y1 = 4f)
                close()
            }
        }
    }

    val Database by lazy {
        glyph("Database") {
            stroke {
                moveTo(4f, 6f)
                arcTo(8f, 3f, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = 20f, y1 = 6f)
                arcTo(8f, 3f, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = 4f, y1 = 6f)
                close()
                moveTo(4f, 6f); verticalLineTo(18f)
                arcTo(8f, 3f, 0f, isMoreThanHalf = false, isPositiveArc = false, x1 = 20f, y1 = 18f)
                verticalLineTo(6f)
                moveTo(4f, 12f)
                arcTo(8f, 3f, 0f, isMoreThanHalf = false, isPositiveArc = false, x1 = 20f, y1 = 12f)
            }
        }
    }

    val Clock by lazy {
        glyph("Clock") {
            stroke {
                circle(12f, 12f, 9f)
                moveTo(12f, 7f); verticalLineTo(12f); lineTo(15.5f, 14f)
            }
        }
    }

    val Wifi by lazy {
        glyph("Wifi") {
            stroke {
                moveTo(2.5f, 9f)
                arcTo(13.5f, 13.5f, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = 21.5f, y1 = 9f)
                moveTo(5.5f, 12.5f)
                arcTo(9f, 9f, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = 18.5f, y1 = 12.5f)
                moveTo(8.5f, 16f)
                arcTo(5f, 5f, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = 15.5f, y1 = 16f)
            }
            fill { circle(12f, 19.5f, 1.3f) }
        }
    }

    val Download by lazy {
        glyph("Download") {
            stroke {
                moveTo(12f, 4f); verticalLineTo(15f)
                moveTo(7.5f, 10.5f); lineTo(12f, 15f); lineTo(16.5f, 10.5f)
                moveTo(5f, 19.5f); horizontalLineTo(19f)
            }
        }
    }

    val Logs by lazy {
        glyph("Logs") {
            stroke {
                moveTo(4f, 4f); horizontalLineTo(20f); verticalLineTo(20f); horizontalLineTo(4f); close()
                moveTo(7.5f, 9f); lineTo(10f, 11.5f); lineTo(7.5f, 14f)
                moveTo(12f, 15f); horizontalLineTo(16.5f)
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
            strokeLineWidth = 1.7f,
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
