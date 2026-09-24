package com.cyclone.mobile.gateway

import android.content.Context
import com.cyclone.mobile.ai.AgentTraceRuntime
import com.cyclone.mobile.ai.RunInsight
import com.cyclone.mobile.brain.graphv2.AppVersionEvidence
import com.cyclone.mobile.brain.graphv2.AtlasDanger
import com.cyclone.mobile.brain.graphv2.AtlasGraphIds
import com.cyclone.mobile.brain.graphv2.AtlasGraphSnapshot
import com.cyclone.mobile.brain.graphv2.AtlasPersona
import com.cyclone.mobile.brain.graphv2.AtlasPlaceKey
import com.cyclone.mobile.brain.graphv2.AtlasStore
import com.cyclone.mobile.brain.graphv2.GraphEdgeType
import com.cyclone.mobile.brain.graphv2.GraphNodeId
import com.cyclone.mobile.brain.graphv2.PageNode
import com.cyclone.mobile.brain.graphv2.TemporalKnowledgeEdge
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/** One run's walk through one app, for scenario health. Structural room ids only. */
internal data class RunWalk(
    val runId: String,
    val status: String,
    val startedAt: Long,
    val placeId: String,
    val rooms: List<String>,
)

/**
 * V5 `atlas.versions` and `scenarios.list` for Glass's Versions and Scenarios tabs (plan 04).
 *
 * - **Versions**: which app versions the phone's doors were learned or confirmed on, which version is installed, and
 *   which doors were last confirmed on an older version (they may be stale after an update).
 * - **Scenarios**: one known route per destination room, from the app's entry room, with health from the real runs
 *   that walked through that destination (passing / warning / critical / untested). Titles name rooms, never people.
 *
 * Read-only and computed on the phone; Glass only shows the result.
 */
internal object GatewayV5KnowledgeAdapter {
    const val MAX_SCENARIOS = 24
    const val MAX_STALE_DOORS = 20
    private const val MAX_VERSIONS = 12
    private const val RUNS_CONSIDERED = 60
    private val NAVIGATION = setOf(GraphEdgeType.NAVIGATES_TO, GraphEdgeType.OPENS, GraphEdgeType.SUBMITS)
    private val packageName = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+$")

    /** Seams for JVM tests; production reads the durable Atlas, PackageManager and the run trace. */
    @Volatile internal var snapshot: (String, AtlasPersona) -> AtlasGraphSnapshot? = { _, _ -> null }
    @Volatile internal var installedVersion: (String) -> AppVersionEvidence? = { null }
    @Volatile internal var walks: (String) -> List<RunWalk> = { emptyList() }

    fun install(context: Context, store: AtlasStore) {
        val app = context.applicationContext
        snapshot = { placeId, persona -> runCatching { store.snapshot(AtlasPlaceKey(placeId, persona)) }.getOrNull() }
        installedVersion = { pkg ->
            runCatching {
                val info = app.packageManager.getPackageInfo(pkg, 0)
                AppVersionEvidence(pkg, info.versionName, info.longVersionCode)
            }.getOrNull()
        }
        walks = { placeId ->
            AgentTraceRuntime.initialize(app)
            if (!AgentTraceRuntime.isReady()) emptyList() else AgentTraceRuntime.store.listSessions(RUNS_CONSIDERED)
                .filterNot { GatewayV5RunsAdapter.marks.isExpected(it.id) }
                .mapNotNull { session ->
                val steps = RunInsight.steps(AgentTraceRuntime.store.events(session.id))
                val rooms = RunInsight.places(steps).firstOrNull { it.getString("placeId") == placeId }
                    ?.getJSONArray("route")?.let { route -> (0 until route.length()).map(route::getString) }
                    ?: return@mapNotNull null
                RunWalk(session.id, RunInsight.wireStatus(session.status), session.startedAt, placeId, rooms)
            }
        }
    }

    fun dispatch(op: String, args: JSONObject): JSONObject = when (op) {
        "atlas.versions" -> versions(args)
        "scenarios.list" -> scenarios(args)
        else -> throw GatewayProtocolException("UNKNOWN_OPERATION", "Unsupported knowledge operation: $op")
    }

