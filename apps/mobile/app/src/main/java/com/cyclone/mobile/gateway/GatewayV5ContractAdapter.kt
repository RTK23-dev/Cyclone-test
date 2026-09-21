package com.cyclone.mobile.gateway

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

/**
 * Run-1 V5 contract adapter.
 *
 * Phone remains authoritative. Until Vault/Atlas owners land their stores, these operations return
 * empty-but-valid phone-owned data. This adapter never accepts or emits a secret value.
 */
internal object GatewayV5ContractAdapter {
    private val packageName = Regex("^[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+$")
    private val slotName = Regex("^[A-Za-z][A-Za-z0-9._-]{0,63}$")
    private val safeReason = Regex("^[A-Za-z0-9][A-Za-z0-9 ._/-]{0,119}$")
    private val inlineSecret = Regex(
        "(?i)(password|passcode|passwd|pin|otp|token|secret|api[_-]?key|authorization|cookie|cvv|credential|typed[_-]?(text|value))\\s*[:=]"
    )

    fun dispatch(op: String, args: JSONObject): JSONObject = when (op) {
        "atlas.places" -> atlasPlaces(args)
        "atlas.get" -> atlasGet(args)
        "secrets.slots" -> secretSlots(args)
        "secrets.request" -> secretRequest(args)
        else -> throw GatewayProtocolException("UNKNOWN_OPERATION", "Unsupported V5 contract operation: $op")
    }

    private fun atlasPlaces(args: JSONObject): JSONObject {
        requireOnly(args, emptySet())
        return JSONObject().put("places", JSONArray())
    }

    private fun atlasGet(args: JSONObject): JSONObject {
        requireOnly(args, setOf("placeId", "persona"))
        val placeId = requiredString(args, "placeId", 512)
        val persona = persona(args)
        val place = place(placeId)
        return JSONObject()
            .put("place", place)
            .put("persona", persona)
            .put("mapStatus", "unmapped")
            .put("screens", JSONArray())
            .put("edges", JSONArray())
            .put("capabilities", JSONArray())
            .put("confidence", 0.0)
            .put("lastObservedAt", JSONObject.NULL)
            .put("lastVerifiedAt", JSONObject.NULL)
    }

    private fun secretSlots(args: JSONObject): JSONObject {
        requireOnly(args, setOf("placeId", "persona"))
        val placeId = requiredString(args, "placeId", 512)
        place(placeId)
        return JSONObject()
            .put("placeId", placeId)
            .put("persona", persona(args))
            .put("slots", JSONObject())
    }

    private fun secretRequest(args: JSONObject): JSONObject {
        requireOnly(args, setOf("placeId", "persona", "slot", "reason"))
        val placeId = requiredString(args, "placeId", 512)
        place(placeId)
        val persona = persona(args)
        val slot = requiredString(args, "slot", 64)
        if (!slotName.matches(slot)) fail("INVALID_REQUEST", "slot must be metadata only.")
        val reason = requiredString(args, "reason", 120)
        if (!safeReason.matches(reason) || inlineSecret.containsMatchIn(reason)) {
            fail("SECRET_PAYLOAD_REJECTED", "Secret-bearing request payload rejected.")
        }
        val request = JSONObject()
            .put("placeId", placeId)
            .put("persona", persona)
            .put("slot", slot)
            .put("reason", reason)
        return JSONObject()
            .put("state", "needs-secret")
            .put("request", request)
    }

    private fun persona(args: JSONObject): String {
        val persona = requiredString(args, "persona", 16)
        if (persona != "live" && persona != "mapping") {
            fail("INVALID_REQUEST", "persona must be live or mapping.")
        }
        return persona
    }

    private fun place(placeId: String): JSONObject {
        return when {
            placeId.startsWith("package:") -> {
                val value = placeId.removePrefix("package:")
                if (!packageName.matches(value)) fail("INVALID_REQUEST", "Invalid package placeId.")
                JSONObject()
                    .put("placeId", placeId)
                    .put("kind", "package")
                    .put("label", value.substringAfterLast('.').take(120).ifBlank { value.take(120) })
                    .put("packageName", value)
            }
            placeId.startsWith("chrome:") -> {
                val origin = placeId.removePrefix("chrome:")
                val uri = runCatching { URI(origin) }.getOrNull()
                if (uri == null || uri.scheme !in setOf("http", "https") || uri.host.isNullOrBlank() ||
                    uri.path.orEmpty().let { it.isNotEmpty() && it != "/" } || uri.query != null || uri.fragment != null
                ) {
                    fail("INVALID_REQUEST", "Invalid Chrome origin placeId.")
                }
                val normalizedOrigin = buildString {
                    append(uri.scheme).append("://").append(uri.host)
                    if (uri.port != -1) append(':').append(uri.port)
                }
                if (normalizedOrigin != origin.removeSuffix("/")) {
                    fail("INVALID_REQUEST", "Chrome placeId must contain an origin only.")
                }
                JSONObject()
                    .put("placeId", placeId)
                    .put("kind", "chrome-origin")
                    .put("label", uri.host.take(120))
                    .put("origin", normalizedOrigin)
            }
            else -> fail("INVALID_REQUEST", "placeId must use package: or chrome: identity.")
        }
    }

    private fun requireOnly(args: JSONObject, allowed: Set<String>) {
        val extras = args.keys().asSequence().filter { it !in allowed }.toList()
        if (extras.isNotEmpty()) {
            val secretKey = extras.firstOrNull { key ->
                key.lowercase() in setOf(
                    "password", "passcode", "passwd", "pin", "otp", "token", "secret", "api_key",
                    "authorization", "cookie", "cvv", "credential", "typed_text", "typed_value",
                )
            }
            if (secretKey != null) fail("SECRET_PAYLOAD_REJECTED", "Secret-bearing request payload rejected.")
            fail("INVALID_REQUEST", "Unexpected V5 contract field.")
        }
    }

    private fun requiredString(args: JSONObject, key: String, max: Int): String {
        val value = args.opt(key)
        if (value !is String || value.isBlank() || value.length > max) {
            fail("INVALID_REQUEST", "$key is required.")
        }
        return value
    }

    private fun fail(code: String, message: String): Nothing =
        throw GatewayProtocolException(code, message)
}
