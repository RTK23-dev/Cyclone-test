package com.cyclone.mobile.ai

import com.cyclone.mobile.agent.contract.AgentElementCandidate
import com.cyclone.mobile.agent.contract.AgentPageCard
import com.cyclone.mobile.applearner.PageContext

enum class LoginAutofillOutcome { ASK_USER, FOCUS_FIELD, SUBMIT, NOT_APPLICABLE, UNRESOLVED }
data class LoginAutofillDecision(
    val outcome: LoginAutofillOutcome,
    val target: AgentElementCandidate? = null,
    val reason: String,
)

/**
 * Login walls stay a human gate. Autofill is the user authorizing Cyclone to focus the system
 * password manager and tap the unique Sign in control. Cyclone never stores or types secrets.
 */
class LoginAutofillPolicy {
    private val attempted = mutableSetOf<String>()
    private var burstStep = 0

    fun reset() {
        burstStep = 0
        attempted.clear()
    }

    fun evaluate(page: AgentPageCard?, authorized: Boolean): LoginAutofillDecision {
        if (page == null || !page.actionable) {
            return LoginAutofillDecision(LoginAutofillOutcome.NOT_APPLICABLE, reason = "login.no_current_page")
        }
        if (!isLoginWall(page)) {
            return LoginAutofillDecision(LoginAutofillOutcome.NOT_APPLICABLE, reason = "login.no_wall")
        }
        val form = form(page)
        if (!authorized) {
            return LoginAutofillDecision(LoginAutofillOutcome.ASK_USER, reason = "login.ask_user")
        }
        val username = form.username
        val password = form.password
        val submit = form.submit
        val focus = when {
            burstStep == 0 && username != null -> username
            burstStep <= 1 && password != null -> password
            else -> null
        }
        if (focus != null) {
            burstStep = (burstStep + 1).coerceAtMost(3)
            return once(page, focus, "login.focus_field", LoginAutofillOutcome.FOCUS_FIELD)
        }
        if (submit != null) {
            burstStep = (burstStep + 1).coerceAtMost(4)
            return once(page, submit, "login.submit", LoginAutofillOutcome.SUBMIT)
        }
        return LoginAutofillDecision(LoginAutofillOutcome.UNRESOLVED, reason = "login.submit_unavailable")
    }

    private fun once(
        page: AgentPageCard,
        target: AgentElementCandidate,
        reason: String,
        outcome: LoginAutofillOutcome,
    ): LoginAutofillDecision {
        val id = key(page, target)
        if (!attempted.add(id)) {
            return LoginAutofillDecision(LoginAutofillOutcome.UNRESOLVED, reason = "login.effect_unverified")
        }
        return LoginAutofillDecision(outcome, target, reason)
    }

    private fun key(page: AgentPageCard, target: AgentElementCandidate): String =
        "${page.sessionId}|${page.displayId}|${page.packageName}|${normalize(target.label)}|${target.elementId}"

    data class Form(
        val username: AgentElementCandidate? = null,
        val password: AgentElementCandidate? = null,
        val submit: AgentElementCandidate? = null,
    )

