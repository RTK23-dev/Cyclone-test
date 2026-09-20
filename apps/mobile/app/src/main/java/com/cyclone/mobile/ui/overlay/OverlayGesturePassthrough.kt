package com.cyclone.mobile.ui.overlay

import java.util.concurrent.atomic.AtomicInteger

/**
 * Temporarily makes Cyclone overlay windows ignore touches so an already-authorized
 * host [android.accessibilityservice.AccessibilityService.dispatchGesture] can land.
 *
 * Overlay buttons still never click host nodes. This flag is held only for the stroke
 * itself, not for the whole WORKING state, so Stop remains usable around the gesture.
 */
object OverlayGesturePassthrough {
    private val depth = AtomicInteger(0)
    @Volatile private var enabled = false
    @Volatile private var applyFlags: ((Boolean) -> Unit)? = null

    fun active(): Boolean = enabled

    fun bind(applyFlags: (Boolean) -> Unit) {
        this.applyFlags = applyFlags
    }

    fun unbind() {
        applyFlags = null
    }

    fun <T> withHostPassthrough(block: () -> T): T {
        val entered = depth.getAndIncrement() == 0
        if (entered) {
            enabled = true
            applyFlags?.invoke(true)
        }
        try {
            return block()
        } finally {
            if (depth.decrementAndGet() == 0) {
                enabled = false
                applyFlags?.invoke(false)
            }
        }
    }

    internal fun resetForTests() {
        depth.set(0)
        enabled = false
        applyFlags = null
    }
}
