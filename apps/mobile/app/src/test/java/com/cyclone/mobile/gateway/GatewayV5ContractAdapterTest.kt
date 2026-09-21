package com.cyclone.mobile.gateway

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GatewayV5ContractAdapterTest {
    @Test
    fun run1OperationsAreAdvertised() {
        val expected = setOf("atlas.places", "atlas.get", "secrets.slots", "secrets.request")
        assertTrue(GatewayProtocol.operations.containsAll(expected))
    }

    @Test
    fun run1OperationsDispatchEmptyButValidPhoneOwnedShapes() {
        val places = GatewayV5ContractAdapter.dispatch("atlas.places", JSONObject())
        assertEquals(0, places.getJSONArray("places").length())

        val atlas = GatewayV5ContractAdapter.dispatch(
            "atlas.get",
            JSONObject().put("placeId", "package:com.example.app").put("persona", "live"),
        )
        assertEquals("unmapped", atlas.getString("mapStatus"))
        assertEquals(0, atlas.getJSONArray("screens").length())
        assertEquals(0, atlas.getJSONArray("edges").length())

        val slots = GatewayV5ContractAdapter.dispatch(
            "secrets.slots",
            JSONObject().put("placeId", "package:com.example.app").put("persona", "mapping"),
        )
        assertEquals(0, slots.getJSONObject("slots").length())

        val request = GatewayV5ContractAdapter.dispatch(
            "secrets.request",
            JSONObject()
                .put("placeId", "package:com.example.app")
                .put("persona", "live")
                .put("slot", "password")
                .put("reason", "Login required"),
        )
        assertEquals("needs-secret", request.getString("state"))
        assertFalse(request.getJSONObject("request").has("value"))
    }

    @Test
    fun secretBearingFieldIsRejectedInsteadOfSanitized() {
        val error = runCatching {
            GatewayV5ContractAdapter.dispatch(
                "secrets.request",
                JSONObject()
                    .put("placeId", "package:com.example.app")
                    .put("persona", "live")
                    .put("slot", "password")
                    .put("reason", "Login required")
                    .put("password", "must-never-cross"),
            )
        }.exceptionOrNull()
        assertTrue(error is GatewayProtocolException)
        assertEquals("SECRET_PAYLOAD_REJECTED", (error as GatewayProtocolException).code)
    }
}