    fun versions(args: JSONObject): JSONObject {
        requireOnly(args, setOf("placeId"))
        val placeId = packagePlace(args)
        val pkg = placeId.removePrefix("package:")
        val doors = AtlasPersona.values().flatMap { persona -> navigationDoors(snapshot(placeId, persona)) }
        val installed = installedVersion(pkg)
        val installedId = installed?.stableIdentity?.takeIf { it.isNotBlank() }
        val byVersion = doors.filter { it.evidence.appVersion?.stableIdentity?.isNotBlank() == true }
            .groupBy { it.evidence.appVersion!!.stableIdentity }
        val versions = byVersion.map { (_, edges) ->
            val version = edges.first().evidence.appVersion!!
            val rooms = edges.flatMap { listOf(it.key.from.value, it.key.to.value) }.toSet()
            version to JSONObject()
                .put("versionName", version.versionName?.take(64) ?: JSONObject.NULL)
                .put("versionCode", version.versionCode ?: JSONObject.NULL)
                .put("installed", version.stableIdentity == installedId)
                .put("doors", edges.size)
                .put("rooms", rooms.size)
                .put("failingDoors", edges.count(::failing))
                .put("lastSeenAt", edges.maxOf { it.evidence.observedAtEpochMillis })
        }
            .sortedWith(compareByDescending<Pair<AppVersionEvidence, JSONObject>> { it.first.versionCode ?: -1L }
                .thenByDescending { it.first.versionName.orEmpty() })
            .take(MAX_VERSIONS)
        val stale = if (installedId == null) emptyList() else doors.filter { edge ->
            val id = edge.evidence.appVersion?.stableIdentity
            !id.isNullOrBlank() && id != installedId
        }
        val mapped = doors.isNotEmpty()
        return JSONObject()
            .put("placeId", placeId)
            .put("installedVersion", installed?.let {
                JSONObject().put("versionName", it.versionName?.take(64) ?: JSONObject.NULL).put("versionCode", it.versionCode ?: JSONObject.NULL)
            } ?: JSONObject.NULL)
            .put("needsRemap", mapped && installedId != null && versions.none { it.second.getBoolean("installed") })
            .put("versions", JSONArray(versions.map { it.second }))
            .put("staleDoorCount", stale.size)
            .put("staleDoors", JSONArray(stale.sortedBy { it.evidence.observedAtEpochMillis }.take(MAX_STALE_DOORS).map { edge ->
                JSONObject()
                    .put("edgeId", AtlasGraphIds.wireEdgeId(edge.key))
                    .put("fromScreenId", edge.key.from.value)
                    .put("toScreenId", edge.key.to.value)
                    .put("versionName", edge.evidence.appVersion?.versionName?.take(64) ?: JSONObject.NULL)
                    .put("versionCode", edge.evidence.appVersion?.versionCode ?: JSONObject.NULL)
            }))
    }

    fun scenarios(args: JSONObject): JSONObject {
        requireOnly(args, setOf("placeId", "persona"))
        val placeId = packagePlace(args)
        val persona = when (val raw = args.opt("persona")) {
            null, JSONObject.NULL, "mapping" -> AtlasPersona.MAPPING
            "live" -> AtlasPersona.LIVE
            else -> throw GatewayProtocolException("INVALID_REQUEST", "persona must be live or mapping, not $raw.")
        }
        val snap = snapshot(placeId, persona)
            ?: return JSONObject().put("placeId", placeId).put("persona", persona.wireValue).put("entryScreenId", JSONObject.NULL)
                .put("scenarios", JSONArray())
        val pages = snap.nodes.filterIsInstance<PageNode>().map { it.id }.toSet()
        val screens = snap.screens.filter { it.screenId in pages }.associateBy { it.screenId }
        val doors = navigationDoors(snap).filter { it.key.from in screens && it.key.to in screens }
        val entry = entryRoom(screens.keys, doors, snap)
        val meta = snap.edgeMetadata.associateBy { it.key }
        val runWalks = runCatching { walks(placeId) }.getOrDefault(emptyList()).sortedByDescending { it.startedAt }

        val scenarios = if (entry == null) emptyList() else shortestRoutes(entry, doors).entries
            .filter { (room, path) -> room != entry && path.isNotEmpty() }
            .sortedWith(compareBy({ it.value.size }, { it.key.value }))
            .take(MAX_SCENARIOS)
            .map { (room, path) ->
                val route = listOf(entry.value) + path.map { it.key.to.value }
                val used = runWalks.filter { room.value in it.rooms }
                val danger = path.any { (meta[it.key]?.danger ?: AtlasDanger.NONE) != AtlasDanger.NONE } ||
                    (screens[room]?.danger ?: AtlasDanger.NONE) != AtlasDanger.NONE
                val verified = path.mapNotNull { it.evidence.lastSucceededAtEpochMillis }.maxOrNull()
                JSONObject()
                    .put("scenarioId", scenarioId(placeId, persona, room.value))
                    .put("title", "Reach ${title(screens[room]?.purpose)}")
                    .put("startScreenId", entry.value)
                    .put("endScreenId", room.value)
                    .put("route", JSONArray(route))
                    .put("steps", path.size)
                    .put("danger", danger)
                    .put("health", health(used))
                    .put("lastVerifiedAt", verified ?: JSONObject.NULL)
                    .put("appVersion", path.lastOrNull()?.evidence?.appVersion?.versionName?.take(64) ?: JSONObject.NULL)
                    .put("runs", JSONArray(used.take(5).map { walk ->
                        JSONObject().put("runId", walk.runId).put("status", walk.status).put("startedAt", walk.startedAt)
                    }))
            }
        return JSONObject()
            .put("placeId", placeId)
            .put("persona", persona.wireValue)
            .put("entryScreenId", entry?.value ?: JSONObject.NULL)
            .put("scenarios", JSONArray(scenarios))
    }

