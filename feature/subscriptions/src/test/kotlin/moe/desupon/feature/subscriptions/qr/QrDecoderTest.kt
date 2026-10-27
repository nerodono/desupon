package moe.desupon.feature.subscriptions.qr

import android.graphics.Bitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertNull

@RunWith(RobolectricTestRunner::class)
class QrDecoderTest {
    private val text = "https://sub.example.invalid/s/0000synthetic0000\nvless://00000000-0000-0000-0000-000000000000@203.0.113.1:443#Тест"

    private fun matrix(size: Int = 360): BitMatrix =
        QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.CHARACTER_SET to "UTF-8"))

    private fun argb(m: BitMatrix, invert: Boolean = false): IntArray = IntArray(m.width * m.height) { i ->
        val dark = m[i % m.width, i / m.width] != invert
        if (dark) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
    }

    @Test
    fun `decodes argb pixels`() {
        val m = matrix()
        assertEquals(text, QrDecoder.decodeArgb(argb(m), m.width, m.height))
    }

    @Test
    fun `decodes inverted code`() {
        val m = matrix()
        assertEquals(text, QrDecoder.decodeArgb(argb(m, invert = true), m.width, m.height))
    }

    @Test
    fun `decodes camera luminance plane with row padding`() {
        val m = matrix()
        val stride = m.width + 64
        val y = ByteArray(stride * m.height) { 0x7F }
        for (row in 0 until m.height) for (col in 0 until m.width) {
            y[row * stride + col] = if (m[col, row]) 0 else 0xFF.toByte()
        }
        assertEquals(text, QrDecoder.decodeLuminance(y, stride, m.width, m.height))
    }

    @Test
    fun `decodes generated bitmap`() {
        val m = matrix()
        val bitmap = Bitmap.createBitmap(argb(m), m.width, m.height, Bitmap.Config.ARGB_8888)
        assertEquals(text, QrDecoder.decodeBitmap(bitmap))
    }

    @Test
    fun `blank image yields nothing`() {
        assertNull(QrDecoder.decodeArgb(IntArray(200 * 200) { 0xFFFFFFFF.toInt() }, 200, 200))
    }
}
