package com.cyclone.mobile

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Handler
import android.os.HandlerThread
import com.cyclone.mobile.fastpath.FastPathTimings
import com.cyclone.mobile.gesture.AndroidGestureRenderer
import com.cyclone.mobile.gesture.GestureBounds
import com.cyclone.mobile.gesture.GesturePoint
import com.cyclone.mobile.gesture.HumanGestureEngine
import com.cyclone.mobile.gesture.HumanGestureRuntimePolicy
import com.cyclone.mobile.gesture.HumanGestureSeed
import com.cyclone.mobile.gesture.HumanizePreference
import com.cyclone.mobile.gesture.HumanizeProfile
import com.cyclone.mobile.gesture.RuntimeGestureKind
import com.cyclone.mobile.ui.overlay.OverlayGesturePassthrough
import java.util.LinkedHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

data class HumanGestureDispatchTrace(
    val profile: HumanizeProfile,
    val dispatchMode: String,
    val durationMs: Long,
    val accepted: Boolean,
    val reason: String? = null,
)

private data class GestureDispatchOutcome(
    val accepted: Boolean,
    val reason: String? = null,
)

/**
 * Android execution adapter for already-authorized physical touches.
 *
 * This object deliberately owns no GATE, stale-observation, duplicate, confirmation, control-owner,
 * or SessionContract decisions. Callers reach it only after those existing authorities have allowed
 * the mutation. Named-VD and Layer2 backends do not use this adapter in V0.3 because their current
 * input protocol accepts only start/end/duration rather than cubic paths.
 *
 * `dispatchGesture` is queued, not completed. This adapter waits for [AccessibilityService.GestureResultCallback]
 * before returning so Fast Path settle observes the screen after the stroke has landed.
 */
