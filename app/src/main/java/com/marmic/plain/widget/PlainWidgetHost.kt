package com.marmic.plain.widget

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.util.SizeF
import android.view.MotionEvent
import android.view.ViewConfiguration
import kotlin.math.abs

/** Arbitrary but stable; changing it orphans every widget the user has added. */
const val PLAIN_HOST_ID = 0x504C4E

class PlainAppWidgetHost(context: Context) : AppWidgetHost(context, PLAIN_HOST_ID) {

    /** Invoked with the widget id when the user long-presses a hosted widget. */
    var onWidgetLongPress: ((Int) -> Unit)? = null

    override fun onCreateView(
        context: Context,
        appWidgetId: Int,
        appWidget: AppWidgetProviderInfo?,
    ): AppWidgetHostView = PlainWidgetHostView(context).apply {
        onLongPress = { this@PlainAppWidgetHost.onWidgetLongPress?.invoke(appWidgetId) }
    }
}

/**
 * A host view that can detect a long-press without stealing normal taps and
 * scrolls from the widget's own RemoteViews.
 *
 * We watch the intercept pass (which always sees the gesture first) and arm a
 * timer, but never return true — so the widget's own buttons and lists keep
 * working. Only the timer firing counts as a long-press.
 */
class PlainWidgetHostView(context: Context) : AppWidgetHostView(context) {

    var onLongPress: (() -> Unit)? = null

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val longPressTimeout = ViewConfiguration.getLongPressTimeout().toLong()
    private var downX = 0f
    private var downY = 0f
    private var triggered = false

    private val longPressRunnable = Runnable {
        triggered = true
        onLongPress?.invoke()
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                triggered = false
                postDelayed(longPressRunnable, longPressTimeout)
            }

            MotionEvent.ACTION_MOVE ->
                if (abs(ev.x - downX) > touchSlop || abs(ev.y - downY) > touchSlop) cancelLongPress()

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> cancelLongPress()
        }
        return false
    }

    override fun cancelLongPress() {
        super.cancelLongPress()
        removeCallbacks(longPressRunnable)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(longPressRunnable)
        super.onDetachedFromWindow()
    }

    /**
     * Tell the provider how much room it actually has. Without this, widgets lay
     * out at their declared minimum — which is why a month calendar can come up
     * showing a single agenda row.
     */
    fun reportSize(widthDp: Int, heightDp: Int) {
        if (widthDp <= 0 || heightDp <= 0) return
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                updateAppWidgetSize(Bundle(), listOf(SizeF(widthDp.toFloat(), heightDp.toFloat())))
            } else {
                @Suppress("DEPRECATION")
                updateAppWidgetSize(null, widthDp, heightDp, widthDp, heightDp)
            }
        }
    }
}
