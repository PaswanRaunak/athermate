package io.ather.pro.ble

import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import org.junit.Assert.assertEquals
import org.junit.Test

class QrDecoderTest {

    private fun renderQr(text: String, side: Int = 220, inverted: Boolean): ByteArray {
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, side, side)
        return ByteArray(side * side) { i ->
            val dark = matrix.get(i % side, i / side) xor inverted
            if (dark) 0 else 0xFF.toByte()
        }
    }

    @Test
    fun `decodes standard dark-on-light qr`() {
        assertEquals("AA:BB:CC:DD:EE:FF", QrDecoder.decode(renderQr("AA:BB:CC:DD:EE:FF", inverted = false), 220, 220))
    }

    @Test
    fun `decodes inverted light-on-dark qr like the scooter dashboard`() {
        assertEquals("AA:BB:CC:DD:EE:FF", QrDecoder.decode(renderQr("AA:BB:CC:DD:EE:FF", inverted = true), 220, 220))
    }

    @Test
    fun `returns null for a blank frame`() {
        assertEquals(null, QrDecoder.decode(ByteArray(220 * 220), 220, 220))
    }
}