object HumanGestureDispatch {
    private val localOrdinal = AtomicLong(0L)
    private val traces = object : LinkedHashMap<String, HumanGestureDispatchTrace>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, HumanGestureDispatchTrace>?): Boolean = size > 128
    }
    private val callbackLooper by lazy {
        HandlerThread("cyclone-hg-callback").apply {
            isDaemon = true
            start()
        }.looper
    }

    @Synchronized
    private fun record(commandId: String?, trace: HumanGestureDispatchTrace): Boolean {
        if (!commandId.isNullOrBlank()) traces[commandId] = trace
        return trace.accepted
    }

    /** Non-consuming read used only when result serialization needs to distinguish semantic click from fallback touch. */
    @Synchronized
    fun peekTrace(commandId: String?): HumanGestureDispatchTrace? =
        commandId?.takeIf { it.isNotBlank() }?.let(traces::get)

    @Synchronized
    fun consumeTrace(commandId: String?): HumanGestureDispatchTrace? =
        commandId?.takeIf { it.isNotBlank() }?.let(traces::remove)

    fun tap(
        service: CycloneAccessibilityService,
        x: Float,
        y: Float,
        preference: HumanizePreference,
        kind: RuntimeGestureKind,
        commandId: String? = null,
        targetBounds: UiBounds? = null,
    ): Boolean {
        val profile = HumanGestureRuntimePolicy.resolve(preference, kind)
        if (profile == HumanizeProfile.OFF && targetBounds == null) {
            val outcome = legacyTap(service, x, y)
            return record(commandId, HumanGestureDispatchTrace(profile, "legacy_straight", 80L, outcome.accepted, outcome.reason))
        }
        val viewport = viewport(service)
        val target = targetBounds?.takeIf { it.width > 0 && it.height > 0 }?.toGestureBounds()
            ?: pointBounds(x, y)
        val ordinal = localOrdinal.incrementAndGet()
        val plan = runCatching {
            HumanGestureEngine.planTap(
                target = target,
                viewport = viewport,
                profile = profile,
                seed = HumanGestureSeed.derive(commandId, kind.name, ordinal, floatArrayOf(x, y), 80L),
                preferredDurationMs = 80L,
            )
        }.getOrElse {
            return record(commandId, HumanGestureDispatchTrace(profile, "plan_rejected", 0L, false, it.message))
        }
        val path = AndroidGestureRenderer.tapPath(plan)
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, plan.durationMs))
            .build()
        val outcome = dispatchAndAwait(service, gesture, plan.durationMs)
        return record(commandId, HumanGestureDispatchTrace(profile, "humanized_path", plan.durationMs, outcome.accepted, outcome.reason))
    }

    fun longPress(
        service: CycloneAccessibilityService,
        x: Float,
        y: Float,
        durationMs: Long,
        preference: HumanizePreference,
        kind: RuntimeGestureKind,
        commandId: String? = null,
        targetBounds: UiBounds? = null,
    ): Boolean {
        val profile = HumanGestureRuntimePolicy.resolve(preference, kind)
        if (profile == HumanizeProfile.OFF && targetBounds == null) {
            val boundedDuration = durationMs.coerceIn(450L, 3_000L)
            val outcome = legacyLongPress(service, x, y, boundedDuration)
            return record(commandId, HumanGestureDispatchTrace(profile, "legacy_straight", boundedDuration, outcome.accepted, outcome.reason))
        }
        val viewport = viewport(service)
        val target = targetBounds?.takeIf { it.width > 0 && it.height > 0 }?.toGestureBounds()
            ?: pointBounds(x, y)
        val ordinal = localOrdinal.incrementAndGet()
        val tapPlan = runCatching {
            HumanGestureEngine.planTap(
                target = target,
                viewport = viewport,
                profile = profile,
                seed = HumanGestureSeed.derive(commandId, kind.name, ordinal, floatArrayOf(x, y), durationMs),
                preferredDurationMs = 80L,
            )
        }.getOrElse {
            return record(commandId, HumanGestureDispatchTrace(profile, "plan_rejected", 0L, false, it.message))
        }
        val path = AndroidGestureRenderer.tapPath(tapPlan)
        val boundedDuration = durationMs.coerceIn(450L, 3_000L)
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, boundedDuration))
            .build()
        val outcome = dispatchAndAwait(service, gesture, boundedDuration)
        return record(commandId, HumanGestureDispatchTrace(profile, "humanized_path", boundedDuration, outcome.accepted, outcome.reason))
    }

    fun swipe(
        service: CycloneAccessibilityService,
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
        durationMs: Long,
        preference: HumanizePreference,
        kind: RuntimeGestureKind,
        commandId: String? = null,
    ): Boolean {
        val profile = HumanGestureRuntimePolicy.resolve(preference, kind)
        if (profile == HumanizeProfile.OFF) {
            val boundedDuration = durationMs.coerceIn(100L, 3_000L)
            val outcome = legacySwipe(service, x1, y1, x2, y2, boundedDuration)
            return record(commandId, HumanGestureDispatchTrace(profile, "legacy_straight", boundedDuration, outcome.accepted, outcome.reason))
        }
        val viewport = viewport(service)
        val ordinal = localOrdinal.incrementAndGet()
        val plan = runCatching {
            HumanGestureEngine.planSwipe(
                start = GesturePoint(x1, y1),
                end = GesturePoint(x2, y2),
                viewport = viewport,
                profile = profile,
                seed = HumanGestureSeed.derive(
                    commandId,
                    kind.name,
                    ordinal,
                    floatArrayOf(x1, y1, x2, y2),
                    durationMs,
                ),
                preferredDurationMs = durationMs,
            )
        }.getOrElse {
            return record(commandId, HumanGestureDispatchTrace(profile, "plan_rejected", 0L, false, it.message))
        }
        val path = AndroidGestureRenderer.strokePath(plan)
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, plan.durationMs))
            .build()
        val outcome = dispatchAndAwait(service, gesture, plan.durationMs)
        return record(commandId, HumanGestureDispatchTrace(profile, "humanized_path", plan.durationMs, outcome.accepted, outcome.reason))
    }

    private fun viewport(service: CycloneAccessibilityService): GestureBounds {
        val metrics = service.resources.displayMetrics
        return GestureBounds(0f, 0f, metrics.widthPixels.toFloat(), metrics.heightPixels.toFloat())
    }

    private fun pointBounds(x: Float, y: Float): GestureBounds = GestureBounds(
        left = x - 0.5f,
        top = y - 0.5f,
        right = x + 0.5f,
        bottom = y + 0.5f,
    )

    private fun UiBounds.toGestureBounds(): GestureBounds = GestureBounds(
        left.toFloat(),
        top.toFloat(),
        right.toFloat(),
        bottom.toFloat(),
    )

    private fun legacyTap(service: CycloneAccessibilityService, x: Float, y: Float): GestureDispatchOutcome {
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 80L))
            .build()
        return dispatchAndAwait(service, gesture, 80L)
    }

    private fun legacyLongPress(
        service: CycloneAccessibilityService,
        x: Float,
        y: Float,
        durationMs: Long,
    ): GestureDispatchOutcome {
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs.coerceIn(450L, 3_000L)))
            .build()
        return dispatchAndAwait(service, gesture, durationMs.coerceIn(450L, 3_000L))
    }

    private fun legacySwipe(
        service: CycloneAccessibilityService,
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
        durationMs: Long,
    ): GestureDispatchOutcome {
        val path = Path().apply { moveTo(x1, y1); lineTo(x2, y2) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs.coerceIn(100L, 3_000L)))
            .build()
        return dispatchAndAwait(service, gesture, durationMs.coerceIn(100L, 3_000L))
    }

    /**
     * Queue the stroke, make overlay chrome pass through for its duration, then wait for completion.
     * Cancelled or not-queued is a hard failure (retry is allowed). A timeout after a successful
     * queue is accepted so PhoneToolExecutor will not fire a second click channel.
     */
    private fun dispatchAndAwait(
        service: CycloneAccessibilityService,
        gesture: GestureDescription,
        durationMs: Long,
    ): GestureDispatchOutcome = OverlayGesturePassthrough.withHostPassthrough {
        val done = CountDownLatch(1)
        val completed = AtomicBoolean(false)
        val cancelled = AtomicBoolean(false)
        val callback = object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                completed.set(true)
                done.countDown()
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                cancelled.set(true)
                done.countDown()
            }
        }
        val queued = service.dispatchGesture(gesture, callback, Handler(callbackLooper))
        if (!queued) return@withHostPassthrough GestureDispatchOutcome(false, "not_queued")
        val finished = done.await(FastPathTimings.gestureAwaitBudgetMs(durationMs), TimeUnit.MILLISECONDS)
        when {
            completed.get() -> GestureDispatchOutcome(true)
            cancelled.get() -> GestureDispatchOutcome(false, "gesture_cancelled")
            !finished -> GestureDispatchOutcome(true, "gesture_timeout")
            else -> GestureDispatchOutcome(false, "gesture_unknown")
        }
    }
}
