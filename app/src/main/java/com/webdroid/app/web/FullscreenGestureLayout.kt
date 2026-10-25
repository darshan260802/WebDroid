package com.webdroid.app.web

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import com.webdroid.app.R
import kotlin.math.abs

/**
 * Wraps a site's own fullscreen video view and adds native gestures on top:
 * left-side vertical drag = brightness, right-side = volume, horizontal drag
 * = seek. Taps still reach the site's controls.
 */
@SuppressLint("ViewConstructor")
class FullscreenGestureLayout(
    context: Context,
    content: View,
    private val callbacks: Callbacks,
) : FrameLayout(context) {

    interface Callbacks {
        fun onBrightness(delta: Float): String
        fun onVolume(delta: Float): String
        fun onSeekPreview(seconds: Int): String
        fun onSeekCommit(seconds: Int)
    }

    private enum class Mode { NONE, SEEK, BRIGHTNESS, VOLUME }

    private val slop = ViewConfiguration.get(context).scaledTouchSlop * 1.5f
    private var mode = Mode.NONE
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var seekSeconds = 0f

    private val hint = TextView(context).apply {
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
        typeface = ResourcesCompat.getFont(context, R.font.inter_semibold)
        val pad = (14 * resources.displayMetrics.density).toInt()
        setPadding(pad * 3 / 2, pad, pad * 3 / 2, pad)
        background = GradientDrawable().apply {
            cornerRadius = 16 * resources.displayMetrics.density
            setColor(0x9E000000.toInt())
        }
        alpha = 0f
    }

    init {
        setBackgroundColor(Color.BLACK)
        addView(content, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(hint, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
            topMargin = (56 * resources.displayMetrics.density).toInt()
        })
    }

    private fun showHint(text: String) {
        hint.text = text
        hint.animate().cancel()
        hint.alpha = 1f
    }

    private fun hideHint() {
        hint.animate().alpha(0f).setStartDelay(350).setDuration(200).start()
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                mode = Mode.NONE
                downX = ev.x; downY = ev.y
                lastX = ev.x; lastY = ev.y
                seekSeconds = 0f
            }
            MotionEvent.ACTION_MOVE -> {
                if (ev.pointerCount > 1) return false
                val dx = ev.x - downX
                val dy = ev.y - downY
                if (mode == Mode.NONE && (abs(dx) > slop || abs(dy) > slop)) {
                    // The site's own controls own the top bar and the bottom area (its
                    // seek bar and buttons): gestures only start in the middle band,
                    // and seeking needs a clearly horizontal swipe.
                    if (downY < height * 0.18f || downY > height * 0.70f) return false
                    mode = when {
                        abs(dx) > abs(dy) * 2f -> Mode.SEEK
                        abs(dy) > abs(dx) * 1.5f && downX < width * 0.4f -> Mode.BRIGHTNESS
                        abs(dy) > abs(dx) * 1.5f && downX > width * 0.6f -> Mode.VOLUME
                        else -> return false
                    }
                    lastX = ev.x; lastY = ev.y
                    performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
                    return true
                }
            }
        }
        return false
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - lastX
                val dy = event.y - lastY
                lastX = event.x; lastY = event.y
                when (mode) {
                    Mode.SEEK -> {
                        seekSeconds += dx / width * 90f
                        showHint(callbacks.onSeekPreview(seekSeconds.toInt()))
                    }
                    Mode.BRIGHTNESS -> showHint(callbacks.onBrightness(-dy / (height * 0.8f)))
                    Mode.VOLUME -> showHint(callbacks.onVolume(-dy / (height * 0.8f)))
                    Mode.NONE -> Unit
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (mode == Mode.SEEK && seekSeconds.toInt() != 0) callbacks.onSeekCommit(seekSeconds.toInt())
                mode = Mode.NONE
                hideHint()
            }
        }
        return true
    }
}
