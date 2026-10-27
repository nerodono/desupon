package moe.desupon.feature.subscriptions.qr

import android.graphics.Bitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

internal object QrEncoder {
    private val hints = mapOf(
        EncodeHintType.CHARACTER_SET to "UTF-8",
        EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
        EncodeHintType.MARGIN to 2,
    )

    fun matrix(text: String, size: Int): BitMatrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints)

    /** Packed ARGB, dark modules black on white regardless of theme so scanners read it. */
    fun argb(matrix: BitMatrix): IntArray = IntArray(matrix.width * matrix.height) { i ->
        if (matrix[i % matrix.width, i / matrix.width]) DARK else LIGHT
    }

    fun bitmap(text: String, size: Int): Bitmap {
        val m = matrix(text, size)
        return Bitmap.createBitmap(argb(m), m.width, m.height, Bitmap.Config.ARGB_8888)
    }

    private const val DARK = 0xFF000000.toInt()
    private const val LIGHT = 0xFFFFFFFF.toInt()
}
