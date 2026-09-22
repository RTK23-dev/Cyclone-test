package com.cyclone.mobile.secrets

import com.cyclone.mobile.agent.contract.AgentPageCard
import com.cyclone.mobile.ai.LoginAutofillPolicy

/**
 * Pure metadata-only detector that converts an already-classified native-app login wall into
 * a run-scoped Vault request. Chrome is intentionally excluded until a canonical origin is
 * available; package:com.android.chrome would violate the frozen place identity contract.
 */
internal object SecretWallDetector {
    private val chromePackages = setOf(
        "com.android.chrome",
        "com.chrome.beta",
        "com.chrome.dev",
        "com.chrome.canary",
    )

    fun passwordForLogin(
        page: AgentPageCard,
        persona: SecretPersona = SecretPersona.LIVE,
    ): SecretWallRequest? {
        if (!LoginAutofillPolicy.isLoginWall(page)) return null
        val packageName = page.packageName
        if (packageName.isBlank() || packageName in chromePackages) return null

        val password = LoginAutofillPolicy.form(page).password ?: return null
        if (password.observationId != page.observationId || password.elementId.isBlank()) return null

        val placeId = "package:$packageName"
        val request = runCatching {
            SecretRequestMetadata(
                placeId = placeId,
                persona = persona,
                slot = "password",
                reason = "Login required",
            )
        }.getOrNull() ?: return null

        return SecretWallRequest(
            request = request,
            target = SecretFillTarget(
                elementId = password.elementId,
                observationId = page.observationId,
                sessionId = page.sessionId,
                displayId = page.displayId,
            ),
        )
    }
}
