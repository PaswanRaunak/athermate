package io.ather.pro.data.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pins the verified Cerberus remote-charging shadow mutation shape. */
class RemoteChargingShadowPayloadTest {

    private val client = AtherApiClient()

    @Test
    fun stopPayloadMatchesVerifiedShadowContract() {
        val payload = client.buildRemoteChargingShadowPayload(
            uuid = "bike-uuid-1",
            start = false,
            timestampMs = 1_700_000_000_000L
        )
        assertEquals("ma_bike-uuid-1", payload.get("request_id").asString)
        val command = payload
            .getAsJsonObject("state")
            .getAsJsonObject("desired")
            .getAsJsonObject("remote_charging")
        assertEquals(1, command.get("state").asInt)
        assertEquals("stop", command.get("action").asString)
        assertEquals("0", command.get("error").asString)
        assertEquals(1_700_000_000_000L, command.get("timestamp").asLong)
        assertTrue(command.entrySet().map { it.key }.containsAll(listOf("state", "action", "error", "timestamp")))
        assertEquals(4, command.entrySet().size)
    }

    @Test
    fun startPayloadUsesStartAction() {
        val payload = client.buildRemoteChargingShadowPayload(
            uuid = "bike-uuid-2",
            start = true,
            timestampMs = 42L
        )
        val action = payload
            .getAsJsonObject("state")
            .getAsJsonObject("desired")
            .getAsJsonObject("remote_charging")
            .get("action")
            .asString
        assertEquals("start", action)
        assertEquals("ma_bike-uuid-2", payload.get("request_id").asString)
    }
}
