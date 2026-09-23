package com.cyclone.mobile.ai

import org.json.JSONArray
import org.json.JSONObject

/**
 * Run insight for Cyclone Glass's run inspector (V5 plan 11): a run's steps, its metrics and, for a run that did not
 * finish, the **cause of death** with the step it happened on and what would fix it.
 *
 * Built only from the user-visible trace in [AgentTraceStore] (already cleaned by [TracePrivacy]); never from hidden
 * provider reasoning. The classifier lives here, on the phone, so the phone's own diagnostics and Glass agree.
 */
object RunInsight {
    const val MAX_STEPS = 200
    const val MAX_EVENTS_PER_STEP = 40
    private const val MAX_TEXT = 360
    private const val MAX_DETAIL = 600

    private val BOUNDARY = setOf("TOOL_REQUESTED", "ACTION_REQUESTED", "TOOL_CALL")
    private val ACTION_RESULTS = setOf("ANDROID_EXECUTION", "ACTION_REJECTED", "TOOL_RESULT")
    private val TERMINAL_FAILURES = setOf("HARD_BLOCKER", "NON_CONVERGENCE", "ERROR")
    private val UNVERIFIED_CODES = setOf("completion.unverified", "completion.still_unverified")

    /**
     * Field assignments that survived [TracePrivacy] as `password=[REDACTED]` are rewritten so no `key=` form leaves the
     * phone. No word boundary on purpose: the PC gateway's check has none either, and a phone payload it rejects is lost.
     */
    private val redactedAssignment = Regex(
        "(?i)(password|passcode|passwd|pin|otp|2fa|token|secret|api[_ -]?key|authorization|cookie|cvv|credential|typed[_-]?(?:text|value))\\s*[:=]\\s*\\S*",
    )
    private val secretWall = Regex("(?i)password|passcode|sign[ -]?in|log[ -]?in|login|credential|needs[-_ ]secret|need_secret|secrets card|otp|2fa")

    enum class StepOutcome(val wire: String) { OK("ok"), FAILED("failed"), UNVERIFIED("unverified"), RECOVERED("recovered"), INFO("info") }

    data class Step(
        val index: Int,
        val startedAt: Long,
        val endedAt: Long,
        val title: String,
        val action: String?,
        val pageId: String?,
        val outcome: StepOutcome,
        val verification: String?,
        val recovery: String?,
        val vision: Boolean,
        val events: List<AiTraceEvent>,
    )

    data class Cause(
        val kind: String,
        val stepIndex: Int?,
        val headline: String,
        val detail: String,
        val fix: String,
    )

    fun wireText(value: String?, max: Int = MAX_TEXT): String =
        TracePrivacy.clean(value.orEmpty()).replace(redactedAssignment) { "${it.groupValues[1]} [redacted]" }.take(max)

    fun wireStatus(status: String): String = when (status.uppercase()) {
        "COMPLETED" -> "completed"
        "FAILED" -> "failed"
        "CANCELLED" -> "cancelled"
        "SUSPENDED" -> "suspended"
        "RUNNING" -> "running"
        else -> "failed"
    }

    fun steps(events: List<AiTraceEvent>): List<Step> {
        if (events.isEmpty()) return emptyList()
        val groups = mutableListOf<MutableList<AiTraceEvent>>()
        events.forEach { event ->
            if (groups.isEmpty() || event.kind in BOUNDARY) groups += mutableListOf<AiTraceEvent>()
            groups.last() += event
        }
        return groups.take(MAX_STEPS).mapIndexed { index, group -> step(index, group) }
    }

    private fun step(index: Int, group: List<AiTraceEvent>): Step {
        val head = group.firstOrNull { it.kind in BOUNDARY || it.kind == "DECISION" } ?: group.first()
        val failed = group.any { (it.kind in ACTION_RESULTS && it.ok == false) || it.kind in TERMINAL_FAILURES }
        val unverified = group.any {
            (it.kind == "VERIFICATION" && it.ok == false) || (it.kind == "VERIFY" && it.code in UNVERIFIED_CODES)
        }
        val recovery = group.lastOrNull {
            (it.kind == "RECOVERY_CLASSIFIED" && it.code != "progress.continue") || it.kind == "REPLAN" || it.kind == "RECOVERY_SELECTED"
        }
        val verification = group.lastOrNull { it.kind == "VERIFICATION" || it.kind == "VERIFY" }
        val outcome = when {
            failed -> StepOutcome.FAILED
            unverified -> StepOutcome.UNVERIFIED
            recovery != null -> StepOutcome.RECOVERED
            group.any { it.ok == true } -> StepOutcome.OK
            else -> StepOutcome.INFO
        }
        return Step(
            index = index,
            startedAt = group.first().timestampMs,
            endedAt = group.last().timestampMs,
            title = wireText(if (index == 0 && head.kind == "START") "Starting the task" else head.displayText, 200),
            action = detailField(group, "action") ?: head.code?.takeIf { head.kind in BOUNDARY },
            pageId = detailField(group, "page"),
            outcome = outcome,
            verification = verification?.let { (it.code ?: it.displayText).take(80) },
            recovery = recovery?.let { (it.code ?: it.displayText).take(80) },
            vision = group.any { it.kind == "VISION" || it.kind == "VISION_ESCALATION" },
            events = group,
        )
    }

