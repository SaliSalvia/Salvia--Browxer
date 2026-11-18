package com.salvia.salviabrowxer.browser

import android.content.Context
import android.graphics.Rect
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout

/**
 * Root layout that stacks the native browser (bottom) under the transparent React UI (top).
 *
 * The UI WebView is drawn above the page so its floating button, sheets and menus stay visible,
 * but a WebView swallows every touch inside its bounds. This layout therefore decides, once per
 * gesture, who receives it: the page when the finger lands inside the browser rectangle and no UI
 * overlay is open and the finger is not on a "hot" UI element (the floating button), otherwise
 * the UI. The whole gesture then stays with that target.
 */
class TouchRoutingLayout(context: Context) : FrameLayout(context) {

    var uiView: View? = null
    var browserView: View? = null
    var fullscreenView: View? = null

    /** Left inset (system bars) between this root view and the UI WebView's own coordinate space. */
    var uiOffsetX: Int = 0
    /** Top inset (status bar) between this root view and the UI WebView's own coordinate space. */
    var uiOffsetY: Int = 0

    var browserRect: Rect = Rect()
    var browserVisible: Boolean = false
    var shield: Boolean = false
    var hotRects: List<Rect> = emptyList()

    var onBrowserGestureStart: (() -> Unit)? = null
    var onUiGestureStart: (() -> Unit)? = null

    private var target: View? = null

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        val full = fullscreenView
        if (full != null && full.visibility == View.VISIBLE) {
            return full.dispatchTouchEvent(ev)
        }
        val ui = uiView
        val browser = browserView
        if (ui == null || browser == null) {
            return super.dispatchTouchEvent(ev)
        }

        val action = ev.actionMasked
        if (action == MotionEvent.ACTION_DOWN) {
            // browserRect / hotRects arrive in the UI WebView's CSS coordinate space, which starts
            // below the status bar once edge-to-edge padding is applied to contentHolder.
            // Translate the root-view coordinates into that space before comparing.
            val x = ev.x.toInt() - uiOffsetX
            val y = ev.y.toInt() - uiOffsetY
            val toBrowser = browserVisible &&
                !shield &&
                browserRect.contains(x, y) &&
                hotRects.none { it.contains(x, y) }
            if (toBrowser) {
                target = browser
                onBrowserGestureStart?.invoke()
            } else {
                target = ui
                onUiGestureStart?.invoke()
            }
        }

        val current = target ?: ui
        val handled = current.dispatchTouchEvent(ev)
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            target = null
        }
        return handled
    }
}
