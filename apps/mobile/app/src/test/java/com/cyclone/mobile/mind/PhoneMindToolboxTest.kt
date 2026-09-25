package com.cyclone.mobile.mind

import com.cyclone.mobile.agent.contract.AgentActionEnvelope
import com.cyclone.mobile.agent.contract.AgentElementCandidate
import com.cyclone.mobile.agent.contract.AgentFailure
import com.cyclone.mobile.agent.contract.AgentFailureClass
import com.cyclone.mobile.agent.contract.AgentFailureLayer
import com.cyclone.mobile.agent.contract.AgentInspectResult
import com.cyclone.mobile.agent.contract.AgentKnowledgeResult
import com.cyclone.mobile.agent.contract.AgentLearningResult
import com.cyclone.mobile.agent.contract.AgentObservationResult
import com.cyclone.mobile.agent.contract.AgentPageCard
import com.cyclone.mobile.agent.contract.AgentScreenshotResult
import com.cyclone.mobile.agent.contract.AgentSearchResult
import com.cyclone.mobile.agent.contract.AgentSemanticVerification
import com.cyclone.mobile.agent.contract.AgentStateDelta
import com.cyclone.mobile.agent.contract.AgentVerificationStatus
import com.cyclone.mobile.agent.tools.CycloneAgentEnvironmentApi
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneMindToolboxTest {
    data class Control(val key: String, val label: String, val role: String = "button", val editable: Boolean = false, val password: Boolean = false)

    class FakeScreen(val packageName: String, val controls: List<Control>, val text: List<String> = emptyList(),
        val values: Map<String, String> = emptyMap(), val treeUseful: Boolean = true, val shortlist: Int = 36)

    class FakeEnv(var screen: FakeScreen) : CycloneAgentEnvironmentApi {
        var observations = 0
        var visible: String? = null
        val acts = mutableListOf<Pair<String, JSONObject>>()
        var nextFailures = ArrayDeque<AgentFailureClass>()
        var onAct: (String, JSONObject) -> Unit = { _, _ -> }
        var images = 0
        private var lastAll: List<AgentElementCandidate> = emptyList()
        private var lastId: String? = null

        override fun allControls(): List<AgentElementCandidate> = if (visible != null && visible == lastId) lastAll else emptyList()
        override fun fieldValue(elementId: String): String? =
            if (visible != null && elementId.contains(":$visible:")) screen.values[elementId.substringAfterLast(':')] else null

        fun card(): AgentPageCard {
            observations++
            val id = "obs$observations"
            visible = id
            val all = screen.controls.map { c ->
                AgentElementCandidate("semantic:$id:${c.key}", id, c.label, c.label.lowercase(), c.role, "semantic", 0.0,
                    JSONObject().put("controlKey", c.key).put("editable", c.editable).put("password", c.password)
                        .put("bounds", JSONObject().put("left", 10).put("top", 10).put("right", 200).put("bottom", 80)))
            }
            lastAll = all
            lastId = id
            return AgentPageCard(
                treeUseful = screen.treeUseful,
                observationId = id, generation = observations.toLong(), actionable = true, capturedAtMs = 0,
                packageName = screen.packageName, activity = null, pageKey = "p", structuralKey = "s", contentKey = "c",
                accessibilityFingerprint = "f$observations", pageSummary = JSONObject(),
                pageText = JSONObject().put("lines", JSONArray().also { lines -> screen.text.forEach { lines.put(JSONObject().put("text", it)) } }),
                pageEvidence = JSONObject().put("captureWidth", 1080).put("captureHeight", 2400),
                controls = all.take(screen.shortlist),
                nextHopHints = JSONArray(),
            )
        }

        override fun observe(goal: String) = AgentObservationResult(page = card())
        override fun observeWithImage(goal: String) = AgentObservationResult(page = card().also { images++ }, image = JSONObject().put("pngBase64", "QUJD").put("width", 540).put("height", 1200))
        override fun locate(goal: String) = AgentSearchResult(query = goal, goal = goal)
        override fun search(query: String, goal: String): AgentSearchResult {
            val page = card()
            return AgentSearchResult(page = page, observationId = page.observationId, query = query, goal = goal,
                candidates = page.controls.filter { it.label.contains(query, true) })
        }
        override fun inspect(elementId: String) = AgentInspectResult(elementId = elementId)
        override fun screenshot(goal: String) = AgentScreenshotResult(goal)
        override fun history(): List<AgentActionEnvelope> = emptyList()
        override fun invalidateObservation() { visible = null }
        override fun brainRecall(goal: String) = AgentKnowledgeResult(goal, JSONObject().put("fact", "owner prefers Dutch"))
        override fun knownRoutes(goal: String) = AgentKnowledgeResult(goal)

        override fun act(tool: String, params: JSONObject, goal: String): AgentActionEnvelope {
            acts += tool to JSONObject(params.toString())
            if (visible == null) return envelope(tool, AgentFailureClass.STALE_OBSERVATION)
            params.optString("elementId").takeIf { it.isNotBlank() }?.let { id ->
                if (!id.contains(":$visible:")) return envelope(tool, AgentFailureClass.STALE_OBSERVATION)
            }
            visible = null
            val failure = nextFailures.removeFirstOrNull() ?: AgentFailureClass.NONE
            if (failure == AgentFailureClass.NONE) onAct(tool, params)
            return envelope(tool, failure)
        }

        private fun envelope(tool: String, failure: AgentFailureClass) = AgentActionEnvelope(
            tool = tool, goal = "", androidExecutionOk = failure == AgentFailureClass.NONE, executorReportedOk = failure == AgentFailureClass.NONE,
            verification = AgentSemanticVerification(AgentVerificationStatus.PASSED, true, true),
            before = null, after = null, pageChanged = failure == AgentFailureClass.NONE,
            delta = AgentStateDelta(false, false, false, emptyList(), false, ""),
            errorClass = failure, failureLayer = if (failure == AgentFailureClass.NONE) AgentFailureLayer.NONE else AgentFailureLayer.POLICY,
            retryable = false, semanticSuccessClaimed = false, beforeObservationId = null, afterObservationId = null,
            observationGeneration = null, learning = AgentLearningResult(false, ""),
            safeMessage = if (failure == AgentFailureClass.NONE) null else "blocked: $failure",
        )
    }

    class FakeOwner : MindOwnerPort {
        var approval = MindApproval.APPROVED
        var answer: String? = "blue"
        var secret = MindSecretOutcome.FILLED
        val asked = mutableListOf<String>()
        val secretSlots = mutableListOf<String>()
        var planned: List<MindPlanStep> = emptyList()
        override fun ask(question: String, choices: List<String>, timeoutMs: Long): MindOwnerReply {
            asked += question
            return answer?.let { MindOwnerReply(true, it, 30_000) } ?: MindOwnerReply(false, waitedMs = timeoutMs)
        }
        override fun awaitApproval(action: String, timeoutMs: Long) = MindApprovalReply(approval, 12_000)
        override fun fillSecret(page: AgentPageCard, target: MindRef, slot: String, reason: String, timeoutMs: Long): MindSecretReply {
            secretSlots += slot
            return MindSecretReply(secret, 40_000)
        }
        override fun awaitControl(timeoutMs: Long) = MindOwnerReply(true, waitedMs = 5_000)
        override fun plan(steps: List<MindPlanStep>) { planned = steps }
    }

    private val device = object : MindDevicePort {
        override fun apps() = listOf(MindApp("com.google.android.deskclock", "Clock"), MindApp("com.android.chrome", "Chrome"),
            MindApp("com.instagram.android", "Instagram"), MindApp("com.google.android.gm", "Gmail"), MindApp("com.google.android.apps.maps", "Maps"),
            MindApp("com.example.mapsplus", "Maps Plus"))
        override fun now() = "Thursday 14:05"
        override fun device() = "Test phone"
        override fun sleep(ms: Long) {}
    }

    private val login = FakeScreen("com.android.chrome", listOf(
        Control("email", "Email", "edit_text", editable = true),
        Control("pass", "Password", "edit_text", editable = true, password = true),
        Control("login", "Log in"),
    ), listOf("Welcome to Facebook", "Log in"))

    private fun call(name: String, args: String = "{}") = MindToolCall("c", name, args)
    private fun MindToolbox.run(name: String, args: String = "{}") = execute(call(name, args), JSONObject(args))

    @Test fun screenRendersRefsTextAndNoIds() {
        val env = FakeEnv(login)
        val box = PhoneMindToolbox(env, FakeOwner(), device, "goal")
        val text = box.run("screen_read").text
        assertTrue(text.contains("Screen: Chrome (com.android.chrome)"))
        assertTrue(text.contains("e1 text field \"Email\""))
        assertTrue(text.contains("e2 password field \"Password\""))
        assertTrue(text.contains("Welcome to Facebook"))
        assertFalse("element ids never reach the model", text.contains("semantic:"))
    }

    @Test fun refsStayStableAcrossReobservation() {
        val env = FakeEnv(login)
        val box = PhoneMindToolbox(env, FakeOwner(), device, "goal")
        box.run("screen_read")
        val typed = box.run("type_text", """{"ref":"e1","text":"me@example.com"}""")
        assertTrue(typed.ok)
        assertFalse("typing does not end the turn", typed.changedScreen)
        assertTrue(typed.text.contains("e3 button \"Log in\""))
        val tap = box.run("tap", """{"ref":"e3"}""")
        assertTrue(tap.ok)
        assertTrue(tap.changedScreen)
        assertEquals("phone.click", env.acts.last().first)
        assertTrue(env.acts.last().second.getString("elementId").endsWith(":login"))
    }

    @Test fun secretFieldsAreNeverTypedAndGoToTheVault() {
        val env = FakeEnv(login)
        val owner = FakeOwner()
        val box = PhoneMindToolbox(env, owner, device, "goal")
        box.run("screen_read")
        val refused = box.run("type_text", """{"ref":"e2","text":"hunter2"}""")
        assertFalse(refused.ok)
        assertTrue(refused.text.contains("vault_fill"))
        assertTrue(env.acts.isEmpty())
        val filled = box.run("vault_fill", """{"ref":"e2","what":"password","reason":"Sign in to Facebook"}""")
        assertTrue(filled.ok)
        assertEquals(listOf("password"), owner.secretSlots)
        assertEquals(40_000, filled.ownerWaitMs)
    }

    @Test fun gateWaitsForApprovalThenRetriesTheSameControl() {
        val env = FakeEnv(login)
        env.nextFailures.add(AgentFailureClass.GATE_REQUIRED)
        val box = PhoneMindToolbox(env, FakeOwner(), device, "goal")
        box.run("screen_read")
        val result = box.run("tap", """{"ref":"e3"}""")
        assertTrue(result.ok)
        assertEquals(2, env.acts.size)
        assertTrue(env.acts[1].second.getString("elementId").endsWith(":login"))
        assertEquals(12_000, result.ownerWaitMs)
    }

    @Test fun declinedApprovalIsReportedAndNotRetried() {
        val env = FakeEnv(login)
        env.nextFailures.add(AgentFailureClass.GATE_REQUIRED)
        val owner = FakeOwner().apply { approval = MindApproval.DECLINED }
        val box = PhoneMindToolbox(env, owner, device, "goal")
        box.run("screen_read")
        val result = box.run("tap", """{"ref":"e3"}""")
        assertFalse(result.ok)
        assertTrue(result.text.contains("declined"))
        assertEquals(1, env.acts.size)
    }

    @Test fun unknownRefIsExplained() {
        val box = PhoneMindToolbox(FakeEnv(login), FakeOwner(), device, "goal")
        box.run("screen_read")
        val result = box.run("tap", """{"ref":"e9"}""")
        assertFalse(result.ok)
        assertTrue(result.text.contains("not on the current screen"))
    }

    @Test fun actionsWithoutAPriorReadObserveFirst() {
        val env = FakeEnv(login)
        env.onAct = { tool, _ -> if (tool == "phone.set_timer") env.screen = FakeScreen("com.google.android.deskclock", listOf(Control("pause", "Pause")), listOf("4:59")) }
        val box = PhoneMindToolbox(env, FakeOwner(), device, "set a 5 minute timer")
        val result = box.run("set_timer", """{"minutes":5}""")
        assertTrue(result.ok)
        assertEquals(300, env.acts.single().second.getInt("seconds"))
        assertTrue(result.text.contains("Screen: Clock"))
        assertTrue(result.text.contains("4:59"))
    }

    @Test fun openAppResolvesNamesAndExplainsMissingOnes() {
        val env = FakeEnv(login)
        val box = PhoneMindToolbox(env, FakeOwner(), device, "goal")
        assertTrue(box.run("open_app", """{"app":"instagram"}""").ok)
        assertEquals("com.instagram.android", env.acts.last().second.getString("package"))
        val missing = box.run("open_app", """{"app":"TikTok"}""")
        assertFalse(missing.ok)
        assertTrue(missing.text.contains("market://details?id="))
        val ambiguous = box.run("open_app", """{"app":"map"}""")
        assertTrue(ambiguous.text.contains("Several installed apps"))
        assertTrue(box.run("open_app", """{"app":"Maps"}""").ok)
    }

    @Test fun linksAndSettingsAreValidated() {
        val env = FakeEnv(login)
        val box = PhoneMindToolbox(env, FakeOwner(), device, "goal")
        assertTrue(box.run("open_link", """{"url":"facebook.com"}""").ok)
        assertEquals("https://facebook.com", env.acts.last().second.getString("uri"))
        assertFalse(box.run("open_link", """{"url":"intent://evil"}""").ok)
        assertTrue(box.run("open_settings", """{"page":"wifi"}""").ok)
        assertTrue(box.run("open_settings", """{"page":"app_details","app":"Gmail"}""").ok)
        assertEquals("com.google.android.gm", env.acts.last().second.getString("app"))
        assertFalse(box.run("open_settings", """{"page":"root_shell"}""").ok)
    }

    @Test fun finishNeedsEvidenceAndRecordsTheFinalScreen() {
        val box = PhoneMindToolbox(FakeEnv(login), FakeOwner(), device, "goal")
        val rejected = box.run("task_finish", """{"summary":"done","evidence":""}""")
        assertNull(rejected.ending)
        val accepted = box.run("task_finish", """{"summary":"Timer running","evidence":"4:59 counting down"}""")
        assertEquals(MindEnding.COMPLETED, accepted.ending)
        assertTrue(accepted.evidence!!.contains("final screen"))
    }

    @Test fun ownerQuestionsAndPlans() {
        val owner = FakeOwner()
        val box = PhoneMindToolbox(FakeEnv(login), owner, device, "goal")
        val answer = box.run("owner_ask", """{"question":"Which account?","choices":["work","home"]}""")
        assertTrue(answer.text.contains("blue"))
        assertEquals(30_000, answer.ownerWaitMs)
        owner.answer = null
        assertFalse(box.run("owner_ask", """{"question":"Still there?"}""").ok)
        box.run("plan_update", """{"steps":[{"step":"Open Clock","status":"done"},{"step":"Start timer","status":"doing"}]}""")
        assertEquals(2, owner.planned.size)
        assertEquals("doing", owner.planned[1].status)
    }

    @Test fun findBindsExtraRefsAndRecallWorks() {
        val box = PhoneMindToolbox(FakeEnv(login), FakeOwner(), device, "goal")
        box.run("screen_read")
        val found = box.run("screen_find", """{"query":"log"}""")
        assertTrue(found.text.contains("e3 button \"Log in\""))
        assertTrue(box.run("recall", """{"topic":"language"}""").text.contains("owner prefers Dutch"))
    }

    @Test fun screenshotIsAttached() {
        val look = PhoneMindToolbox(FakeEnv(login), FakeOwner(), device, "goal").run("screen_look")
        assertNotNull(look.imageDataUrl)
        assertTrue(look.imageDataUrl!!.startsWith("data:image/png;base64,"))
    }

    @Test fun humanControlWaitsForHandBack() {
        val env = FakeEnv(login)
        env.nextFailures.add(AgentFailureClass.HUMAN_HAS_CONTROL)
        val box = PhoneMindToolbox(env, FakeOwner(), device, "goal")
        box.run("screen_read")
        val result = box.run("back")
        assertFalse(result.ok)
        assertTrue(result.text.contains("handed it back"))
        assertEquals(5_000, result.ownerWaitMs)
    }

    @Test fun everySpecIsExecutable() {
        val box = PhoneMindToolbox(FakeEnv(login), FakeOwner(), device, "goal")
        box.specs().forEach { spec ->
            val result = box.execute(call(spec.name), JSONObject())
            assertFalse("${spec.name} is wired", result.text.startsWith("ERROR: Unknown tool"))
        }
        assertEquals(box.specs().size, box.specs().map { it.name }.toSet().size)
    }

    @Test fun afterAFailedActionTheModelStillSeesTheScreen() {
        val env = FakeEnv(login)
        env.nextFailures.add(AgentFailureClass.EXECUTION_FAILED)
        val box = PhoneMindToolbox(env, FakeOwner(), device, "goal")
        box.run("screen_read")
        val result = box.run("tap", """{"ref":"e3"}""")
        assertFalse(result.ok)
        assertTrue(result.text.startsWith("Failed"))
        assertTrue(result.text.contains("Controls:"))
    }

    @Test fun policyFailuresAreNotRetried() {
        val env = FakeEnv(login)
        env.nextFailures.add(AgentFailureClass.POLICY_DENIED)
        val box = PhoneMindToolbox(env, FakeOwner().apply { approval = MindApproval.NOT_PENDING }, device, "goal")
        box.run("screen_read")
        val result = box.run("tap", """{"ref":"e3"}""")
        assertTrue(result.text.startsWith("Not allowed"))
        assertEquals(1, env.acts.size)
        AgentFailure(AgentFailureClass.NONE, AgentFailureLayer.NONE, false, "")
    }

    @Test fun interceptorGateWaitsForApprovalLikeAPolicyGate() {
        val env = FakeEnv(login)
        env.nextFailures.add(AgentFailureClass.POLICY_DENIED)
        val box = PhoneMindToolbox(env, FakeOwner(), device, "goal")
        box.run("screen_read")
        val result = box.run("tap", """{"ref":"e3"}""")
        assertTrue(result.ok)
        assertEquals(2, env.acts.size)
    }

    @Test fun tapPointNeedsAScreenshotAndScalesToTheScreen() {
        val env = FakeEnv(login)
        val box = PhoneMindToolbox(env, FakeOwner(), device, "goal")
        assertFalse(box.run("tap_point", """{"x":10,"y":10}""").ok)
        val look = box.run("screen_look")
        assertTrue(look.text.contains("540×1200"))
        assertFalse("outside the screenshot", box.run("tap_point", """{"x":600,"y":10}""").ok)
        assertTrue(box.run("tap_point", """{"x":100,"y":300}""").ok)
        val (tool, params) = env.acts.last()
        assertEquals("phone.tap_point", tool)
        assertEquals(200, params.getInt("x"))
        assertEquals(600, params.getInt("y"))
        assertFalse("points belong to one screenshot", box.run("tap_point", """{"x":100,"y":300}""").ok)
    }

    @Test fun rememberAndForgetAcrossMissions() {
        val memory = MindMemory(java.nio.file.Files.createTempFile("memory", ".json").toFile())
        val first = PhoneMindToolbox(FakeEnv(login), FakeOwner(), device, "goal", memory = memory, missionId = "m1")
        assertTrue(first.run("remember", """{"fact":"The owner's work email is jan@example.com"}""").ok)
        assertFalse(first.run("remember", """{"fact":"Facebook password: hunter2"}""").ok)
        val second = PhoneMindToolbox(FakeEnv(login), FakeOwner(), device, "goal", memory = memory, missionId = "m2")
        assertTrue(second.run("recall", """{"topic":"work email"}""").text.contains("jan@example.com"))
        assertTrue(memory.digest().contains("[f1]"))
        assertTrue(second.run("forget", """{"id":"f1"}""").ok)
        assertTrue(memory.all().isEmpty())
    }

    @Test fun aLockedPhoneIsWaitedForNotFailed() {
        var locked = 3
        val lockedDevice = object : MindDevicePort by device {
            override fun blocker(): String? = if (locked-- > 0) "the phone is locked" else null
            override fun sleep(ms: Long) {}
        }
        val env = FakeEnv(login)
        val box = PhoneMindToolbox(env, FakeOwner(), lockedDevice, "goal")
        val result = box.run("screen_read")
        assertTrue(result.ok)
        assertTrue(result.text.startsWith("(The phone was the phone is locked"))
        assertTrue(result.ownerWaitMs > 0)
        locked = 0
        assertTrue("memory tools never wait", box.run("note", """{"text":"x"}""").ok)
    }

    @Test fun aPhoneThatStaysLockedEndsTheCallHonestly() {
        val lockedDevice = object : MindDevicePort by device {
            override fun blocker(): String = "the screen is off"
            override fun sleep(ms: Long) {}
        }
        val result = PhoneMindToolbox(FakeEnv(login), FakeOwner(), lockedDevice, "goal", ownerTimeoutMs = 5_000).run("tap", """{"ref":"e1"}""")
        assertFalse(result.ok)
        assertTrue(result.text.contains("still unavailable"))
    }

    @Test fun fieldValuesAreShownButSecretsNever() {
        val screen = FakeScreen("com.android.chrome", login.controls, values = mapOf("email" to "jan@example.com", "pass" to "hunter2"))
        val text = PhoneMindToolbox(FakeEnv(screen), FakeOwner(), device, "goal").run("screen_read").text
        assertTrue(text.contains("e1 text field \"Email\" = \"jan@example.com\""))
        assertTrue(text.contains("e2 password field \"Password\" (hidden)"))
        assertFalse(text.contains("hunter2"))
        val empty = PhoneMindToolbox(FakeEnv(login), FakeOwner(), device, "goal").run("screen_read").text
        assertTrue(empty.contains("\"Email\" (empty)"))
    }

    @Test fun theWholeScreenIsListedNotJustTheShortlist() {
        val many = FakeScreen("com.android.settings", (1..60).map { Control("k$it", "Setting $it") })
        val text = PhoneMindToolbox(FakeEnv(many), FakeOwner(), device, "goal").run("screen_read").text
        assertTrue(text.contains("e60 button \"Setting 60\""))
    }

    @Test fun screenshotsAreMarkedAndScaled() {
        var seen: List<MindMark> = emptyList()
        val marker = MindImageMarker { _, marks, width, height ->
            seen = marks
            assertEquals(1080, width)
            assertEquals(2400, height)
            MindImage("data:image/jpeg;base64,XYZ", 576, 1280)
        }
        val env = FakeEnv(login)
        val box = PhoneMindToolbox(env, FakeOwner(), device, "goal", marker = marker)
        val look = box.run("screen_look")
        assertEquals("data:image/jpeg;base64,XYZ", look.imageDataUrl)
        assertEquals(listOf("e1", "e2", "e3"), seen.map { it.ref })
        assertTrue(look.text.contains("576×1280"))
        box.run("tap_point", """{"x":288,"y":640}""")
        assertEquals(540, env.acts.last().second.getInt("x"))
        assertEquals(1200, env.acts.last().second.getInt("y"))
    }

    @Test fun screensAccessibilityCannotDescribeComeWithAPicture() {
        val game = FakeScreen("com.example.game", emptyList(), treeUseful = false)
        val env = FakeEnv(game)
        val read = PhoneMindToolbox(env, FakeOwner(), device, "goal").run("screen_read")
        assertNotNull(read.imageDataUrl)
        assertEquals(1, env.images)
        val normal = FakeEnv(login)
        PhoneMindToolbox(normal, FakeOwner(), device, "goal").run("screen_read")
        assertEquals(0, normal.images)
    }
}
