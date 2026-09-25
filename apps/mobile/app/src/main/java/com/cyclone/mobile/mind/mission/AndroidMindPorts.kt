package com.cyclone.mobile.mind.mission

import android.content.Context
import android.os.Build
import com.cyclone.mobile.DeviceState
import com.cyclone.mobile.agent.contract.AgentPageCard
import com.cyclone.mobile.fastpath.InstalledAppInventory
import com.cyclone.mobile.mind.MindApp
import com.cyclone.mobile.mind.MindApproval
import com.cyclone.mobile.mind.MindApprovalReply
import com.cyclone.mobile.mind.MindDevicePort
import com.cyclone.mobile.mind.MindOwnerPort
import com.cyclone.mobile.mind.MindOwnerReply
import com.cyclone.mobile.mind.MindPlanStep
import com.cyclone.mobile.mind.MindRef
import com.cyclone.mobile.mind.MindSecretOutcome
import com.cyclone.mobile.mind.MindSecretReply
import com.cyclone.mobile.places.PlaceResolver
import com.cyclone.mobile.secrets.SecretFillTarget
import com.cyclone.mobile.secrets.SecretPersona
import com.cyclone.mobile.secrets.SecretRequestMetadata
import com.cyclone.mobile.secrets.SecretUseResult
import com.cyclone.mobile.secrets.SecretUseStatus
import com.cyclone.mobile.secrets.SecretsPhoneFacade
import com.cyclone.mobile.ui.overlay.OverlayChromeRuntime
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicReference

internal class AndroidMindDevice(private val context: Context) : MindDevicePort {
    override fun apps(): List<MindApp> {
        if (InstalledAppInventory.snapshot.isEmpty()) InstalledAppInventory.refresh(context)
        return InstalledAppInventory.snapshot.map { MindApp(it.packageName, it.label) }
    }

    override fun now(): String {
        val zone = TimeZone.getDefault()
        return SimpleDateFormat("EEEE d MMMM yyyy, HH:mm", Locale.ENGLISH).apply { timeZone = zone }.format(Date()) + " (${zone.id})"
    }

    override fun device(): String {
        val locales = context.resources.configuration.locales
        val languages = (0 until locales.size()).map { locales[it].displayLanguage }.distinct().joinToString(", ")
        return "${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}; languages: $languages"
    }
}

/**
 * The owner, reached through the approval card, the Secrets Card and the mission's own requests (in the app, the
 * overlay composer and the task notification). Every wait honours Stop and reports how long the owner took.
 */
