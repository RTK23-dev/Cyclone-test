package com.cyclone.mobile.gateway

import com.cyclone.mobile.ai.AgentTraceRuntime
import com.cyclone.mobile.ai.AiTraceEvent
import com.cyclone.mobile.ai.AiTraceSession
import com.cyclone.mobile.ai.RunInsight
import org.json.JSONArray
import org.json.JSONObject

/**
 * V5 `runs.list` / `runs.get` for Cyclone Glass's Runs page and run inspector (V5 plan 11).
 *
 * Read-only. Data is the phone's user-visible run trace plus [RunInsight]'s steps and cause of death; no hidden
 * provider reasoning, no screenshots, no typed values.
 */
internal object GatewayV5RunsAdapter {
    const val DEFAULT_LIMIT = 50
    const val MAX_LIMIT = 200
    private val runId = Regex("^[A-Za-z0-9_-]{4,120}$")
    private val filters = setOf("all", "failed", "completed", "stopped")

    /** Seams for JVM tests; production reads the trace database. */
    @Volatile internal var sessions: (Int) -> List<AiTraceSession> = { limit ->
        if (AgentTraceRuntime.isReady()) AgentTraceRuntime.store.listSessions(limit) else emptyList()
    }
    @Volatile internal var session: (String) -> AiTraceSession? = { id ->
        if (AgentTraceRuntime.isReady()) AgentTraceRuntime.store.session(id) else null
    }
    @Volatile internal var events: (String) -> List<AiTraceEvent> = { id ->
        if (AgentTraceRuntime.isReady()) AgentTraceRuntime.store.events(id) else emptyList()
    }

    fun dispatch(op: String, args: JSONObject): JSONObject = when (op) {
        "runs.list" -> list(args)
        "runs.get" -> get(args)
        else -> throw GatewayProtocolException("UNKNOWN_OPERATION", "Unsupported runs operation: $op")
    }

    fun list(args: JSONObject): JSONObject {
        requireOnly(args, setOf("limit", "filter"))
        val limit = when (val raw = args.opt("limit")) {
            null, JSONObject.NULL -> DEFAULT_LIMIT
            is Number -> raw.toInt().takeIf { it in 1..MAX_LIMIT }
                ?: throw GatewayProtocolException("INVALID_REQUEST", "limit must be 1..$MAX_LIMIT.")
            else -> throw GatewayProtocolException("INVALID_REQUEST", "limit must be a number.")
        }
        val filter = (args.opt("filter") as? String ?: "all").takeIf { it in filters }
            ?: throw GatewayProtocolException("INVALID_REQUEST", "filter must be one of $filters.")
        val runs = sessions(MAX_LIMIT)
            .map { RunInsight.summaryJson(it, events(it.id)) }
            .filter { run ->
                when (filter) {
                    "failed" -> run.getString("status") == "failed"
                    "completed" -> run.getString("status") == "completed"
                    "stopped" -> run.getString("status") in setOf("cancelled", "suspended")
                    else -> true
                }
            }
            .take(limit)
        return JSONObject().put("runs", JSONArray(runs))
    }

    fun get(args: JSONObject): JSONObject {
        requireOnly(args, setOf("runId"))
        val id = (args.opt("runId") as? String)?.takeIf { runId.matches(it) }
            ?: throw GatewayProtocolException("INVALID_REQUEST", "runId is malformed.")
        val found = session(id) ?: throw GatewayProtocolException("RUN_NOT_FOUND", "No run with that id on this phone.")
        return RunInsight.detailJson(found, events(id))
    }

    private fun requireOnly(args: JSONObject, allowed: Set<String>) {
        if (args.keys().asSequence().any { it !in allowed }) {
            throw GatewayProtocolException("INVALID_REQUEST", "Unexpected runs field.")
        }
    }
}
