package com.cyclone.mobile.gateway

import com.cyclone.mobile.automation.AutomationDefinition
import com.cyclone.mobile.automation.SkillDefinition
import com.cyclone.mobile.automation.TriggerDefinition
import com.cyclone.mobile.automation.TriggerType
import com.cyclone.mobile.secrets.SecretPersona
import com.cyclone.mobile.secrets.SecretSlotKey
import com.cyclone.mobile.secrets.SecretSlotMetadata
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GatewayV5KnowledgeSummaryAdapterTest {
    @After
    fun reset() = GatewayV5KnowledgeSummaryAdapter.resetForTests()

    @Test
    fun summaryNamesSlotsAndSkillsButNeverValues() {
        GatewayV5KnowledgeSummaryAdapter.slots = {
            listOf(
                SecretSlotMetadata(SecretSlotKey.of("package:com.facebook.katana", SecretPersona.LIVE, "password"), true, 1, 2),
                SecretSlotMetadata(SecretSlotKey.of("package:com.facebook.katana", SecretPersona.LIVE, "email"), false, 1, 0),
            )
        }
        GatewayV5KnowledgeSummaryAdapter.skills = {
            listOf(SkillDefinition(id = "skill-1", name = "Morning brief token=abc123", steps = emptyList()))
        }
        GatewayV5KnowledgeSummaryAdapter.automations = {
            listOf(AutomationDefinition(id = "auto-1", name = "Plug in", trigger = TriggerDefinition(TriggerType.entries.first()), steps = emptyList(), enabled = false))
        }
        val result = GatewayV5KnowledgeSummaryAdapter.summary(JSONObject())
        val vault = result.getJSONObject("vault")
        assertEquals(2, vault.getInt("slotCount"))
        assertEquals(1, vault.getInt("setCount"))
        val email = vault.getJSONArray("slots").getJSONObject(0)
        assertEquals("email", email.getString("slot"))
        assertFalse(email.getBoolean("set"))
        assertTrue(email.isNull("updatedAt"))
        assertEquals(setOf("placeId", "persona", "slot", "set", "updatedAt"), email.keys().asSequence().toSet())
        val skill = result.getJSONArray("skills").getJSONObject(0)
        assertFalse(skill.getString("name"), skill.getString("name").contains("abc123"))
        assertFalse(result.getJSONArray("automations").getJSONObject(0).getBoolean("enabled"))
        assertEquals(0, result.getJSONObject("atlas").getInt("places"))
        val bad = runCatching { GatewayV5KnowledgeSummaryAdapter.dispatch("knowledge.get", JSONObject().put("all", true)) }.exceptionOrNull()
        assertEquals("INVALID_REQUEST", (bad as GatewayProtocolException).code)
        assertTrue("knowledge.get" in GatewayProtocol.legacyReadOnlyOperations)
    }
}