    private fun detailField(group: List<AiTraceEvent>, key: String): String? =
        group.firstNotNullOfOrNull { event ->
            Regex("(?:^|\\s)$key=([^\\s·]+)").find(event.detail.orEmpty())?.groupValues?.get(1)?.take(120)
        }

    /** Null for a finished or still-running run. */
    fun causeOfDeath(session: AiTraceSession, events: List<AiTraceEvent>, steps: List<Step> = steps(events)): Cause? {
        val status = session.status.uppercase()
        if (status == "COMPLETED" || status == "RUNNING") return null
        val stepOf = { event: AiTraceEvent? ->
            event?.let { target -> steps.firstOrNull { step -> step.events.any { it.id == target.id } }?.index }
        }
        val text = { event: AiTraceEvent -> "${event.displayText} ${event.code.orEmpty()} ${event.detail.orEmpty()}" }

        if (status == "CANCELLED" || events.any { it.kind == "CANCELLED" }) {
            val human = events.lastOrNull { text(it).contains("HUMAN_HAS_CONTROL") || text(it).contains("take_human") }
            return if (human != null) {
                cause("human-took-control", stepOf(human), "You took the phone", human, "Give control back to Cyclone and ask again.")
            } else {
                cause("cancelled", stepOf(events.lastOrNull()), "Stopped by you", events.lastOrNull(), "Nothing to fix; ask again when ready.")
            }
        }

        val lastSuspend = events.indexOfLast { it.kind == "GATE_SUSPEND" }
        val resumed = lastSuspend >= 0 && events.drop(lastSuspend + 1).any { it.kind == "GATE_RESUME" }
        if (status == "SUSPENDED" || (lastSuspend >= 0 && !resumed)) {
            val gate = events.getOrNull(lastSuspend)
            return if (gate != null && secretWall.containsMatchIn(text(gate))) {
                cause("needs-secret", stepOf(gate), "Stopped at a login wall", gate, "Set the password slot for this app on the phone, then ask again.")
            } else {
                cause("gate", stepOf(gate), "Waiting for your approval on the phone", gate, "Approve or decline on the phone. GATE owns pay, send, delete and permissions.")
            }
        }

        events.lastOrNull { it.kind == "HARD_BLOCKER" }?.let { blocker ->
            val t = text(blocker)
            return when {
                secretWall.containsMatchIn(t) ->
                    cause("needs-secret", stepOf(blocker), "Stopped at a login wall", blocker, "Set the password slot for this app on the phone, then ask again.")
                t.contains("PHONE_LOCKED", ignoreCase = true) || t.contains("locked", ignoreCase = true) ->
                    cause("transport", stepOf(blocker), "The phone was locked", blocker, "Unlock the phone. Cyclone never unlocks it for you.")
                t.contains("accessibility", ignoreCase = true) || t.contains("overlay", ignoreCase = true) ->
                    cause("transport", stepOf(blocker), "Cyclone lost sight of the screen", blocker, "Check Cyclone's accessibility service on the phone.")
                else -> cause("blocked", stepOf(blocker), "A hard blocker stopped the run", blocker, "Open the step before it to see what the screen showed.")
            }
        }

        events.lastOrNull { it.kind == "NON_CONVERGENCE" }?.let { stop ->
            val code = stop.code.orEmpty()
            return when {
                code == "convergence.task_timeout" ->
                    cause("timeout", stepOf(stop), "Ran out of time", stop, "Look for loops in the last steps; a mapped route would make this faster.")
                code == "convergence.repeated_action" ->
                    cause("unchanged", stepOf(stop), "The same action kept changing nothing", stop, "Inspect the target of the repeated step; the control may not do what the map says.")
                code == "convergence.stale_target" ->
                    cause("element-not-found", stepOf(stop), "Couldn't find the control on screen", stop, "Teach this door, or remap the room: the control moved or changed.")
                code == "convergence.backtrack" ->
                    cause("wrong-room", stepOf(stop), "Kept ending up on the wrong screen", stop, "Remap this room; a door leads somewhere else now.")
                code.startsWith("completion.") || code == "convergence.mutations_without_verified_progress" ->
                    cause("verification-failed", stepOf(stop), "Couldn't prove the goal was done", stop, "Open the last verification step to see what the screen was missing.")
                else ->
                    cause("model-gave-up", stepOf(stop), "The model stopped making progress", stop, "Open the steps before this one; a known route would let Cyclone skip the guessing.")
            }
        }

        events.lastOrNull { it.kind == "ACTION_REJECTED" && (text(it).contains("HUMAN_HAS_CONTROL") || text(it).contains("PHONE_LOCKED")) }?.let { rejected ->
            return if (text(rejected).contains("HUMAN_HAS_CONTROL")) {
                cause("human-took-control", stepOf(rejected), "You had the phone", rejected, "Give control back to Cyclone and ask again.")
            } else {
                cause("transport", stepOf(rejected), "The phone was locked", rejected, "Unlock the phone. Cyclone never unlocks it for you.")
            }
        }

        events.lastOrNull { it.kind == "ERROR" || it.code.orEmpty().startsWith("provider.") || it.kind == "PROVIDER_PHASE" && it.ok == false }?.let { error ->
            return cause("provider-error", stepOf(error), "The model provider failed", error, "Check the model and API key in Settings on the phone, then ask again.")
        }

        val lastFailed = steps.lastOrNull { it.outcome == StepOutcome.FAILED || it.outcome == StepOutcome.UNVERIFIED }
        return Cause(
            kind = "unknown",
            stepIndex = lastFailed?.index ?: steps.lastOrNull()?.index,
            headline = "The run stopped",
            detail = wireText(session.result),
            fix = if (lastFailed != null) "Start with the last failed step." else "Open the last step to see where it ended.",
        )
    }

