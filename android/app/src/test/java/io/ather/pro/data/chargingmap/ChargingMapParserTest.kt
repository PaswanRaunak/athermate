package io.ather.pro.data.chargingmap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Deterministic parser tests over sanitized Cerberus-shaped fixtures.
 * No live network, credentials, or real wallet balances.
 */
class ChargingMapParserTest {

    @Test
    fun parseLocationsSampleExtractsConnectorsAndAvailability() {
        val locations = ChargingMapParser.parseLocations(readFixture("chargingmap_fixtures/locations_sample.json"))

        assertEquals(2, locations.size)

        val hub = locations[0]
        assertEquals("Indiranagar Grid Hub", hub.name)
        assertEquals("ATHER_GRID", hub.infraType)
        assertEquals("100 Feet Rd, Indiranagar", hub.address)
        assertEquals(12.9784, hub.latitude!!, 0.0001)
        assertEquals(77.6408, hub.longitude!!, 0.0001)
        assertEquals(true, hub.isOpenNow)
        assertEquals(2, hub.dbsAvailable)
        assertEquals(1, hub.dbsInUse)
        assertEquals(3, hub.dbsTotal)
        assertEquals(true, hub.has6kwGrid)
        assertEquals(listOf("covered", "cafe"), hub.locationTags)
        assertEquals(2, hub.connectors.size)
        assertEquals("Ather Grid 3.3 kW", hub.connectors[0].displayText)
        assertEquals("ATHER_GRID", hub.connectors[0].standard)
        assertEquals(2, hub.tariffLines.size)
        assertEquals("Energy", hub.tariffLines[0].displayText)
        assertEquals("₹8.5 / kWh", hub.tariffLines[0].text)

        val closed = locations[1]
        assertEquals(false, closed.isOpenNow)
        assertEquals(0, closed.dbsAvailable)
        assertEquals("Tomorrow 07:00", closed.nextOpening)
        assertEquals(1, closed.connectors.size)
    }

    @Test
    fun parseLocationsEmptyArray() {
        val locations = ChargingMapParser.parseLocations(readFixture("chargingmap_fixtures/locations_empty.json"))
        assertTrue(locations.isEmpty())
    }

    @Test
    fun parseLocationsDropsInvalidCoordinates() {
        val json = """
            [
              {"name":"Bad lat","latitude":120.0,"longitude":77.0},
              {"name":"Ok","latitude":12.97,"longitude":77.59,"dbs_available":1}
            ]
        """.trimIndent()
        val locations = ChargingMapParser.parseLocations(json)
        assertEquals(1, locations.size)
        assertEquals("Ok", locations[0].name)
    }

    @Test
    fun parseWalletWithCreditsAndTransactions() {
        val wallet = ChargingMapParser.parseWallet(readFixture("chargingmap_fixtures/wallet_sample.json"))

        assertEquals(247.5, wallet.balance!!, 0.001)
        assertEquals("ACTIVE", wallet.walletStatus)
        assertEquals(12.0, wallet.credits!!, 0.001)
        assertEquals(2, wallet.transactions.size)
        assertEquals("txn_1001", wallet.transactions[0].id)
        assertEquals(-42.75, wallet.transactions[0].amount!!, 0.001)
        assertEquals("DEBIT", wallet.transactions[0].type)
        assertEquals("Wallet top-up", wallet.transactions[1].title)
    }

    @Test
    fun parseWalletBalanceOnlyLeavesOptionalFieldsAbsent() {
        val wallet = ChargingMapParser.parseWallet(readFixture("chargingmap_fixtures/wallet_balance_only.json"))

        assertEquals(0.0, wallet.balance!!, 0.001)
        assertEquals("ACTIVE", wallet.walletStatus)
        assertNull(wallet.credits)
        assertTrue(wallet.transactions.isEmpty())
    }

    @Test
    fun parseWalletDoesNotInventBalanceWhenMissing() {
        val wallet = ChargingMapParser.parseWallet("""{"data":{"wallet_status":"ACTIVE"}}""")
        assertNull(wallet.balance)
        assertEquals("ACTIVE", wallet.walletStatus)
        assertNull(wallet.credits)
        assertTrue(wallet.transactions.isEmpty())
    }

    @Test
    fun locationsUrlContractMatchesMether() {
        assertEquals("https://cerberus.ather.io/api/v2/locations", ChargingMapApi.LOCATIONS_URL)
        assertEquals("https://cerberus.ather.io/api/v1/wallet", ChargingMapApi.WALLET_URL)
        assertEquals(5, ChargingMapApi.DEFAULT_RADIUS_KM)
        assertEquals(50, ChargingMapApi.DEFAULT_LIMIT)
    }

    private fun readFixture(path: String): String {
        val stream = requireNotNull(javaClass.classLoader?.getResourceAsStream(path)) {
            "Missing fixture: $path"
        }
        return stream.bufferedReader().use { it.readText() }
    }
}
