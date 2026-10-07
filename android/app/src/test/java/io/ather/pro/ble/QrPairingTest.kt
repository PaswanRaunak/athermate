package io.ather.pro.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QrPairingTest {

    @Test
    fun `bare mac address`() {
        val payload = QrPairing.parse("AA:BB:CC:DD:EE:FF")
        assertEquals("AA:BB:CC:DD:EE:FF", payload.address)
        assertNull(payload.name)
        assertTrue(payload.recognised)
    }

    @Test
    fun `dash separated mac normalises to colons`() {
        val payload = QrPairing.parse("AA-BB-CC-DD-EE-FF")
        assertEquals("AA:BB:CC:DD:EE:FF", payload.address)
    }

    @Test
    fun `lowercase mac uppercases`() {
        val payload = QrPairing.parse("scooter a0:b1:c2:d3:e4:f5")
        assertEquals("A0:B1:C2:D3:E4:F5", payload.address)
    }

    @Test
    fun `json payload with mac and name`() {
        val payload = QrPairing.parse("""{"mac":"AA:BB:CC:DD:EE:FF","name":"Ather 450X"}""")
        assertEquals("AA:BB:CC:DD:EE:FF", payload.address)
        assertEquals("Ather 450X", payload.name)
        assertTrue(payload.recognised)
    }

    @Test
    fun `json with device_address and snake_case name`() {
        val payload = QrPairing.parse("""{"device_address":"11:22:33:44:55:66","device_name":"Scooter"}""")
        assertEquals("11:22:33:44:55:66", payload.address)
        assertEquals("Scooter", payload.name)
    }

    @Test
    fun `url encoded mac in query parameter`() {
        val payload = QrPairing.parse("https://pair.ather.app/ble?mac=AA%3ABB%3ACC%3ADD%3AEE%3AFF&name=450X")
        assertEquals("AA:BB:CC:DD:EE:FF", payload.address)
        assertEquals("450X", payload.name)
    }

    @Test
    fun `plain mac inside longer text`() {
        val payload = QrPairing.parse("Scan ok. Device 0A-0B-0C-0D-0E-0F ready.")
        assertEquals("0A:0B:0C:0D:0E:0F", payload.address)
    }

    @Test
    fun `unrecognised payload keeps raw text`() {
        val raw = "https://ather.app/some/deep/link"
        val payload = QrPairing.parse(raw)
        assertNull(payload.address)
        assertNull(payload.name)
        assertFalse(payload.recognised)
        assertEquals(raw, payload.raw)
    }
}
