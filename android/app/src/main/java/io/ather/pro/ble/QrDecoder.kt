package io.ather.pro.ble

import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.LuminanceSource
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader

/**
 * QR decoding over raw camera luma frames. Tries the frame as-is and then inverted:
 * the scooter's dashboard renders the pairing QR light-on-dark, which standard
 * readers reject. Rotation is deliberately not applied — QR detection is
 * orientation-invariant, and the YUV source does not support rotating anyway.
 */
object QrDecoder {

    private val HINTS = mapOf(DecodeHintType.TRY_HARDER to true)

    fun decode(data: ByteArray, width: Int, height: Int): String? {
        val reader = QRCodeReader()
        return tryDecode(reader, data, width, height, inverted = false)
            ?: tryDecode(reader, data, width, height, inverted = true)
    }

    private fun tryDecode(
        reader: QRCodeReader,
        data: ByteArray,
        width: Int,
        height: Int,
        inverted: Boolean
    ): String? = try {
        var source: LuminanceSource = PlanarYUVLuminanceSource(data, width, height, 0, 0, width, height, false)
        if (inverted) source = source.invert()
        reader.decode(BinaryBitmap(HybridBinarizer(source)), HINTS).text
    } catch (_: Exception) {
        null
    }
}
