package com.cyclone.mobile.mind

import com.cyclone.mobile.PhoneSettingsPages
import com.cyclone.mobile.agent.contract.AgentActionEnvelope
import com.cyclone.mobile.agent.contract.AgentElementCandidate
import com.cyclone.mobile.agent.contract.AgentFailureClass
import com.cyclone.mobile.agent.contract.AgentPageCard
import com.cyclone.mobile.agent.tools.CycloneAgentEnvironmentApi
import com.cyclone.mobile.mind.MindToolSpec.Companion.array
import com.cyclone.mobile.mind.MindToolSpec.Companion.boolean
import com.cyclone.mobile.mind.MindToolSpec.Companion.integer
import com.cyclone.mobile.mind.MindToolSpec.Companion.objectSchema
import com.cyclone.mobile.mind.MindToolSpec.Companion.string
import org.json.JSONArray
import org.json.JSONObject

/**
 * The phone as a set of tools for the Mind. Every mutation goes through [CycloneAgentEnvironmentApi.act], which owns
 * observation freshness, policy and GATE, the canonical PhoneToolExecutor, settle and verification. After every action
 * the model is shown the new screen, so it always decides against what is really there.
 */
class PhoneMindToolbox(
    private val env: CycloneAgentEnvironmentApi,
    private val owner: MindOwnerPort,
    private val device: MindDevicePort,
    private val goal: String,
    private val cancelled: () -> Boolean = { false },
    private val ownerTimeoutMs: Long = 10 * 60_000L,
) : MindToolbox {
    private val refs = MindRefBook()
    private var screen: AgentPageCard? = null
    private var controlsById: Map<String, AgentElementCandidate> = emptyMap()
    private var fresh = false
    private var finishRejections = 0
    private var plan: List<MindPlanStep> = emptyList()

    val currentPlan: List<MindPlanStep> get() = plan
    val lastScreen: AgentPageCard? get() = screen

    override fun specs(): List<MindToolSpec> = SPECS

    override fun situation(): String {
        val observed = env.observe(goal)
        val page = observed.page ?: return "The screen could not be read yet (${observed.failure?.message ?: "unknown reason"})."
        bind(page)
        return "Time: ${device.now()}\nThe phone is currently ${MindScreen.brief(page, appLabel(page.packageName))}."
    }

    override fun execute(call: MindToolCall, arguments: JSONObject): MindToolResult = when (call.name) {
        "screen_read" -> read()
        "screen_look" -> look()
        "screen_find" -> find(arguments.optString("query"))
        "tap" -> onElement(arguments, "phone.click", "Tapped")
        "long_press" -> onElement(arguments, "phone.long_press", "Long-pressed")
        "type_text" -> typeText(arguments)
        "press_enter" -> onElement(arguments, "phone.submit_text", "Pressed Enter in", requireEditable = true)
        "scroll" -> scroll(arguments)
        "back" -> act("phone.back", JSONObject(), "Pressed Back")
        "home" -> act("phone.home", JSONObject(), "Went to the Home screen")
        "wait" -> waitFor(arguments)
        "open_app" -> openApp(arguments.optString("app"))
        "open_link" -> openLink(arguments.optString("url"))
        "open_settings" -> openSettings(arguments)
        "set_timer" -> setTimer(arguments)
        "set_alarm" -> setAlarm(arguments)
        "apps_list" -> appsList(arguments.optString("query"))
        "recall" -> recall(arguments.optString("topic").ifBlank { goal })
        "owner_ask" -> ownerAsk(arguments)
        "vault_fill" -> vaultFill(arguments)
        "plan_update" -> planUpdate(arguments)
        "note" -> MindToolResult("Noted.", "note: ${arguments.optString("text").take(160)}")
        "task_finish" -> finish(arguments)
        "task_give_up" -> giveUp(arguments)
        else -> MindToolResult.error("Unknown tool ${call.name}.")
    }

    // ---- seeing -------------------------------------------------------------------------------------------------

    private fun bind(page: AgentPageCard): List<MindRef> {
        screen = page
        controlsById = page.controls.associateBy { it.elementId }
        fresh = true
        return refs.bind(page)
    }

    private fun observeAndRender(header: String?, image: Boolean = false): MindToolResult {
        val observed = if (image) env.observeWithImage(goal) else env.observe(goal)
        val page = observed.page ?: return MindToolResult(
            listOfNotNull(header, "The screen could not be read: ${observed.failure?.message ?: "unknown reason"}.").joinToString("\n"),
            ok = false,
        )
        val bound = bind(page)
        val rendered = MindScreen.render(page, bound, appLabel(page.packageName), controlsById)
        val text = listOfNotNull(header, rendered).joinToString("\n\n")
        val brief = (header ?: "Read the screen") + " — " + MindScreen.brief(page, appLabel(page.packageName))
        val dataUrl = observed.image?.optString("pngBase64")?.takeIf { it.isNotBlank() }?.let { "data:image/png;base64,$it" }
        return MindToolResult(text, brief.take(200), imageDataUrl = dataUrl)
    }

    private fun read() = observeAndRender(null)

    private fun look(): MindToolResult {
        val result = observeAndRender("Screenshot of the current screen attached.", image = true)
        return if (result.imageDataUrl != null) result
        else result.copy(text = "A screenshot could not be taken; here is the text description.\n\n${result.text}")
    }

    private fun find(query: String): MindToolResult {
        if (query.isBlank()) return MindToolResult.error("query is required.")
        val found = env.search(query, goal)
        found.failure?.let { return MindToolResult.error("Search failed: ${it.message}") }
        val page = found.page
        if (page != null && page.observationId != refs.observationId) bind(page)
        val observationId = found.observationId ?: page?.observationId ?: return MindToolResult.error("Search returned no screen.")
        if (found.candidates.isEmpty()) return MindToolResult("Nothing matching \"$query\" on this screen. Try scrolling, or screen_look.",
            "find \"$query\": nothing")
        controlsById = controlsById + found.candidates.associateBy { it.elementId }
        val bound = refs.bindExtra(observationId, found.candidates)
        val lines = bound.joinToString("\n") { "  ${it.ref} ${MindScreen.describe(it, controlsById[it.elementId])}" }
        return MindToolResult("Matches for \"$query\":\n$lines", "find \"$query\": ${bound.size} matches")
    }

    // ---- acting -------------------------------------------------------------------------------------------------

    private fun target(arguments: JSONObject): Pair<MindRef?, MindToolResult?> {
        val raw = arguments.optString("ref")
        if (raw.isBlank()) return null to MindToolResult.error("ref is required (for example e3).")
        val ref = refs.resolve(raw) ?: return null to MindToolResult.error(
            "$raw is not on the current screen. Use a ref from the latest screen (screen_read shows it).")
        return ref to null
    }

    private fun onElement(arguments: JSONObject, tool: String, verb: String, requireEditable: Boolean = false): MindToolResult {
        val (ref, error) = target(arguments)
        if (ref == null) return error!!
        if (requireEditable && !ref.editable) return MindToolResult.error("${ref.ref} is not a text field.")
        return act(tool, JSONObject().put("elementId", ref.elementId), "$verb ${ref.ref} \"${ref.label}\"", ref)
    }

    private fun typeText(arguments: JSONObject): MindToolResult {
        val (ref, error) = target(arguments)
        if (ref == null) return error!!
        if (!arguments.has("text")) return MindToolResult.error("text is required.")
        if (ref.password || sensitive(ref.label)) return MindToolResult.error(
            "${ref.ref} \"${ref.label}\" is a secret field. Use vault_fill so the owner fills it through the Secrets Card.")
        val text = arguments.optString("text")
        val typed = act("phone.type", JSONObject().put("elementId", ref.elementId).put("value", text),
            "Typed ${text.length} characters into ${ref.ref} \"${ref.label}\"", ref, changesScreen = false)
        if (!typed.ok || !arguments.optBoolean("press_enter")) return typed
        val again = refs.resolve(ref.ref) ?: return typed.copy(text = typed.text + "\n\nEnter was not pressed: the field is gone.")
        return act("phone.submit_text", JSONObject().put("elementId", again.elementId), "Typed into ${ref.ref} and pressed Enter", again)
    }

    private fun scroll(arguments: JSONObject): MindToolResult {
        val direction = arguments.optString("direction", "down").lowercase()
        if (direction !in setOf("down", "up")) return MindToolResult.error("direction must be down or up.")
        val params = JSONObject().put("direction", if (direction == "down") "forward" else "backward")
        var label = "Scrolled $direction"
        if (arguments.optString("ref").isNotBlank()) {
            val (ref, error) = target(arguments)
            if (ref == null) return error!!
            params.put("elementId", ref.elementId)
            label += " in ${ref.ref}"
        }
        return act("phone.scroll", params, label)
    }

    private fun waitFor(arguments: JSONObject): MindToolResult {
        val seconds = arguments.optInt("seconds", 3).coerceIn(1, 30)
        val text = arguments.optString("until_text").trim()
        if (text.isNotBlank()) {
            if (!fresh) env.observe(goal).page?.let(::bind)
            fresh = false
            val result = env.act("phone.wait_for", JSONObject().put("condition", JSONObject().put("type", "text_contains").put("text", text))
                .put("timeoutMs", seconds * 1000L), goal)
            invalidate()
            val appeared = result.androidExecutionOk
            return observeAndRender(if (appeared) "\"$text\" appeared." else "\"$text\" did not appear within $seconds s.")
                .copy(changedScreen = true)
        }
        var left = seconds * 1000L
        while (left > 0 && !cancelled()) { device.sleep(minOf(left, 500L)); left -= 500L }
        return observeAndRender("Waited $seconds s.").copy(changedScreen = true)
    }

    private fun openApp(requested: String): MindToolResult {
        if (requested.isBlank()) return MindToolResult.error("app is required (a name or a package).")
        val apps = device.apps()
        val wanted = requested.trim().lowercase()
        val exact = apps.firstOrNull { it.packageName.equals(requested.trim(), true) }
            ?: apps.filter { it.label.equals(requested.trim(), true) }.singleOrNull()
        val app = exact ?: apps.filter { it.label.lowercase().contains(wanted) || wanted.contains(it.label.lowercase()) && it.label.length >= 3 }
            .let { matches -> matches.singleOrNull() ?: if (matches.size > 1) return MindToolResult(
                "Several installed apps match \"$requested\": ${matches.take(10).joinToString { "${it.label} (${it.packageName})" }}. " +
                    "Call open_app with the exact package.", "open_app \"$requested\": ambiguous", ok = false) else null }
        if (app == null) return MindToolResult(
            "\"$requested\" is not installed on this phone (or has no launcher icon). apps_list shows what is installed; " +
                "to install an app, open its Play Store page with open_link market://details?id=<package> or search the Play Store.",
            "open_app \"$requested\": not installed", ok = false)
        return act("phone.open_app", JSONObject().put("package", app.packageName), "Opened ${app.label}")
    }

    private fun openLink(raw: String): MindToolResult {
        var url = raw.trim()
        if (url.isBlank()) return MindToolResult.error("url is required.")
        if (!url.contains(":") ) url = "https://$url"
        val scheme = url.substringBefore(':').lowercase()
        if (scheme !in setOf("http", "https", "geo", "mailto", "tel", "sms", "market")) {
            return MindToolResult.error("Only http, https, geo, mailto, tel, sms and market links can be opened.")
        }
        return act("phone.launch_intent", JSONObject().put("uri", url), "Opened $url")
    }

    private fun openSettings(arguments: JSONObject): MindToolResult {
        val page = arguments.optString("page", "main").lowercase()
        val spec = PhoneSettingsPages.page(page) ?: return MindToolResult.error(
            "Unknown page. Choose one of: ${PhoneSettingsPages.pages.keys.joinToString()}.")
        val params = JSONObject().put("page", page)
        if (spec.needsPackage) {
            val app = resolvePackage(arguments.optString("app"))
                ?: return MindToolResult.error("This page needs app: the name or package of an installed app.")
            params.put("app", app)
        }
        return act("phone.open_settings", params, "Opened Settings › ${spec.description}")
    }

    private fun resolvePackage(value: String): String? {
        val wanted = value.trim()
        if (wanted.isBlank()) return null
        val apps = device.apps()
        return apps.firstOrNull { it.packageName.equals(wanted, true) }?.packageName
            ?: apps.firstOrNull { it.label.equals(wanted, true) }?.packageName
            ?: wanted.takeIf(PhoneSettingsPages::validPackage)
    }

    private fun setTimer(arguments: JSONObject): MindToolResult {
        val seconds = arguments.optInt("hours") * 3600 + arguments.optInt("minutes") * 60 + arguments.optInt("seconds")
        if (seconds !in 1..86_400) return MindToolResult.error("The timer must be between 1 second and 24 hours.")
        val params = JSONObject().put("seconds", seconds)
        arguments.optString("label").takeIf { it.isNotBlank() }?.let { params.put("label", it.take(60)) }
        return act("phone.set_timer", params, "Asked the clock app for a ${duration(seconds)} timer")
    }

    private fun setAlarm(arguments: JSONObject): MindToolResult {
        val hour = arguments.optInt("hour", -1)
        val minute = arguments.optInt("minute", -1)
        if (hour !in 0..23 || minute !in 0..59) return MindToolResult.error("hour 0-23 and minute 0-59 are required.")
        val params = JSONObject().put("hour", hour).put("minute", minute)
        arguments.optString("label").takeIf { it.isNotBlank() }?.let { params.put("label", it.take(60)) }
        return act("phone.set_alarm", params, "Asked the clock app for an alarm at %02d:%02d".format(hour, minute))
    }

    /**
     * Runs one action through the harness and shows the resulting screen. Handles the owner boundaries: approvals,
     * secret fields and the owner taking over.
     */
    private fun act(tool: String, params: JSONObject, done: String, ref: MindRef? = null, changesScreen: Boolean = true): MindToolResult {
        if (cancelled()) return MindToolResult("NOT RUN: the owner stopped the mission.", ok = false)
        if (!fresh) {
            // Mutations need the current observation in scope; refs survive the re-read by identity.
            env.observe(goal).page?.let(::bind)
            if (ref != null) {
                val again = refs.resolve(ref.ref)?.takeIf { it.identity == ref.identity }
                    ?: return finishAction(tool, null, "Not done: ${ref.ref} \"${ref.label}\" is no longer on the screen.", false, 0, false)
                params.put("elementId", again.elementId)
            }
        }
        owner.status(done)
        fresh = false
        var envelope = env.act(tool, params, goal)
        var waited = 0L
        if (envelope.errorClass == AgentFailureClass.GATE_REQUIRED) {
            val approval = owner.awaitApproval(done.replaceFirstChar { it.lowercase() }, ownerTimeoutMs)
            waited += approval.waitedMs
            when (approval.outcome) {
                MindApproval.APPROVED -> {
                    // The grant is for this exact action on this exact control: re-observe, rebind the ref, retry once.
                    val retryParams = JSONObject(params.toString())
                    env.observe(goal).page?.let(::bind)
                    fresh = false
                    if (ref != null) {
                        val again = refs.resolve(ref.ref)?.takeIf { it.identity == ref.identity }
                            ?: return finishAction(tool, null, "The owner approved, but ${ref.ref} \"${ref.label}\" is no longer on the screen.", false, waited, changesScreen)
                        retryParams.put("elementId", again.elementId)
                    }
                    envelope = env.act(tool, retryParams, goal)
                }
                MindApproval.DECLINED -> return finishAction(tool, null, "The owner declined: $done was not done. Respect this decision.", false, waited, changesScreen)
                MindApproval.CANCELLED -> return MindToolResult("NOT RUN: the owner stopped the mission.", ok = false, ownerWaitMs = waited)
                MindApproval.TIMED_OUT -> return finishAction(tool, null, "The owner did not approve in time; $done was not done.", false, waited, changesScreen)
                MindApproval.NOT_PENDING -> return finishAction(tool, null,
                    "Not allowed: ${envelope.safeMessage ?: "this action needs the owner's confirmation"}.", false, waited, changesScreen)
            }
        }
        if (envelope.errorClass == AgentFailureClass.HUMAN_HAS_CONTROL) {
            owner.status("Waiting for you to hand the phone back")
            val back = owner.awaitControl(ownerTimeoutMs)
            waited += back.waitedMs
            return finishAction(tool, null, if (back.answered) "The owner had taken over the phone and has handed it back. Nothing was done; " +
                "check the screen before continuing." else "The owner has control of the phone; nothing was done.", false, waited, true)
        }
        return finishAction(tool, envelope, done, null, waited, changesScreen)
    }

    private fun finishAction(tool: String, envelope: AgentActionEnvelope?, done: String, okOverride: Boolean?, waited: Long, changesScreen: Boolean): MindToolResult {
        val header = if (envelope == null) done else describe(tool, envelope, done)
        val ok = okOverride ?: (envelope?.androidExecutionOk == true)
        invalidate()
        val result = observeAndRender(header)
        return result.copy(ok = ok, changedScreen = changesScreen && ok || tool in NAVIGATION, ownerWaitMs = waited)
    }

    private fun describe(tool: String, envelope: AgentActionEnvelope, done: String): String {
        val message = envelope.safeMessage?.takeIf { it.isNotBlank() }
        return when {
            envelope.errorClass == AgentFailureClass.AUTH_REQUIRED ->
                "Not typed: this is a sensitive field. Use vault_fill so the owner fills it through the Secrets Card."
            envelope.errorClass == AgentFailureClass.STALE_OBSERVATION || envelope.errorClass == AgentFailureClass.TARGET_NOT_FOUND ->
                "Not done: the element moved or disappeared before Cyclone could act. Here is the screen now."
            envelope.errorClass == AgentFailureClass.POLICY_DENIED -> "Not allowed: ${message ?: "Cyclone's access settings block this action"}."
            envelope.errorClass == AgentFailureClass.ACCESSIBILITY_UNAVAILABLE ->
                "Not done: Cyclone Accessibility is not connected, so the phone cannot be operated right now."
            envelope.errorClass == AgentFailureClass.CAPABILITY_UNAVAILABLE -> "Not available: ${message ?: tool}."
            !envelope.androidExecutionOk -> "Failed: ${message ?: "Android could not perform it"}."
            tool == "phone.type" -> "$done."
            envelope.pageChanged -> "$done. The screen changed."
            else -> "$done. The screen did not visibly change."
        }
    }

    // ---- knowing ------------------------------------------------------------------------------------------------

    private fun appsList(query: String): MindToolResult {
        val apps = device.apps().sortedBy { it.label.lowercase() }
        val filtered = if (query.isBlank()) apps else apps.filter {
            it.label.contains(query, true) || it.packageName.contains(query, true)
        }
        if (filtered.isEmpty()) return MindToolResult("No installed app matches \"$query\".", "apps: none for \"$query\"")
        val shown = filtered.take(80)
        return MindToolResult(
            "Installed apps${if (query.isBlank()) "" else " matching \"$query\""} (${filtered.size}):\n" +
                shown.joinToString("\n") { "  ${it.label} — ${it.packageName}" } + if (filtered.size > shown.size) "\n  …" else "",
            "apps: ${filtered.size}${if (query.isBlank()) "" else " for \"$query\""}",
        )
    }

    private fun recall(topic: String): MindToolResult {
        val brain = env.brainRecall(topic)
        val routes = env.knownRoutes(topic)
        val parts = listOfNotNull(
            brain.evidence?.takeIf { it.length() > 0 }?.let { "What Cyclone remembers:\n${compactJson(it)}" },
            routes.evidence?.takeIf { it.length() > 0 }?.let { "Routes Cyclone has verified before:\n${compactJson(it)}" },
        )
        if (parts.isEmpty()) return MindToolResult("Nothing remembered about \"$topic\".", "recall: nothing")
        return MindToolResult(parts.joinToString("\n\n").take(4_000), "recall \"${topic.take(60)}\"")
    }

    // ---- the owner ----------------------------------------------------------------------------------------------

    private fun ownerAsk(arguments: JSONObject): MindToolResult {
        val question = arguments.optString("question").trim()
        if (question.isBlank()) return MindToolResult.error("question is required.")
        val choices = arguments.optJSONArray("choices")?.let { list -> (0 until list.length()).map { list.optString(it).trim() }.filter(String::isNotBlank) }.orEmpty()
        owner.status("Waiting for your answer")
        val reply = owner.ask(question.take(500), choices.take(6), ownerTimeoutMs)
        return if (reply.answered) MindToolResult("The owner answered: ${reply.text}", "owner: ${reply.text.take(120)}", ownerWaitMs = reply.waitedMs)
        else MindToolResult("The owner has not answered after ${reply.waitedMs / 60_000} min. Continue with what you can, or give up and say what you needed.",
            "owner: no answer", ok = false, ownerWaitMs = reply.waitedMs)
    }

    private fun vaultFill(arguments: JSONObject): MindToolResult {
        val (ref, error) = target(arguments)
        if (ref == null) return error!!
        if (!ref.editable) return MindToolResult.error("${ref.ref} is not a text field.")
        val slot = SLOTS[arguments.optString("what").lowercase()] ?: return MindToolResult.error(
            "what must be one of: ${SLOTS.keys.joinToString()}.")
        val page = screen ?: return MindToolResult.error("Read the screen first.")
        val reason = arguments.optString("reason").replace(Regex("[^A-Za-z0-9 ._/-]"), " ").trim().take(100).ifBlank { "Sign in" }
        owner.status("Waiting for the Secrets Card")
        val reply = owner.fillSecret(page, ref, slot, reason, ownerTimeoutMs)
        val header = when (reply.outcome) {
            MindSecretOutcome.FILLED -> "The owner filled ${ref.ref} \"${ref.label}\" through the Secrets Card. You never see the value."
            MindSecretOutcome.DECLINED -> "The owner chose not to fill ${ref.ref}. Do not try to type it yourself."
            MindSecretOutcome.MISSING -> "No saved value exists for this field and the owner did not enter one."
            MindSecretOutcome.TIMED_OUT -> "The owner did not respond to the Secrets Card in time."
            MindSecretOutcome.UNAVAILABLE -> "The Secrets Card cannot be used here: ${reply.detail.ifBlank { "this app or site is not identified" }}."
            MindSecretOutcome.FAILED -> "The Secrets Card could not fill the field: ${reply.detail.ifBlank { "unknown reason" }}."
        }
        invalidate()
        return observeAndRender(header).copy(ok = reply.outcome == MindSecretOutcome.FILLED, ownerWaitMs = reply.waitedMs, changedScreen = true)
    }

    // ---- tracking and finishing ---------------------------------------------------------------------------------

    private fun planUpdate(arguments: JSONObject): MindToolResult {
        val steps = arguments.optJSONArray("steps") ?: return MindToolResult.error("steps is required.")
        plan = (0 until steps.length()).mapNotNull { index ->
            val row = steps.optJSONObject(index) ?: return@mapNotNull steps.optString(index).takeIf(String::isNotBlank)?.let { MindPlanStep(it.take(140), "todo") }
            val text = row.optString("step").trim().take(140)
            if (text.isBlank()) null else MindPlanStep(text, row.optString("status").lowercase().takeIf { it in MindPlanStep.STATUSES } ?: "todo")
        }.take(20)
        owner.plan(plan)
        return MindToolResult("Plan updated (${plan.size} steps).", "plan: " + plan.joinToString(" · ") { "${it.status}:${it.text.take(40)}" }.take(180))
    }

    private fun finish(arguments: JSONObject): MindToolResult {
        val summary = arguments.optString("summary").trim()
        val evidence = arguments.optString("evidence").trim()
        if (summary.isBlank()) return MindToolResult.error("summary is required.")
        if (evidence.isBlank() && finishRejections < MAX_FINISH_REJECTIONS) {
            finishRejections++
            return MindToolResult.error("evidence is required: say what on the screen shows the goal is done.")
        }
        val final = runCatching { env.observe(goal).page }.getOrNull()
        val seen = final?.let { " (final screen: ${MindScreen.brief(it, appLabel(it.packageName))})" }.orEmpty()
        return MindToolResult("Mission recorded as complete.", "finished: ${summary.take(160)}", ending = MindEnding.COMPLETED,
            summary = summary.take(600), evidence = (evidence.ifBlank { "none given" } + seen).take(600))
    }

    private fun giveUp(arguments: JSONObject): MindToolResult {
        val reason = arguments.optString("reason").trim().ifBlank { "No reason given." }
        val next = arguments.optString("owner_next_step").trim()
        val summary = reason.take(500) + if (next.isNotBlank()) " What you can do: ${next.take(300)}" else ""
        return MindToolResult("Mission recorded as not possible.", "gave up: ${reason.take(160)}", ending = MindEnding.GAVE_UP, summary = summary)
    }

    // ---- helpers ------------------------------------------------------------------------------------------------

    private fun invalidate() {
        fresh = false
        env.invalidateObservation()
    }

    private fun appLabel(packageName: String): String? = device.apps().firstOrNull { it.packageName == packageName }?.label

    private fun compactJson(json: JSONObject): String = json.toString().replace(Regex("\"(observationId|elementId|sessionId|displayId|generation)\":\"?[^,}\"]*\"?,?"), "")
        .take(1_800)

    companion object {
        private const val MAX_FINISH_REJECTIONS = 2
        private val NAVIGATION = setOf("phone.open_app", "phone.launch_intent", "phone.open_settings", "phone.set_timer", "phone.set_alarm", "phone.back", "phone.home")
        private val SENSITIVE = Regex("(?i)password|passcode|wachtwoord|\\bpin\\b|one[- ]time|otp|verification code|verificatiecode|cvv|cvc|card number|kaartnummer|security code")
        fun sensitive(label: String): Boolean = SENSITIVE.containsMatchIn(label)

        val SLOTS = linkedMapOf(
            "password" to "password",
            "username" to "username",
            "email" to "email",
            "phone_number" to "phone",
            "one_time_code" to "otp",
            "pin" to "pin",
            "card_number" to "card.number",
            "card_expiry" to "card.expiry",
            "card_cvc" to "card.cvc",
        )

        private fun duration(seconds: Int): String {
            val h = seconds / 3600; val m = (seconds % 3600) / 60; val s = seconds % 60
            return listOfNotNull(h.takeIf { it > 0 }?.let { "$it h" }, m.takeIf { it > 0 }?.let { "$it min" }, s.takeIf { it > 0 }?.let { "$it s" }).joinToString(" ")
        }

        private val REF = string("An element ref from the latest screen, like e3.")

        val SPECS: List<MindToolSpec> = listOf(
            MindToolSpec("screen_read", "Read the current screen: app, visible text and the controls with their refs."),
            MindToolSpec("screen_look", "Take a screenshot to see the screen as an image (icons, pictures, layouts the text misses), plus the text description."),
            MindToolSpec("screen_find", "Find elements on the current screen matching a description, including ones not listed in the screen summary.",
                objectSchema("query" to string("What to look for, e.g. \"install button\" or \"search\"."), required = listOf("query"))),
            MindToolSpec("tap", "Tap an element.", objectSchema("ref" to REF, required = listOf("ref"))),
            MindToolSpec("long_press", "Long-press an element.", objectSchema("ref" to REF, required = listOf("ref"))),
            MindToolSpec("type_text", "Replace the text in a text field. Not for passwords, codes or card numbers (use vault_fill). Set press_enter to submit, e.g. to search.",
                objectSchema("ref" to REF, "text" to string("The full text the field should contain."),
                    "press_enter" to boolean("Press the keyboard's Enter/Search key afterwards."), required = listOf("ref", "text"))),
            MindToolSpec("press_enter", "Press the keyboard's Enter/Search/Go key in a text field.", objectSchema("ref" to REF, required = listOf("ref"))),
            MindToolSpec("scroll", "Scroll the screen, or one list when ref is given.",
                objectSchema("direction" to string("down shows more below, up goes back.", listOf("down", "up")), "ref" to REF, required = listOf("direction"))),
            MindToolSpec("back", "Press Android Back."),
            MindToolSpec("home", "Go to the Home screen."),
            MindToolSpec("wait", "Wait for the phone (loading, a countdown), optionally until some text appears, then read the screen.",
                objectSchema("seconds" to integer("How long to wait at most.", 1, 30), "until_text" to string("Stop waiting as soon as this text is on screen."))),
            MindToolSpec("open_app", "Open an installed app by name or package.", objectSchema("app" to string("App name or package."), required = listOf("app"))),
            MindToolSpec("open_link", "Open a link: a website (https://…), a Play Store page (market://details?id=<package>), a map (geo:…), or a mail/phone/sms composer.",
                objectSchema("url" to string("The link."), required = listOf("url"))),
            MindToolSpec("open_settings", "Open a page of Android Settings directly.",
                objectSchema("page" to string("Which page.", PhoneSettingsPages.pages.keys.toList()),
                    "app" to string("For app_details and app_notifications: the app's name or package."), required = listOf("page"))),
            MindToolSpec("set_timer", "Start a countdown timer in the clock app.",
                objectSchema("hours" to integer("Hours.", 0, 24), "minutes" to integer("Minutes.", 0, 1440), "seconds" to integer("Seconds.", 0, 86400),
                    "label" to string("Optional name for the timer."))),
            MindToolSpec("set_alarm", "Create an alarm in the clock app.",
                objectSchema("hour" to integer("Hour, 0-23.", 0, 23), "minute" to integer("Minute, 0-59.", 0, 59), "label" to string("Optional name."),
                    required = listOf("hour", "minute"))),
            MindToolSpec("apps_list", "List the installed apps, optionally filtered.", objectSchema("query" to string("Part of a name or package."))),
            MindToolSpec("recall", "Look up what Cyclone remembers about the owner, apps and routes that worked before.",
                objectSchema("topic" to string("What you want to know."), required = listOf("topic"))),
            MindToolSpec("owner_ask", "Ask the owner a question and wait for the answer. Only for decisions or information you cannot find yourself; never for passwords or codes.",
                objectSchema("question" to string("A short, specific question."), "choices" to array("Optional answer options.", string("An option.")),
                    required = listOf("question"))),
            MindToolSpec("vault_fill", "Have the owner fill a secret field (password, code, card) through the Secrets Card. The value never reaches you.",
                objectSchema("ref" to REF, "what" to string("What the field needs.", SLOTS.keys.toList()), "reason" to string("Short reason shown to the owner, e.g. Sign in to Gmail."),
                    required = listOf("ref", "what"))),
            MindToolSpec("plan_update", "Write or update your plan for this mission. The owner sees it.",
                objectSchema("steps" to array("The steps in order.", objectSchema("step" to string("What to do."),
                    "status" to string("Progress.", MindPlanStep.STATUSES), required = listOf("step", "status"))), required = listOf("steps"))),
            MindToolSpec("note", "Remember a fact for later in this mission.", objectSchema("text" to string("The fact."), required = listOf("text"))),
            MindToolSpec("task_finish", "End the mission as done. Only after you have seen that the goal is achieved.",
                objectSchema("summary" to string("One or two sentences for the owner."), "evidence" to string("What on the screen shows it is done."),
                    required = listOf("summary", "evidence"))),
            MindToolSpec("task_give_up", "End the mission because it cannot be done.",
                objectSchema("reason" to string("Why, honestly."), "owner_next_step" to string("What the owner could do instead."), required = listOf("reason"))),
        )
    }
}
