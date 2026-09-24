package com.cyclone.mobile.agent.nav

import com.cyclone.mobile.applearner.PageControl

/** Additional clause boundaries; canonical executor GATE checks still run for every mutation. */
object NavigationActionPolicy {
    fun boundary(clause: TaskClause?, tool: String, target: PageControl?, screen: NavigationScreen?): String? {
        val signal = target?.let { it.label + " " + it.semanticName + " " + it.selector.optString("resourceId") }.orEmpty()
        if (tool in setOf("phone.type", "phone.replace_text") &&
            (target?.selector?.optBoolean("password") == true || SECRET.containsMatchIn(signal))) return "needs-secret"
        if (clause?.capability == NavCapability.CREATE_ACCOUNT && tool in setOf("phone.click", "phone.long_press") &&
            screen?.page?.controls?.any { it.selector.optBoolean("editable") } == true &&
            SUBMIT.containsMatchIn(signal)) return "account-submit"
        return null
    }

    private val SECRET = Regex("(?i)password|passcode|passwd|one[-_ ]?time|verification[-_ ]?code|\\botp\\b|\\bpin\\b|card[-_ ]?number|\\bcvv\\b|\\bcvc\\b|api[-_ ]?key")
    private val SUBMIT = Regex("(?i)sign[- ]?up|create.*account|register|submit|continue|next|agree|confirm")
}
