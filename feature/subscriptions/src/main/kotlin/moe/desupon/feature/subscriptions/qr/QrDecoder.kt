package moe.desupon.feature.subscriptions.qr

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.LuminanceSource
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader

/** QR decoding on zxing alone, so the app needs no Play services. */
internal object QrDecoder {
    private const val MAX_IMAGE_SIDE = 2048

    private val fastHints = mapOf(
        DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
        DecodeHintType.CHARACTER_SET to "UTF-8",
    )
    private val thoroughHints = fastHints + (DecodeHintType.TRY_HARDER to true)

    /** One camera frame's luminance plane; tried as-is and inverted for light-on-dark codes. */
    fun decodeLuminance(y: ByteArray, rowStride: Int, width: Int, height: Int): String? =
        decode(PlanarYUVLuminanceSource(y, rowStride, height, 0, 0, width, height, false), thorough = false)

    /** Packed ARGB pixels, as from [Bitmap.getPixels]. */
    fun decodeArgb(pixels: IntArray, width: Int, height: Int): String? =
        decode(RGBLuminanceSource(width, height, pixels), thorough = true)

    fun decodeBitmap(bitmap: Bitmap): String? {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return decodeArgb(pixels, bitmap.width, bitmap.height)
    }

    /** Decodes a picked image, downsampled so huge photos fit in memory; blocking. */
    fun decodeImage(resolver: ContentResolver, uri: Uri): String? {
        val source = ImageDecoder.createSource(resolver, uri)
        val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val side = maxOf(info.size.width, info.size.height)
            var sample = 1
            while (side / sample > MAX_IMAGE_SIDE) sample *= 2
            decoder.setTargetSampleSize(sample)
        }
        return try {
            decodeBitmap(bitmap)
        } finally {
            bitmap.recycle()
        }
    }

    private fun decode(source: LuminanceSource, thorough: Boolean): String? {
        val hints = if (thorough) thoroughHints else fastHints
        return tryDecode(source, hints) ?: tryDecode(source.invert(), hints)
    }

    private fun tryDecode(source: LuminanceSource, hints: Map<DecodeHintType, Any>): String? =
        try {
            QRCodeReader().decode(BinaryBitmap(HybridBinarizer(source)), hints).text?.takeIf { it.isNotBlank() }
        } catch (_: ReaderException) {
            null
        }
}
