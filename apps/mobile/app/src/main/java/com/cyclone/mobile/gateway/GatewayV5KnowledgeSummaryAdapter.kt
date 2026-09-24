package com.cyclone.mobile.gateway

import android.content.Context
import com.cyclone.mobile.ai.RunInsight
import com.cyclone.mobile.automation.AutomationDefinition
import com.cyclone.mobile.automation.AutomationRuntime
import com.cyclone.mobile.automation.SkillDefinition
import com.cyclone.mobile.brain.graphv2.AtlasDanger
import com.cyclone.mobile.brain.graphv2.AtlasGraphSnapshot
import com.cyclone.mobile.brain.graphv2.AtlasPersona
import com.cyclone.mobile.brain.graphv2.AtlasPlace
import com.cyclone.mobile.brain.graphv2.AtlasPlaceKey
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
 * - Guarded doors (the never-pay list): per app and danger, how many doors and rooms the Atlas marked as pay, send,
 *   delete, sign-out-everywhere or permission. The mapper never presses them; during Ask, GATE asks on the phone.
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
    @Volatile internal var guarded: () -> List<GuardedCount> = { emptyList() }

    /** One row of the never-pay list. */
    internal data class GuardedCount(val placeId: String, val label: String, val persona: AtlasPersona, val danger: AtlasDanger, val doors: Int, val rooms: Int)

    val NEVER = listOf(AtlasDanger.PAYMENT, AtlasDanger.SEND_PUBLIC, AtlasDanger.DELETE_ACCOUNT, AtlasDanger.LOGOUT_ALL, AtlasDanger.PERMISSION)
    const val MAX_GUARDED = 200

    /** Counts guarded doors and rooms in one Atlas snapshot; never labels or selectors. */
    internal fun guardedCounts(place: AtlasPlace, snapshot: AtlasGraphSnapshot): List<GuardedCount> = NEVER.mapNotNull { danger ->
        val doors = snapshot.edgeMetadata.count { it.danger == danger }
        val rooms = snapshot.screens.count { it.danger == danger }
        if (doors + rooms == 0) null else GuardedCount(place.id, place.label, place.persona, danger, doors, rooms)
    }

    fun install(context: Context) {
        val app = context.applicationContext
        slots = { runCatching { SecretsVaultRuntime.allSlots(app) }.getOrDefault(emptyList()) }
        skills = { AutomationRuntime.initialize(app); AutomationRuntime.store.listSkills() }
        automations = { AutomationRuntime.initialize(app); AutomationRuntime.store.listAutomations() }
        places = {
            com.cyclone.mobile.applearner.graphv2.AtlasRuntime.initialize(app)
            com.cyclone.mobile.applearner.graphv2.AtlasRuntime.store.places()
        }
        guarded = {
            com.cyclone.mobile.applearner.graphv2.AtlasRuntime.initialize(app)
            val store = com.cyclone.mobile.applearner.graphv2.AtlasRuntime.store
            store.places().flatMap { summary ->
                val snapshot = store.snapshot(AtlasPlaceKey(summary.place.id, summary.place.persona)) ?: return@flatMap emptyList()
                guardedCounts(summary.place, snapshot)
            }
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
        val allGuarded = runCatching(guarded).getOrDefault(emptyList())
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
            .put("guarded", JSONArray(allGuarded.sortedWith(compareBy({ it.placeId }, { it.persona.wireValue }, { it.danger.ordinal })).take(MAX_GUARDED).map { row ->
                JSONObject()
                    .put("placeId", row.placeId.take(200))
                    .put("label", RunInsight.wireText(row.label, 80))
                    .put("persona", row.persona.wireValue)
                    .put("danger", row.danger.wireValue)
                    .put("doors", row.doors)
                    .put("rooms", row.rooms)
            }))
    }

    internal fun resetForTests() {
        slots = { emptyList() }
        skills = { emptyList() }
        automations = { emptyList() }
        places = { emptyList() }
        guarded = { emptyList() }
    }
}
