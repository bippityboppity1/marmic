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
 * The timer is armed and cancelled in [dispatchTouchEvent] rather than
 * [onInterceptTouchEvent], because a ViewGroup is only asked to intercept the
 * rest of a gesture once one of its children has claimed the DOWN. A widget
 * whose layout is entirely non-clickable claims nothing, and intercept would
 * then never see the UP — leaving a timer running that fires a long-press the
 * user never made.
 *
 * Nothing is consumed on the way through, so the widget's own buttons and lists
 * keep working. Once the timer has actually fired we do start intercepting, so
 * that lifting a finger after a long-press does not also register as a tap on
 * whatever was underneath it.
 */
class PlainWidgetHostView(context: Context) : AppWidgetHostView(context) {

    var onLongPress: (() -> Unit)? = null

    /**
     * The argb pair currently applied as a duotone filter, so the hardware
     * layer is only rebuilt when the palette actually changes rather than on
     * every recomposition the clock happens to cause.
     */
    var appliedDuotone: Pair<Int, Int>? = null

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val longPressTimeout = ViewConfiguration.getLongPressTimeout().toLong()
    private var downX = 0f
    private var downY = 0f
    private var triggered = false

    private val longPressRunnable = Runnable {
        triggered = true
        onLongPress?.invoke()
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
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
        return super.dispatchTouchEvent(ev)
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean = triggered

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
