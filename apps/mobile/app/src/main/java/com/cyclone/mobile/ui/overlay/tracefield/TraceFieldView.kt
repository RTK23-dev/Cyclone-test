package com.cyclone.mobile.ui.overlay.tracefield

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.graphics.Typeface
import android.os.SystemClock
import android.view.Choreographer
import android.view.View
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * Full-screen, non-touchable canvas for the Trace Field. Draws nothing and stays INVISIBLE unless
 * the choreographer says the field is visible, so an idle phone composites no extra layer.
 */
@SuppressLint("ViewConstructor")
internal class TraceFieldView(
    context: Context,
    private val onShaderFailure: (Throwable) -> Unit,
) : View(context), TraceFieldCaptureGate.Surface {
    private val density = resources.displayMetrics.density
    private val events = ConcurrentLinkedQueue<TraceEvent>()
    private var choreographer: TraceFieldChoreographer? = null
    private var shader: RuntimeShader? = null
    private var atlas: Bitmap? = null
    private val paint = Paint()
    private var captureHidden = false
    private val pendingHidden = mutableListOf<() -> Unit>()
    private var disabled = false

    var mode: TraceFieldMode = TraceFieldMode.FIELD
        set(value) { field = value; choreographer?.mode = value; kick() }
    var reduceMotion: Boolean = false
        set(value) { field = value; choreographer?.reduceMotion = value }

    /** Bottom-center Aurora pill area; the field never draws over Cyclone's own entry point. */
    private val exclusionWidth = 176f * density
    private val exclusionHeight = 104f * density

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        visibility = INVISIBLE
        setWillNotDraw(false)
    }

    /** Thread-safe: any thread may publish an attention event. */
    fun publish(event: TraceEvent) {
        if (disabled) return
        events.add(event)
        post { kick() }
    }

    private fun kick() {
        if (disabled || width == 0) {
            if (!disabled) requestLayout()
            return
        }
        if (!captureHidden && visibility != VISIBLE && (events.isNotEmpty() || choreographer?.frame(now())?.visible == true)) {
            visibility = VISIBLE
        }
        postInvalidateOnAnimation()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return
        val previous = choreographer
        choreographer = TraceFieldChoreographer(w.toFloat(), h.toFloat(), density, mode, reduceMotion).also { next ->
            // Rotation mid-task: keep the task alive in the new geometry.
            if (previous != null && previous.phase != TracePhase.OFF) {
                next.onEvent(TraceEvent.Wake(w / 2f, h.toFloat()), now())
            }
        }
        if (shader == null) buildShader()
        kick()
    }

    private fun buildShader() {
        try {
            val cellW = (9f * density).roundToInt().coerceAtLeast(8)
            val cellH = (12f * density).roundToInt().coerceAtLeast(10)
            val bitmap = buildAtlas(cellW, cellH)
            val runtime = RuntimeShader(TraceFieldShader.SOURCE)
            runtime.setInputShader("atlas", BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP))
            runtime.setFloatUniform("cell", cellW.toFloat(), cellH.toFloat())
            runtime.setFloatUniform("glyphCount", TraceFieldShader.GLYPHS.length.toFloat())
            runtime.setFloatUniform("lensSoft", 44f * density)
            runtime.setColorUniform("tint", TINT)
            runtime.setColorUniform("hot", HOT)
            runtime.setColorUniform("warm", WARM)
            atlas = bitmap
            shader = runtime
            paint.shader = runtime
        } catch (failure: Throwable) {
            disabled = true
            visibility = GONE
            onShaderFailure(failure)
        }
    }

    private fun buildAtlas(cellW: Int, cellH: Int): Bitmap {
        val glyphs = TraceFieldShader.GLYPHS
        val bitmap = Bitmap.createBitmap(cellW * glyphs.length, cellH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
            // 8.5dp type gives ~6dp cap height: readable only when you look for it.
            textSize = 8.5f * density
        }
        val metrics = text.fontMetrics
        val baseline = cellH / 2f - (metrics.ascent + metrics.descent) / 2f
        glyphs.forEachIndexed { index, glyph ->
            canvas.drawText(glyph.toString(), index * cellW + cellW / 2f, ceil(baseline), text)
        }
        return bitmap
    }

    override fun onDraw(canvas: Canvas) {
        val runtime = shader ?: return
        val machine = choreographer ?: return
        val now = now()
        while (true) {
            val event = events.poll() ?: break
            machine.onEvent(event, now)
        }
        val frame = machine.frame(now)
        if (!frame.visible) {
            visibility = INVISIBLE
            return
        }
        if (captureHidden) return
        try {
            runtime.setFloatUniform("res", width.toFloat(), height.toFloat())
            runtime.setFloatUniform("clock", frame.glyphClock)
            runtime.setFloatUniform("seed", frame.seed)
            runtime.setFloatUniform("lens", frame.lensX, frame.lensY, frame.lensHalfW, frame.lensHalfH)
            runtime.setFloatUniform("lensCorner", frame.lensCorner)
            runtime.setFloatUniform("intensity", frame.intensity)
            runtime.setFloatUniform("ripple", frame.rippleX, frame.rippleY, frame.rippleRadius, frame.rippleStrength)
            runtime.setFloatUniform("scan", frame.scanY, frame.scanStrength)
            runtime.setFloatUniform("flow", frame.flow)
            runtime.setFloatUniform("rain", frame.rain)
            runtime.setFloatUniform("scramble", frame.scramble)
            runtime.setFloatUniform("edge", frame.edge)
            runtime.setFloatUniform("edgeHead", frame.edgeHead)
            runtime.setFloatUniform("warmth", frame.warmth)
            val left = (width - exclusionWidth) / 2f
            runtime.setFloatUniform("excl", left, height - exclusionHeight, left + exclusionWidth, height.toFloat())
            canvas.drawPaint(paint)
        } catch (failure: Throwable) {
            disabled = true
            visibility = GONE
            onShaderFailure(failure)
            return
        }
        if (frame.animating || events.isNotEmpty()) postInvalidateOnAnimation()
    }

    override fun hide(onHidden: () -> Unit) {
        val work = Runnable {
            pendingHidden += onHidden
            if (captureHidden && pendingHidden.size > 1) return@Runnable
            captureHidden = true
            val wasVisible = visibility == VISIBLE
            visibility = INVISIBLE
            if (!wasVisible) {
                flushHidden()
                return@Runnable
            }
            // Two frame callbacks: the first commits INVISIBLE, the second runs after it is composited.
            Choreographer.getInstance().postFrameCallback {
                Choreographer.getInstance().postFrameCallback { flushHidden() }
            }
        }
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) work.run() else post(work)
    }

    private fun flushHidden() {
        val callbacks = pendingHidden.toList()
        pendingHidden.clear()
        callbacks.forEach { runCatching { it() } }
    }

    override fun restore() {
        post {
            if (TraceFieldCaptureGate.openHolds() > 0) return@post
            captureHidden = false
            kick()
        }
    }

    fun release() {
        disabled = true
        shader = null
        paint.shader = null
        atlas?.recycle()
        atlas = null
        flushHidden()
    }

    private fun now(): Double = SystemClock.uptimeMillis() / 1000.0

    companion object {
        private const val TINT = 0x804A8DFF.toInt()
        private const val HOT = 0xB3E8F4FF.toInt()
        private const val WARM = 0xB3FFC46B.toInt()
    }
}