    companion object {
        fun isLoginWall(page: AgentPageCard): Boolean {
            val labels = page.controls.map { normalize(it.label) }
            val signedOut = labels.any { it in SIGN_IN_LABELS || it.startsWith("log in") || it.startsWith("sign in") }
            val signedIn = labels.any { it in SIGN_OUT_LABELS }
            if (signedIn) return false
            val form = form(page)
            return signedOut || form.password != null || (form.username != null && form.submit != null)
        }

        fun isLoginWall(page: PageContext): Boolean {
            val labels = page.controls.map { it.label.trim().lowercase() }
            val signedOut = labels.any { it in SIGN_IN_LABELS || it.startsWith("log in") || it.startsWith("sign in") }
            val signedIn = labels.any { it in SIGN_OUT_LABELS }
            return signedOut && !signedIn
        }

        fun form(page: AgentPageCard): Form {
            val editable = page.controls.filter { editable(it, page.observationId) }
            val password = editable.firstOrNull { isPassword(it) }
            val username = editable.firstOrNull { !isPassword(it) && isUsername(it) }
                ?: editable.firstOrNull { !isPassword(it) }
            val submit = ranked(page.controls.filter { clickable(it, page.observationId) && isSubmit(it) })
            return Form(username = username, password = password, submit = submit)
        }

        fun explanation(reason: String): String = when (reason) {
            "login.focus_field" -> "Opening your password manager on the sign-in field."
            "login.submit" -> "Signing in with the filled login."
            "login.submit_unavailable" -> "Choose a saved login, then tap Autofill again or I'm Done."
            "login.effect_unverified" -> "Sign-in is still on screen. Take Over, Autofill again, or tap I'm Done."
            else -> "This screen needs your sign-in. Take Over, Autofill, or tap I'm Done when finished."
        }

        fun isLoginSubmitLabel(label: String): Boolean {
            val value = normalize(label)
            return value in SIGN_IN_LABELS || value in CONTINUE_LABELS ||
                value.startsWith("log in") || value.startsWith("sign in")
        }

        private fun editable(control: AgentElementCandidate, observationId: String): Boolean {
            if (control.observationId != observationId || control.elementId.isBlank()) return false
            if (!control.evidence.optBoolean("enabled", true) || !control.evidence.optBoolean("visibleToUser", true)) return false
            val role = control.role.lowercase()
            return control.evidence.optBoolean("editable") ||
                role in setOf("edittext", "textbox", "text_field", "edit")
        }

        private fun clickable(control: AgentElementCandidate, observationId: String): Boolean =
            control.observationId == observationId && control.elementId.isNotBlank() &&
                control.evidence.optBoolean("enabled", true) && control.evidence.optBoolean("visibleToUser", true) &&
                (control.evidence.optBoolean("clickable") || control.role.lowercase() in setOf("button", "link"))

        private fun isPassword(control: AgentElementCandidate): Boolean {
            if (control.evidence.optBoolean("password")) return true
            val hint = hint(control)
            return Regex("(?i)\\b(password|passcode|passwd)\\b").containsMatchIn(hint)
        }

        private fun isUsername(control: AgentElementCandidate): Boolean {
            val hint = hint(control)
            return Regex("(?i)\\b(email|e-mail|username|user name|phone|mobile|account)\\b").containsMatchIn(hint)
        }

        private fun isSubmit(control: AgentElementCandidate): Boolean = isLoginSubmitLabel(control.label)

        private fun hint(control: AgentElementCandidate): String = listOf(
            control.label,
            control.semanticName,
            control.evidence.optString("contentDescription"),
            control.evidence.optString("resourceId"),
            control.evidence.optString("hint"),
        ).joinToString(" ")

        private fun ranked(controls: List<AgentElementCandidate>): AgentElementCandidate? {
            val matches = controls.distinctBy { it.elementId }
            if (matches.isEmpty()) return null
            if (matches.size == 1) return matches.single()
            return matches.minBy { control ->
                SUBMIT_RANK.indexOfFirst { token -> normalize(control.label) == token }
                    .takeIf { it >= 0 } ?: Int.MAX_VALUE
            }
        }

        private fun normalize(label: String) = label.trim().lowercase().replace(Regex("\\s+"), " ")

        private val SIGN_IN_LABELS = setOf("log in", "login", "sign in", "signin", "inloggen", "anmelden")
        private val SIGN_OUT_LABELS = setOf("log out", "logout", "sign out", "signout")
        private val CONTINUE_LABELS = setOf("continue", "next", "doorgaan", "weiter")
        private val SUBMIT_RANK = listOf(
            "log in", "sign in", "login", "signin", "inloggen", "anmelden",
            "continue", "next", "doorgaan", "weiter",
        )
    }
}
