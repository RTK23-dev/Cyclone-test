package com.cyclone.mobile.ai

import com.cyclone.mobile.gateway.GatewayProtocol
import com.cyclone.mobile.gateway.GatewayProtocolException
import com.cyclone.mobile.gateway.GatewayV5RunsAdapter
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RunInsightTest {
    private var clock = 1_000L
    private val run = "ai-run-1"

    private fun ev(kind: String, text: String = kind.lowercase(), code: String? = null, ok: Boolean? = null, detail: String? = null) =
        AiTraceEvent("evt-${clock}", run, clock.also { clock += 500 }, kind, text, code, ok, detail)

    private fun session(status: String, result: String = "", goal: String = "find the dm of Louella") =
        AiTraceSession(run, goal, "model-x", status, 1_000L, if (status == "RUNNING") null else clock, result, 3)

    /** Start, open Facebook, tap Messages, verify. */
    private fun opening(): MutableList<AiTraceEvent> = mutableListOf(
        ev("START", "Starting task", "task.start"),
        ev("TOOL_REQUESTED", "Opening the app needed for this task", "tool.requested", detail = "action=open_app:com.facebook.katana · page=aaaaaaaaaaaaaaaa"),
        ev("ANDROID_EXECUTION", "Opened Facebook", "executor.ok", ok = true, detail = "executorInvoked=true"),
        ev("VERIFICATION", "Screen changed", "verify.progress", ok = true),
        ev("TOOL_REQUESTED", "Opening the selected control: Messages", "tool.requested", detail = "action=click:Messages · page=bbbbbbbbbbbbbbbb"),
        ev("ANDROID_EXECUTION", "Tapped Messages", "executor.ok", ok = true),
    )

    @After
    fun reset() {
        GatewayV5RunsAdapter.sessions = { emptyList() }
        GatewayV5RunsAdapter.session = { null }
        GatewayV5RunsAdapter.events = { emptyList() }
    }

    @Test
    fun stepsFollowDecisionTurnsWithOutcomes() {
        val events = opening().apply { add(ev("VERIFICATION", "Expected screen missing", "verify.failed", ok = false)) }
        val steps = RunInsight.steps(events)
        assertEquals(3, steps.size)
        assertEquals("Starting the task", steps[0].title)
        assertEquals("open_app:com.facebook.katana", steps[1].action)
        assertEquals("aaaaaaaaaaaaaaaa", steps[1].pageId)
        assertEquals(RunInsight.StepOutcome.OK, steps[1].outcome)
        assertEquals(RunInsight.StepOutcome.UNVERIFIED, steps[2].outcome)
    }

    @Test
    fun completedAndRunningRunsHaveNoCauseOfDeath() {
        val events = opening().apply { add(ev("DONE", "Task finished", "task.finish", ok = true)) }
        assertNull(RunInsight.causeOfDeath(session("COMPLETED"), events))
        assertNull(RunInsight.causeOfDeath(session("RUNNING"), opening()))
    }

    @Test
    fun loginWallIsNeedsSecretOnTheRightStep() {
        val events = opening().apply {
            add(ev("GATE_SUSPEND", "Facebook wants a password. Waiting for the Secrets Card on the phone.", "gate.need_secret"))
        }
        val cause = RunInsight.causeOfDeath(session("SUSPENDED"), events)!!
        assertEquals("needs-secret", cause.kind)
        assertEquals(2, cause.stepIndex)
        assertTrue(cause.fix.contains("password slot"))

        val hard = opening().apply { add(ev("HARD_BLOCKER", "hard blocker", "Log in to continue")) }
        assertEquals("needs-secret", RunInsight.causeOfDeath(session("FAILED"), hard)!!.kind)
    }

    @Test
    fun gateApprovalThatWasResumedIsNotTheCause() {
        val events = opening().apply {
            add(ev("GATE_SUSPEND", "Approve sending this message?", "gate.approve"))
            add(ev("GATE_RESUME", "Approved", "gate.resume"))
            add(ev("NON_CONVERGENCE", "non convergence", "convergence.task_timeout", ok = false))
        }
        assertEquals("timeout", RunInsight.causeOfDeath(session("FAILED"), events)!!.kind)
        val waiting = opening().apply { add(ev("GATE_SUSPEND", "Approve sending this message?", "gate.approve")) }
        assertEquals("gate", RunInsight.causeOfDeath(session("SUSPENDED"), waiting)!!.kind)
    }

    @Test
    fun nonConvergenceCodesMapToPlainCauses() {
        val expected = mapOf(
            "convergence.repeated_action" to "unchanged",
            "convergence.stale_target" to "element-not-found",
            "convergence.backtrack" to "wrong-room",
            "convergence.mutations_without_verified_progress" to "verification-failed",
            "completion.ambiguous_after_recheck" to "verification-failed",
            "convergence.malformed_model" to "model-gave-up",
            "classifier.non_convergence" to "model-gave-up",
        )
        for ((code, kind) in expected) {
            val events = opening().apply { add(ev("NON_CONVERGENCE", "non convergence", code, ok = false)) }
            val cause = RunInsight.causeOfDeath(session("FAILED"), events)!!
            assertEquals(code, kind, cause.kind)
            assertEquals(code, 2, cause.stepIndex)
        }
    }

    @Test
    fun cancelledHumanAndProviderFailuresAreNamed() {
        val cancelled = opening().apply { add(ev("CANCELLED", "cancelled", "Request stopped by you.")) }
        assertEquals("cancelled", RunInsight.causeOfDeath(session("CANCELLED"), cancelled)!!.kind)
        val human = opening().apply {
            add(ev("ACTION_REJECTED", "Rejected", "HUMAN_HAS_CONTROL", ok = false))
            add(ev("CANCELLED", "cancelled"))
        }
        assertEquals("human-took-control", RunInsight.causeOfDeath(session("CANCELLED"), human)!!.kind)
        val locked = opening().apply { add(ev("ACTION_REJECTED", "Rejected", "PHONE_LOCKED", ok = false)) }
        assertEquals("transport", RunInsight.causeOfDeath(session("FAILED"), locked)!!.kind)
        val provider = opening().apply { add(ev("ERROR", "The model provider returned 502", "provider.http_502", ok = false)) }
        assertEquals("provider-error", RunInsight.causeOfDeath(session("FAILED"), provider)!!.kind)
        val unknown = opening().apply { add(ev("ANDROID_EXECUTION", "Tap failed", "executor.failed", ok = false)) }
        val cause = RunInsight.causeOfDeath(session("FAILED", "Couldn't finish"), unknown)!!
        assertEquals("unknown", cause.kind)
        assertEquals(2, cause.stepIndex)
    }

    @Test
    fun wireTextNeverCarriesAKeyValueSecretForm() {
        val events = opening().apply {
            add(ev("TOOL_REQUESTED", "Filling the requested field without storing its contents", detail = "password=[REDACTED] token: abc spin: 3"))
            add(ev("NON_CONVERGENCE", "non convergence", "convergence.task_timeout", ok = false))
        }
        val text = RunInsight.detailJson(session("FAILED", "api_key=sk-live-123"), events).toString()
        val inlineSecret = Regex("(?i)(password|passcode|passwd|pin|otp|token|secret|api[_-]?key|authorization|cookie|cvv|credential)\\s*[:=]")
        assertFalse(text, inlineSecret.containsMatchIn(text))
        assertFalse(text.contains("sk-live-123"))
    }

    @Test
    fun runsOpsListAndGetFromTheTrace() {
        val failed = opening().apply { add(ev("NON_CONVERGENCE", "non convergence", "convergence.stale_target", ok = false)) }
        val ok = session("COMPLETED").copy(id = "ai-run-2", goal = "open clock")
        GatewayV5RunsAdapter.sessions = { listOf(session("FAILED"), ok) }
        GatewayV5RunsAdapter.session = { id -> if (id == run) session("FAILED") else null }
        GatewayV5RunsAdapter.events = { id -> if (id == run) failed else emptyList() }

        val all = GatewayV5RunsAdapter.dispatch("runs.list", JSONObject()).getJSONArray("runs")
        assertEquals(2, all.length())
        val first = all.getJSONObject(0)
        assertEquals("failed", first.getString("status"))
        assertEquals("element-not-found", first.getJSONObject("cause").getString("kind"))
        assertEquals(3, first.getInt("stepCount"))
        assertTrue(all.getJSONObject(1).isNull("cause"))

        val onlyFailed = GatewayV5RunsAdapter.dispatch("runs.list", JSONObject().put("filter", "failed")).getJSONArray("runs")
        assertEquals(1, onlyFailed.length())

        val detail = GatewayV5RunsAdapter.dispatch("runs.get", JSONObject().put("runId", run))
        val steps = detail.getJSONArray("steps")
        assertEquals(3, steps.length())
        assertEquals("failed", steps.getJSONObject(2).getString("outcome"))
        assertEquals("NON_CONVERGENCE", steps.getJSONObject(2).getJSONArray("events").getJSONObject(2).getString("kind"))
    }

    @Test
    fun runsOpsValidateArgumentsAndAreReadOnly() {
        fun code(args: JSONObject, op: String = "runs.list") =
            (runCatching { GatewayV5RunsAdapter.dispatch(op, args) }.exceptionOrNull() as GatewayProtocolException).code
        assertEquals("INVALID_REQUEST", code(JSONObject().put("limit", 0)))
        assertEquals("INVALID_REQUEST", code(JSONObject().put("filter", "everything")))
        assertEquals("INVALID_REQUEST", code(JSONObject().put("sql", "drop")))
        assertEquals("INVALID_REQUEST", code(JSONObject().put("runId", "../etc"), "runs.get"))
        assertEquals("RUN_NOT_FOUND", code(JSONObject().put("runId", "ai-missing"), "runs.get"))
        assertTrue(setOf("runs.list", "runs.get").all { it in GatewayProtocol.operations && it in GatewayProtocol.legacyReadOnlyOperations })
    }
}