    private fun cause(kind: String, stepIndex: Int?, headline: String, evidence: AiTraceEvent?, fix: String) = Cause(
        kind = kind,
        stepIndex = stepIndex,
        headline = headline,
        detail = wireText(listOfNotNull(evidence?.displayText, evidence?.code, evidence?.detail?.substringAfter("reason=", ""))
            .filter { it.isNotBlank() }.distinct().joinToString(" · ")),
        fix = fix,
    )

    fun summaryJson(session: AiTraceSession, events: List<AiTraceEvent>): JSONObject {
        val steps = steps(events)
        return summary(session, events, steps, causeOfDeath(session, events, steps))
    }

    fun detailJson(session: AiTraceSession, events: List<AiTraceEvent>): JSONObject {
        val steps = steps(events)
        val cause = causeOfDeath(session, events, steps)
        return summary(session, events, steps, cause)
            .put("result", wireText(session.result, 1_500))
            .put("stepsTruncated", events.isNotEmpty() && steps.size >= MAX_STEPS)
            .put("steps", JSONArray(steps.map(::stepJson)))
    }

    private fun summary(session: AiTraceSession, events: List<AiTraceEvent>, steps: List<Step>, cause: Cause?): JSONObject {
        val metrics = AgentRunDiagnosticV39.metrics(events)
        val ended = session.endedAt
        return JSONObject()
            .put("runId", session.id.take(120))
            .put("goal", wireText(session.goal, 500))
            .put("model", session.model.take(120))
            .put("status", wireStatus(session.status))
            .put("startedAt", session.startedAt)
            .put("endedAt", ended ?: JSONObject.NULL)
            .put("durationMs", ((ended ?: events.lastOrNull()?.timestampMs ?: session.startedAt) - session.startedAt).coerceAtLeast(0L))
            .put("decisions", session.decisions)
            .put("stepCount", steps.size)
            .put("metrics", JSONObject()
                .put("toolCalls", metrics.toolCalls)
                .put("toolFailures", metrics.toolFailures)
                .put("verificationFailures", metrics.verificationFailures)
                .put("recoveries", metrics.recoveries)
                .put("visionChecks", metrics.visionChecks)
                .put("verifiedActions", metrics.verifiedActions))
            .put("cause", cause?.let(::causeJson) ?: JSONObject.NULL)
    }

    private fun causeJson(cause: Cause): JSONObject = JSONObject()
        .put("kind", cause.kind)
        .put("stepIndex", cause.stepIndex ?: JSONObject.NULL)
        .put("headline", cause.headline)
        .put("detail", cause.detail)
        .put("fix", cause.fix)

    private fun stepJson(step: Step): JSONObject = JSONObject()
        .put("index", step.index)
        .put("startedAt", step.startedAt)
        .put("endedAt", step.endedAt)
        .put("title", step.title)
        .put("action", step.action?.let { wireText(it, 120) } ?: JSONObject.NULL)
        .put("pageId", step.pageId ?: JSONObject.NULL)
        .put("outcome", step.outcome.wire)
        .put("verification", step.verification?.let { wireText(it, 80) } ?: JSONObject.NULL)
        .put("recovery", step.recovery?.let { wireText(it, 80) } ?: JSONObject.NULL)
        .put("vision", step.vision)
        .put("eventsTruncated", step.events.size > MAX_EVENTS_PER_STEP)
        .put("events", JSONArray(step.events.take(MAX_EVENTS_PER_STEP).map { event ->
            JSONObject()
                .put("at", event.timestampMs)
                .put("kind", event.kind.take(40))
                .put("text", wireText(event.displayText))
                .put("code", event.code?.take(120) ?: JSONObject.NULL)
                .put("ok", event.ok ?: JSONObject.NULL)
                .put("detail", event.detail?.let { wireText(it, MAX_DETAIL) } ?: JSONObject.NULL)
        }))
}
