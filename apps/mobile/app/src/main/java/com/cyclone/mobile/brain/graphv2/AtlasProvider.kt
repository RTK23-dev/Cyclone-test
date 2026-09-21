package com.cyclone.mobile.brain.graphv2

import org.json.JSONArray
import org.json.JSONObject

/**
 * Narrow phone-owned read surface for Agent 001's gateway adapter. It only emits whitelisted Atlas
 * metadata; there is deliberately no generic graph/database dump method.
 */
interface AtlasReadProvider {
    fun places(persona: AtlasPersona? = null): JSONObject
    fun get(placeId: String, persona: AtlasPersona): JSONObject?
}

class StoreBackedAtlasReadProvider(
    private val store: AtlasStore,
) : AtlasReadProvider {
    override fun places(persona: AtlasPersona?): JSONObject = JSONObject()
        .put("places", JSONArray().also { array ->
            store.places(persona).forEach { summary ->
                array.put(placeSummaryJson(summary))
            }
        })

    override fun get(placeId: String, persona: AtlasPersona): JSONObject? {
        val snapshot = store.snapshot(AtlasPlaceKey(placeId, persona)) ?: return null
        val pages = snapshot.nodes.filterIsInstance<PageNode>().associateBy { it.id }
        val screenMeta = snapshot.screens.associateBy { it.screenId }
        val edgeMeta = snapshot.edgeMetadata.associateBy { it.key }
        val navigation = snapshot.edges
            .filter { it.key.type in NAVIGATION_EDGES && it.key.from in pages && it.key.to in pages }
            .sortedBy { it.key }

        return JSONObject()
            .put("placeId", snapshot.place.id)
            .put("kind", snapshot.place.kind.wireValue)
            .put("label", snapshot.place.label)
            .put("packageName", snapshot.place.packageName ?: JSONObject.NULL)
            .put("origin", snapshot.place.origin ?: JSONObject.NULL)
            .put("persona", snapshot.place.persona.wireValue)
            .put("mapStatus", snapshot.place.mapStatus.wireValue)
            .put("lastObservedAt", snapshot.place.lastObservedAtEpochMillis ?: JSONObject.NULL)
            .put("lastVerifiedAt", snapshot.place.lastVerifiedAtEpochMillis ?: JSONObject.NULL)
            .put("screens", JSONArray().also { out ->
                pages.values.sortedBy { it.id.value }.forEachIndexed { index, page ->
                    val meta = screenMeta[page.id]
                    val fallback = AtlasGraphIds.stableLayout(snapshot.place.id + ":" + persona.wireValue, index)
                    out.put(JSONObject()
                        .put("id", page.id.value)
                        .put("purpose", meta?.purpose ?: AtlasPrivacy.structuralPurpose(page.displayName))
                        .put("capabilities", JSONArray(meta?.capabilities?.sorted().orEmpty()))
                        .put("factSlots", JSONArray().also { facts ->
                            meta?.factSlots?.sortedBy { it.name }.orEmpty().forEach { slot ->
                                facts.put(JSONObject()
                                    .put("name", slot.name)
                                    .put("purpose", slot.purpose)
                                    .put("selectorKey", slot.selectorKey ?: JSONObject.NULL)
                                    .put("confidence", slot.confidence))
                            }
                        })
                        .put("danger", (meta?.danger ?: AtlasDanger.NONE).wireValue)
                        .put("confidence", meta?.confidence ?: 0.0)
                        .put("lastObservedAt", meta?.lastObservedAtEpochMillis ?: JSONObject.NULL)
                        .put("lastVerifiedAt", meta?.lastVerifiedAtEpochMillis ?: JSONObject.NULL)
                        .put("layout", JSONObject()
                            .put("x", meta?.layoutX ?: fallback.first)
                            .put("y", meta?.layoutY ?: fallback.second)))
                }
            })
            .put("edges", JSONArray().also { out ->
                navigation.forEach { edge ->
                    val meta = edgeMeta[edge.key]
                    out.put(JSONObject()
                        .put("id", edgeId(edge.key))
                        .put("from", edge.key.from.value)
                        .put("to", edge.key.to.value)
                        .put("kind", edge.key.type.name.lowercase())
                        .put("action", meta?.action ?: "navigate")
                        .put("selectorKey", meta?.selectorKey ?: JSONObject.NULL)
                        .put("danger", (meta?.danger ?: AtlasDanger.NONE).wireValue)
                        .put("confidence", meta?.confidence ?: edge.evidence.confidence)
                        .put("lastObservedAt", meta?.lastObservedAtEpochMillis ?: edge.evidence.observedAtEpochMillis)
                        .put("lastVerifiedAt", meta?.lastVerifiedAtEpochMillis ?: edge.evidence.lastSucceededAtEpochMillis ?: JSONObject.NULL))
                }
            })
    }

    private fun placeSummaryJson(summary: AtlasPlaceSummary): JSONObject = JSONObject()
        .put("placeId", summary.place.id)
        .put("kind", summary.place.kind.wireValue)
        .put("label", summary.place.label)
        .put("packageName", summary.place.packageName ?: JSONObject.NULL)
        .put("origin", summary.place.origin ?: JSONObject.NULL)
        .put("persona", summary.place.persona.wireValue)
        .put("mapStatus", summary.place.mapStatus.wireValue)
        .put("screenCount", summary.screenCount)
        .put("edgeCount", summary.edgeCount)
        .put("lastObservedAt", summary.place.lastObservedAtEpochMillis ?: JSONObject.NULL)
        .put("lastVerifiedAt", summary.place.lastVerifiedAtEpochMillis ?: JSONObject.NULL)

    private fun edgeId(key: GraphEdgeKey): String =
        key.from.value + "|" + key.type.name.lowercase() + "|" + key.to.value

    companion object {
        private val NAVIGATION_EDGES = setOf(GraphEdgeType.NAVIGATES_TO, GraphEdgeType.OPENS, GraphEdgeType.SUBMITS)
    }
}

