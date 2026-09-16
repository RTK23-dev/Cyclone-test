package com.cyclone.mobile

/**
 * Pre-dispatch / execution-scope failures must keep a stable code.
 * Dumping them into CAPABILITY_UNAVAILABLE made leftover Layer-2 leases look like
 * "Facebook launching is not supported" and killed the task.
 */
object PhoneToolScopeErrors {
    fun code(error: Exception): PhoneToolErrorCode {
        val reason = error.message.orEmpty().uppercase()
        return when {
            "HUMAN" in reason -> PhoneToolErrorCode.HUMAN_HAS_CONTROL
            "POLICY" in reason || "GATE" in reason -> PhoneToolErrorCode.POLICY_DENIED
            "STALE" in reason || "GENERATION" in reason || "EXPIRED" in reason ->
                PhoneToolErrorCode.FRESH_OBSERVATION_REQUIRED
            "MUTATE_LOCK" in reason || "LEASE" in reason || "QUEUE_EMPTY" in reason ->
                PhoneToolErrorCode.WORKSPACE_SCOPE_CONFLICT
            "TARGET_MISMATCH" in reason -> PhoneToolErrorCode.TARGET_SCOPE_MISMATCH
            "MISMATCH" in reason -> PhoneToolErrorCode.INVALID_REQUEST
            else -> PhoneToolErrorCode.ACTION_FAILED
        }
    }

    fun message(error: Exception): String {
        val token = error.message?.substringBefore(':')?.trim()?.take(80).orEmpty()
        return if (token.isNotBlank()) {
            "Execution scope unavailable ($token); observe the current session again."
        } else {
            "Execution scope unavailable; observe the current session again."
        }
    }
}
