package com.cyclone.mobile.gateway

import android.content.Context
import com.cyclone.mobile.ai.RunInsight
import com.cyclone.mobile.automation.AutomationDefinition
import com.cyclone.mobile.automation.AutomationRuntime
import com.cyclone.mobile.automation.SkillDefinition
import com.cyclone.mobile.brain.graphv2.AtlasPlaceSummary
import com.cyclone.mobile.secrets.SecretSlotMetadata
import com.cyclone.mobile.secrets.SecretsVaultRuntime
import org.json.JSONArray
import org.json.JSONObject

/**
 * V5 `knowledge.get` for Glass's Knowledge page (plan 03): what Cyclone knows beyond maps.
 *
 * - Vault: which secret slots exist per place and whether each is **set** — never a value, length or hint.
 * - Skills and automations the user taught or approved: names, step counts, enabled.
 * - Atlas totals: places, rooms, doors.
 *
 * Read-only. Names pass through the same redaction as run text, so a secret-looking `key=value` never leaves.
 */
internal object GatewayV5KnowledgeSummaryAdapter {
    const val MAX_SLOTS = 200
    const val MAX_SKILLS = 100

    /** Seams for JVM tests; production reads the vault metadata, the automation store and the Atlas. */
    @Volatile internal var slots: () -> List<SecretSlotMetadata> = { emptyList() }
    @Volatile internal var skills: () -> List<SkillDefinition> = { emptyList() }
    @Volatile internal var automations: () -> List<AutomationDefinition> = { emptyList() }
    @Volatile internal var places: () -> List<AtlasPlaceSummary> = { emptyList() }

    fun install(context: Context) {
        val app = context.applicationContext
        slots = { runCatching { SecretsVaultRuntime.allSlots(app) }.getOrDefault(emptyList()) }
        skills = { AutomationRuntime.initialize(app); AutomationRuntime.store.listSkills() }
        automations = { AutomationRuntime.initialize(app); AutomationRuntime.store.listAutomations() }
        places = {
            com.cyclone.mobile.applearner.graphv2.AtlasRuntime.initialize(app)
            com.cyclone.mobile.applearner.graphv2.AtlasRuntime.store.places()
        }
    }

    fun dispatch(op: String, args: JSONObject): JSONObject = when (op) {
        "knowledge.get" -> summary(args)
        else -> throw GatewayProtocolException("UNKNOWN_OPERATION", "Unsupported knowledge operation: $op")
    }

    fun summary(args: JSONObject): JSONObject {
        if (args.length() != 0) throw GatewayProtocolException("INVALID_REQUEST", "knowledge.get takes no arguments.")
        val allSlots = runCatching(slots).getOrDefault(emptyList())
        val allSkills = runCatching(skills).getOrDefault(emptyList())
        val allAutomations = runCatching(automations).getOrDefault(emptyList())
        val allPlaces = runCatching(places).getOrDefault(emptyList())
        return JSONObject()
            .put("vault", JSONObject()
                .put("slotCount", allSlots.size)
                .put("setCount", allSlots.count { it.present })
                .put("slots", JSONArray(allSlots.sortedWith(compareBy({ it.key.placeId }, { it.key.slotName })).take(MAX_SLOTS).map { slot ->
                    JSONObject()
                        .put("placeId", slot.key.placeId.take(200))
                        .put("persona", slot.key.persona.wireValue)
                        .put("slot", slot.key.slotName.take(64))
                        .put("set", slot.present)
                        .put("updatedAt", slot.updatedAtMs.takeIf { it > 0 } ?: JSONObject.NULL)
                })))
            .put("skills", JSONArray(allSkills.take(MAX_SKILLS).map { skill ->
                JSONObject()
                    .put("id", skill.id.take(80))
                    .put("name", RunInsight.wireText(skill.name, 80))
                    .put("steps", skill.steps.size)
                    .put("enabled", skill.enabled)
                    .put("version", skill.version)
            }))
            .put("automations", JSONArray(allAutomations.take(MAX_SKILLS).map { automation ->
                JSONObject()
                    .put("id", automation.id.take(80))
                    .put("name", RunInsight.wireText(automation.name, 80))
                    .put("trigger", automation.trigger.type.name.lowercase().take(40))
                    .put("steps", automation.steps.size)
                    .put("enabled", automation.enabled)
            }))
            .put("atlas", JSONObject()
                .put("places", allPlaces.map { it.place.id }.distinct().size)
                .put("rooms", allPlaces.sumOf { it.screenCount })
                .put("doors", allPlaces.sumOf { it.edgeCount }))
    }

    internal fun resetForTests() {
        slots = { emptyList() }
        skills = { emptyList() }
        automations = { emptyList() }
        places = { emptyList() }
    }
}
