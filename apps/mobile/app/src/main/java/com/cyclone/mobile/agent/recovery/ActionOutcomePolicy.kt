package com.cyclone.mobile.agent.recovery

import com.cyclone.mobile.PhoneToolErrorCode
import com.cyclone.mobile.agent.contract.AgentFailureClass

/**
 * One place decides whether a typed action failure is terminal.
 * Generic capability/scope misses are recovery incidents, not HARD_BLOCKER.
 */
object ActionOutcomePolicy {
    fun hardBlocker(errorClass: AgentFailureClass, safeMessage: String?): Boolean {
        if (errorClass == AgentFailureClass.POLICY_DENIED) return true
        return errorClass == AgentFailureClass.CAPABILITY_UNAVAILABLE &&
            safeMessage?.contains("not exposed", ignoreCase = true) == true
    }

    fun shouldCaptureAfter(code: PhoneToolErrorCode?): Boolean {
        if (code == null) return true
        return code != PhoneToolErrorCode.ACCESSIBILITY_NOT_CONNECTED &&
            code != PhoneToolErrorCode.HUMAN_HAS_CONTROL
    }
}