internal class AndroidMindOwner(
    private val context: Context,
    private val inbox: OwnerInbox,
    private val missionId: String,
    private val cancelled: () -> Boolean,
    private val onWaiting: (String?) -> Unit,
    private val onStatus: (String) -> Unit,
    private val onPlan: (List<MindPlanStep>) -> Unit,
) : MindOwnerPort {
    /** The overlay and task-card session of this mission; GATE grants are bound to it. */
    private val overlaySession = "mission-$missionId"

    override fun ask(question: String, choices: List<String>, timeoutMs: Long): MindOwnerReply {
        val request = inbox.post(missionId, OwnerRequestKind.QUESTION, question, choices)
        onWaiting(question)
        try {
            val wait = inbox.await(request, timeoutMs, cancelled)
            val answer = (wait.response as? OwnerResponse.Answer)?.text?.trim().orEmpty()
            return MindOwnerReply(answer.isNotBlank(), answer, wait.waitedMs)
        } finally {
            onWaiting(null)
        }
    }

    override fun awaitApproval(action: String, timeoutMs: Long): MindApprovalReply {
        if (OverlayChromeRuntime.gateWait() != OverlayChromeRuntime.GateWait.PENDING) return MindApprovalReply(MindApproval.NOT_PENDING)
        val request = inbox.post(missionId, OwnerRequestKind.APPROVAL, "Cyclone wants to: $action")
        onWaiting("Approve: $action")
        val started = System.currentTimeMillis()
        try {
            while (true) {
                val waited = System.currentTimeMillis() - started
                if (cancelled()) return MindApprovalReply(MindApproval.CANCELLED, waited)
                when (OverlayChromeRuntime.gateWait()) {
                    OverlayChromeRuntime.GateWait.APPROVED -> return approved(waited)
                    OverlayChromeRuntime.GateWait.NONE -> return MindApprovalReply(if (cancelled()) MindApproval.CANCELLED else MindApproval.DECLINED, waited)
                    OverlayChromeRuntime.GateWait.PENDING -> OverlayChromeRuntime.keepGateChallengeAlive()
                }
                // The mission's request card (app, notification) answers here; the overlay button answers via gateWait().
                when (inbox.poll(request.id)) {
                    OwnerResponse.Approve -> return if (OverlayChromeRuntime.approveGateForMission()) approved(waited)
                        else MindApprovalReply(MindApproval.DECLINED, waited)
                    OwnerResponse.Decline -> {
                        OverlayChromeRuntime.declineGateForMission()
                        return MindApprovalReply(MindApproval.DECLINED, waited)
                    }
                    else -> Unit
                }
                if (waited >= timeoutMs) {
                    OverlayChromeRuntime.declineGateForMission()
                    return MindApprovalReply(MindApproval.TIMED_OUT, waited)
                }
                Thread.sleep(POLL_MS)
            }
        } finally {
            inbox.withdraw(request.id)
            onWaiting(null)
        }
    }

    private fun approved(waited: Long): MindApprovalReply {
        DeviceState.setController(DeviceState.Controller.AGENT)
        OverlayChromeRuntime.missionWorking(overlaySession, "Approved")
        return MindApprovalReply(MindApproval.APPROVED, waited)
    }

    override fun fillSecret(page: AgentPageCard, target: MindRef, slot: String, reason: String, timeoutMs: Long): MindSecretReply {
        val place = PlaceResolver.resolveCurrent(page) ?: return MindSecretReply(MindSecretOutcome.UNAVAILABLE,
            detail = "Cyclone cannot identify this app or website yet (for a website, the address bar must be visible)")
        val metadata = runCatching { SecretRequestMetadata(place.id, SecretPersona.LIVE, slot, reason) }.getOrElse {
            return MindSecretReply(MindSecretOutcome.UNAVAILABLE, detail = "the request could not be described safely")
        }
        val fillTarget = runCatching { SecretFillTarget(target.elementId, target.observationId, page.sessionId, page.displayId) }.getOrElse {
            return MindSecretReply(MindSecretOutcome.FAILED, detail = "the field is not addressable")
        }
        val result = AtomicReference<SecretUseResult?>(null)
        val request = inbox.post(missionId, OwnerRequestKind.SECRET, "Fill ${target.label} for ${place.origin ?: place.id.substringAfter(':')}")
        onWaiting("Secure input: ${target.label}")
        val started = System.currentTimeMillis()
        try {
            SecretsPhoneFacade.requestForRun(context, metadata, fillTarget) { result.set(it) }
            while (result.get() == null) {
                if (cancelled()) return MindSecretReply(MindSecretOutcome.DECLINED, System.currentTimeMillis() - started)
                if (System.currentTimeMillis() - started > timeoutMs) return MindSecretReply(MindSecretOutcome.TIMED_OUT, timeoutMs)
                Thread.sleep(POLL_MS)
            }
        } finally {
            inbox.withdraw(request.id)
            onWaiting(null)
        }
        val use = result.get()!!
        val waited = System.currentTimeMillis() - started
        DeviceState.setController(DeviceState.Controller.AGENT)
        return when (use.status) {
            SecretUseStatus.FILLED -> MindSecretReply(if (use.verified) MindSecretOutcome.FILLED else MindSecretOutcome.FAILED, waited,
                if (use.verified) "" else "the field did not accept the value")
            SecretUseStatus.SKIPPED, SecretUseStatus.CANCELLED -> MindSecretReply(MindSecretOutcome.DECLINED, waited)
            SecretUseStatus.MISSING -> MindSecretReply(MindSecretOutcome.MISSING, waited)
            SecretUseStatus.STORED -> MindSecretReply(MindSecretOutcome.FAILED, waited, "the value was saved but not filled")
            SecretUseStatus.FAILED, SecretUseStatus.ALREADY_USED -> MindSecretReply(MindSecretOutcome.FAILED, waited, use.errorCode.orEmpty())
        }
    }

    override fun awaitControl(timeoutMs: Long): MindOwnerReply {
        val request = inbox.post(missionId, OwnerRequestKind.CONTROL, "You have control of the phone. Hand it back when you are done.")
        onWaiting("You have control")
        val started = System.currentTimeMillis()
        try {
            while (true) {
                val waited = System.currentTimeMillis() - started
                if (DeviceState.controller == DeviceState.Controller.AGENT) return MindOwnerReply(true, waitedMs = waited)
                if (cancelled() || waited > timeoutMs) return MindOwnerReply(false, waitedMs = waited)
                if (inbox.poll(request.id) == OwnerResponse.Done) {
                    DeviceState.setController(DeviceState.Controller.AGENT)
                    OverlayChromeRuntime.missionWorking(overlaySession)
                    return MindOwnerReply(true, waitedMs = waited)
                }
                Thread.sleep(POLL_MS)
            }
        } finally {
            inbox.withdraw(request.id)
            onWaiting(null)
        }
    }

    override fun status(text: String) = onStatus(text)
    override fun plan(steps: List<MindPlanStep>) = onPlan(steps)

    private companion object {
        const val POLL_MS = 300L
    }
}
