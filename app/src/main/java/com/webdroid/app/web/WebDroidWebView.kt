package com.webdroid.app.web

import android.annotation.SuppressLint
import android.content.Context
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.webkit.WebView
import kotlin.math.abs

/**
 * WebView that turns an over-pull at the very top of the page into a native
 * pull gesture (refresh / menu), the way Chrome does: the pull only starts
 * once the page itself reports it cannot scroll further up, so inner
 * scrollers and `overscroll-behavior` keep working.
 */
@SuppressLint("ViewConstructor")
class WebDroidWebView(context: Context) : WebView(context) {

    interface PullCallbacks {
        fun onPullStart()
        fun onPull(distancePx: Float)
        fun onPullEnd(distancePx: Float)
    }

    var pullCallbacks: PullCallbacks? = null
    var pullEnabled: Boolean = true

    /** Notified when the page scroll position changes (for the mini toolbar). */
    var onScrollChangedListener: ((scrollY: Int, oldScrollY: Int) -> Unit)? = null

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var pullStartY = 0f
    private var overscrolledTop = false
    private var pulling = false

    override fun onOverScrolled(scrollX: Int, scrollY: Int, clampedX: Boolean, clampedY: Boolean) {
        super.onOverScrolled(scrollX, scrollY, clampedX, clampedY)
        if (clampedY && scrollY == 0) overscrolledTop = true
    }

    override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
        super.onScrollChanged(l, t, oldl, oldt)
        if (t > 0) overscrolledTop = false
        onScrollChangedListener?.invoke(t, oldt)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                overscrolledTop = false
                pulling = false
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (pulling) {
                    pulling = false
                    pullCallbacks?.onPullEnd(0f)
                    return true
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (pulling) {
                    pullCallbacks?.onPull((event.y - pullStartY).coerceAtLeast(0f))
                    return true
                }
                val dy = event.y - downY
                val dx = event.x - downX
                if (pullEnabled && overscrolledTop && event.pointerCount == 1 &&
                    dy > touchSlop && abs(dy) > abs(dx) * 1.2f && scrollY == 0
                ) {
                    pulling = true
                    pullStartY = event.y
                    // Hand the gesture over: the page sees a cancelled touch.
                    val cancel = MotionEvent.obtain(event)
                    cancel.action = MotionEvent.ACTION_CANCEL
                    super.onTouchEvent(cancel)
                    cancel.recycle()
                    pullCallbacks?.onPullStart()
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (pulling) {
                    pulling = false
                    val distance = if (event.actionMasked == MotionEvent.ACTION_UP) {
                        (event.y - pullStartY).coerceAtLeast(0f)
                    } else 0f
                    pullCallbacks?.onPullEnd(distance)
                    return true
                }
            }
        }
        return super.onTouchEvent(event)
    }
}
