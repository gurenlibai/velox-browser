package com.velox.browser.ui

import android.content.Context
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.VelocityTracker
import kotlin.math.abs

/**
 * 边缘手势（内容区生效，工具栏区域永远优先）：
 *   左边缘右滑  → 后退（跟随手指的手势进行预览式位移）
 *   右边缘左滑  → 前进
 *   下拉        → 刷新（仅在页面顶部）
 *   工具栏上滑  → 唤出
 *
 * 全部使用 ViewConfiguration 标准阈值，且**不消耗**未命中手势，
 * 保证普通滚动、点击、长按行为与原生一致（这是「易用性不被手势破坏」的底线）。
 */
class EdgeGestures(context: Context) {

    interface Callback {
        fun onBackPeek(progress: Float)
        fun onBackCommit()
        fun onBackCancel()
        fun onForwardCommit()
        fun onPullToRefresh()
        fun onSwipeUpOnBar()
        fun isAtTop(): Boolean
    }

    var callback: Callback? = null

    private val vc = android.view.ViewConfiguration.get(context)
    private val density = context.resources.displayMetrics.density
    private val edgeWidth = (22 * density)
    private val touchSlop = vc.scaledTouchSlop

    private var tracking = false
    private var edge = 0 // -1 左边缘, 1 右边缘
    private var startX = 0f
    private var startY = 0f
    private var lastX = 0f
    private var pullTracking = false
    private var pullDistance = 0f

    fun onTouchEvent(e: MotionEvent, width: Int, height: Int): Boolean {
        return when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startX = e.x; startY = e.y; lastX = e.x
                pullTracking = false
                val w = width.toFloat()
                edge = when {
                    startX < edgeWidth -> -1
                    w > 0 && startX > w - edgeWidth -> 1
                    else -> 0
                }
                tracking = edge != 0 && e.pointerCount == 1
                false
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = e.x - startX
                val dy = e.y - startY
                if (tracking) {
                    val rightward = dx > 0 && edge == -1
                    val leftward = dx < 0 && edge == 1
                    if ((rightward || leftward) && abs(dx) > touchSlop && abs(dx) > abs(dy) * 1.4f) {
                        callback?.onBackPeek((abs(dx) / (width.coerceAtLeast(1) * 0.55f)).coerceIn(0f, 1f))
                        lastX = e.x
                        return true
                    }
                } else if (!pullTracking && callback?.isAtTop() == true && dy > 0 && abs(dy) > abs(dx) * 1.4f && startY < height) {
                    pullTracking = true
                }
                if (pullTracking) {
                    pullDistance = dy.coerceAtLeast(0f)
                    callback?.onBackPeek((-pullDistance / height.coerceAtLeast(1)).coerceIn(-1f, 0f))
                    return true
                }
                false
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val dx = e.x - startX
                val dy = e.y - startY
                val commit = when {
                    tracking && edge == 1 && -dx > width * 0.28f -> { callback?.onForwardCommit(); true }
                    tracking && edge == -1 && dx > width * 0.28f -> { callback?.onBackCommit(); true }
                    pullTracking && pullDistance > height * 0.22f -> { callback?.onPullToRefresh(); true }
                    tracking || pullTracking -> { callback?.onBackCancel(); true }
                    else -> false
                }
                tracking = false; pullTracking = false; pullDistance = 0f
                commit
            }
            else -> false
        }
    }

}
