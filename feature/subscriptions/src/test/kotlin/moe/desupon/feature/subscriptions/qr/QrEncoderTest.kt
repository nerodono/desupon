package moe.desupon.feature.subscriptions.qr

import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
class QrEncoderTest {
    private val url = "https://sub.example.invalid/api/sub/0000synthetic0000?flag=Тест"

    @Test
    fun `encoded pixels decode back`() {
        val m = QrEncoder.matrix(url, 400)
        assertEquals(url, QrDecoder.decodeArgb(QrEncoder.argb(m), m.width, m.height))
    }

    @Test
    fun `encoded bitmap decodes back`() {
        assertEquals(url, QrDecoder.decodeBitmap(QrEncoder.bitmap(url, 400)))
    }

    @Test
    fun `long link still round-trips`() {
        val long = "https://sub.example.invalid/" + "a".repeat(600)
        val m = QrEncoder.matrix(long, 720)
        assertEquals(long, QrDecoder.decodeArgb(QrEncoder.argb(m), m.width, m.height))
    }
}