    /** passing: the latest run through here finished; critical: the last two failed; warning: mixed; untested: none. */
    fun health(used: List<RunWalk>): String {
        val ended = used.filter { it.status != "running" }
        if (ended.isEmpty()) return "untested"
        if (ended.first().status == "completed") return "passing"
        if (ended.size >= 2 && ended.take(2).none { it.status == "completed" }) return "critical"
        return "warning"
    }

    private fun navigationDoors(snapshot: AtlasGraphSnapshot?): List<TemporalKnowledgeEdge> =
        snapshot?.edges.orEmpty().filter { it.key.type in NAVIGATION }

    private fun failing(edge: TemporalKnowledgeEdge): Boolean {
        val failed = edge.evidence.lastFailedAtEpochMillis ?: return false
        return failed >= (edge.evidence.lastSucceededAtEpochMillis ?: -1L)
    }

    /** The room without incoming doors that opens the most doors; else the room with the most doors out. */
    private fun entryRoom(rooms: Set<GraphNodeId>, doors: List<TemporalKnowledgeEdge>, snap: AtlasGraphSnapshot): GraphNodeId? {
        if (rooms.isEmpty()) return null
        val incoming = doors.map { it.key.to }.toSet()
        val out = doors.groupingBy { it.key.from }.eachCount()
        val home = snap.screens.firstOrNull { it.screenId in rooms && it.purpose.equals("home", ignoreCase = true) }?.screenId
        return home ?: rooms.filter { it !in incoming }.maxWithOrNull(compareBy<GraphNodeId> { out[it] ?: 0 }.thenByDescending { it.value })
            ?: rooms.maxWithOrNull(compareBy<GraphNodeId> { out[it] ?: 0 }.thenByDescending { it.value })
    }

    /** Breadth-first: fewest doors from the entry to every reachable room; ties go to the most trusted door. */
    private fun shortestRoutes(entry: GraphNodeId, doors: List<TemporalKnowledgeEdge>): Map<GraphNodeId, List<TemporalKnowledgeEdge>> {
        val out = doors.groupBy { it.key.from }
        val routes = linkedMapOf(entry to emptyList<TemporalKnowledgeEdge>())
        val queue = ArrayDeque(listOf(entry))
        while (queue.isNotEmpty()) {
            val room = queue.removeFirst()
            out[room].orEmpty().sortedByDescending { it.evidence.confidence }.forEach { door ->
                if (door.key.to !in routes) {
                    routes[door.key.to] = routes.getValue(room) + door
                    queue.addLast(door.key.to)
                }
            }
        }
        return routes
    }

    private fun title(purpose: String?): String {
        val words = purpose.orEmpty().replace('_', ' ').replace(Regex("\\s+"), " ").trim().take(60)
        return if (words.isBlank()) "a screen" else words.replaceFirstChar { it.uppercase() }
    }

    private fun scenarioId(placeId: String, persona: AtlasPersona, room: String): String =
        "sc_" + MessageDigest.getInstance("SHA-256").digest("$placeId|${persona.wireValue}|$room".toByteArray())
            .take(9).joinToString("") { "%02x".format(it) }

    private fun packagePlace(args: JSONObject): String {
        val placeId = args.opt("placeId") as? String
            ?: throw GatewayProtocolException("INVALID_REQUEST", "placeId is required.")
        if (!placeId.startsWith("package:") || !packageName.matches(placeId.removePrefix("package:")) || placeId.length > 200) {
            throw GatewayProtocolException("INVALID_REQUEST", "Scenarios and versions are for apps (package:…) for now.")
        }
        return placeId
    }

    private fun requireOnly(args: JSONObject, allowed: Set<String>) {
        val extra = args.keys().asSequence().filter { it !in allowed }.toList()
        if (extra.isNotEmpty()) throw GatewayProtocolException("INVALID_REQUEST", "Unexpected arguments: ${extra.sorted().joinToString()}.")
    }

    internal fun resetForTests() {
        snapshot = { _, _ -> null }
        installedVersion = { null }
        walks = { emptyList() }
    }
}