class PlaceCatalog(
    private val store: AtlasStore,
) {
    fun packages(persona: AtlasPersona? = null): List<AtlasPlaceSummary> =
        store.places(persona).filter { it.place.kind == AtlasPlaceKind.PACKAGE }

    fun chromeOrigins(persona: AtlasPersona? = null): List<AtlasPlaceSummary> =
        store.places(persona).filter { it.place.kind == AtlasPlaceKind.CHROME_ORIGIN }

    fun all(persona: AtlasPersona? = null): List<AtlasPlaceSummary> = store.places(persona)

    fun addPackage(packageName: String, label: String, persona: AtlasPersona): AtlasPlace =
        store.ensurePackagePlace(packageName, label, persona)

    fun addChromeOrigin(origin: String, label: String, persona: AtlasPersona): AtlasPlace =
        store.ensureChromeOrigin(origin, label, persona)
}

/**
 * Atlas retrieval returns data-only hints. It cannot dispatch phone actions and has no dependency on
 * PhoneToolExecutor or AppGraphExecutor.
 */
class AtlasRetriever(
    private val store: AtlasStore,
) {
    fun findHint(
        key: AtlasPlaceKey,
        goal: String,
        currentScreenId: GraphNodeId? = null,
    ): AtlasNavigationHint? {
        val snapshot = store.snapshot(key) ?: return null
        val normalized = goal.lowercase().trim()
        if (normalized.isBlank()) return null
        val pages = snapshot.nodes.filterIsInstance<PageNode>().associateBy { it.id }
        val meta = snapshot.screens.associateBy { it.screenId }
        val scored = pages.values.map { page ->
            val screen = meta[page.id]
            val haystack = buildString {
                append(page.identity.lowercase()).append(' ')
                append(screen?.purpose?.lowercase().orEmpty()).append(' ')
                append(screen?.capabilities?.joinToString(" ")?.lowercase().orEmpty()).append(' ')
                append(screen?.factSlots?.joinToString(" ") { it.name + " " + it.purpose }?.lowercase().orEmpty())
            }
            page to tokenScore(normalized, haystack)
        }.filter { it.second > 0 }
        val target = scored.maxWithOrNull(compareBy<Pair<PageNode, Int>>({ it.second }, { it.first.id.value }))?.first
            ?: return null

        val path = if (currentScreenId == null || currentScreenId == target.id) {
            listOf(target.id)
        } else {
            shortestPath(snapshot, currentScreenId, target.id) ?: listOf(target.id)
        }
        val targetMeta = meta[target.id]
        val pathKeys = path.zipWithNext { from, to -> GraphEdgeKey(from, GraphEdgeType.NAVIGATES_TO, to) }
        val edgeMeta = snapshot.edgeMetadata.associateBy { it.key }
        val confidence = buildList {
            targetMeta?.confidence?.let(::add)
            pathKeys.mapNotNullTo(this) { edgeMeta[it]?.confidence }
        }.minOrNull() ?: targetMeta?.confidence ?: 0.0
        val danger = buildList {
            targetMeta?.danger?.let(::add)
            pathKeys.mapNotNullTo(this) { edgeMeta[it]?.danger }
        }.maxByOrNull(::dangerRank) ?: AtlasDanger.NONE
        val matchedSlot = targetMeta?.factSlots?.firstOrNull {
            normalized.contains(it.name.replace('_', ' ')) || it.purpose.lowercase().contains(normalized)
        }?.name
        val matchedCapability = targetMeta?.capabilities?.firstOrNull {
            normalized.split(Regex("\\s+")).any { token -> token.length >= 3 && it.lowercase().contains(token) }
        }

        return AtlasNavigationHint(
            place = key,
            destinationScreen = target.id,
            candidatePath = path,
            capability = matchedCapability,
            factSlot = matchedSlot,
            confidence = confidence.coerceIn(0.0, 1.0),
            danger = danger,
        )
    }

    private fun shortestPath(
        snapshot: AtlasGraphSnapshot,
        start: GraphNodeId,
        target: GraphNodeId,
    ): List<GraphNodeId>? {
        val pageIds = snapshot.nodes.filterIsInstance<PageNode>().map { it.id }.toSet()
        if (start !in pageIds || target !in pageIds) return null
        val adjacency = snapshot.edges
            .filter { it.key.type in NAVIGATION_EDGES && it.key.from in pageIds && it.key.to in pageIds }
            .groupBy({ it.key.from }, { it.key.to })
        val queue = ArrayDeque<GraphNodeId>().apply { add(start) }
        val previous = linkedMapOf<GraphNodeId, GraphNodeId?>().apply { put(start, null) }
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            if (current == target) break
            adjacency[current].orEmpty().sortedBy { it.value }.forEach { next ->
                if (next !in previous) {
                    previous[next] = current
                    queue.add(next)
                }
            }
        }
        if (target !in previous) return null
        val reversed = mutableListOf<GraphNodeId>()
        var cursor: GraphNodeId? = target
        while (cursor != null) {
            reversed += cursor
            cursor = previous[cursor]
        }
        return reversed.asReversed()
    }

    private fun tokenScore(needle: String, haystack: String): Int =
        needle.split(Regex("\\s+"))
            .filter { it.length >= 2 }
            .sumOf { token -> if (haystack.contains(token)) token.length else 0 }

    private fun dangerRank(value: AtlasDanger): Int = when (value) {
        AtlasDanger.NONE -> 0
        AtlasDanger.AUTHENTICATION -> 1
        AtlasDanger.PERMISSION -> 2
        AtlasDanger.UNKNOWN -> 3
        AtlasDanger.SEND_PUBLIC -> 4
        AtlasDanger.LOGOUT_ALL -> 5
        AtlasDanger.DELETE_ACCOUNT -> 6
        AtlasDanger.PAYMENT -> 7
    }

    companion object {
        private val NAVIGATION_EDGES = setOf(GraphEdgeType.NAVIGATES_TO, GraphEdgeType.OPENS, GraphEdgeType.SUBMITS)
    }
}
