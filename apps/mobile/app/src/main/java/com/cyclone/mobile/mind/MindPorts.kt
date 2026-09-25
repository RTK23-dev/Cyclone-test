package com.cyclone.mobile.mind

import com.cyclone.mobile.agent.contract.AgentPageCard

data class MindApp(val packageName: String, val label: String)

enum class MindApproval { APPROVED, DECLINED, TIMED_OUT, CANCELLED, NOT_PENDING }

data class MindOwnerReply(val answered: Boolean, val text: String = "", val waitedMs: Long = 0)

data class MindApprovalReply(val outcome: MindApproval, val waitedMs: Long = 0)

enum class MindSecretOutcome { FILLED, DECLINED, MISSING, FAILED, TIMED_OUT, UNAVAILABLE }

data class MindSecretReply(val outcome: MindSecretOutcome, val waitedMs: Long = 0, val detail: String = "")

data class MindPlanStep(val text: String, val status: String) {
    companion object {
        val STATUSES = listOf("todo", "doing", "done", "skipped")
    }
}

/**
 * The owner as seen by the Mind. Every call may block while the owner decides; implementations return promptly when
 * the mission is stopped and report how long the owner took, which does not count as working time.
 */
interface MindOwnerPort {
    fun ask(question: String, choices: List<String>, timeoutMs: Long): MindOwnerReply
    /** Cyclone has already put the exact action on the approval card; wait for the owner's decision. */
    fun awaitApproval(action: String, timeoutMs: Long): MindApprovalReply
    /** Opens the Secrets Card for this field. The value goes from the owner or the Vault straight into the field. */
    fun fillSecret(page: AgentPageCard, target: MindRef, slot: String, reason: String, timeoutMs: Long): MindSecretReply
    /** The owner took over the phone; wait until they hand it back. */
    fun awaitControl(timeoutMs: Long): MindOwnerReply
    fun status(text: String) {}
    fun plan(steps: List<MindPlanStep>) {}
}

/** Facts about the phone that are not on the screen. */
interface MindDevicePort {
    fun apps(): List<MindApp>
    fun now(): String
    fun device(): String
    fun sleep(ms: Long) { Thread.sleep(ms) }
    /** Why the phone cannot be operated right now (locked, screen off), or null when it can. */
    fun blocker(): String? = null
}
