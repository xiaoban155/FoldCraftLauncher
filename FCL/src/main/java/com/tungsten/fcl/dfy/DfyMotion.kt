package com.tungsten.fcl.dfy

import android.view.MotionEvent
import android.view.View

/**
 * 轻量动效工具：按压缩放 + 淡入上浮。
 * 只做微交互，不重造系统转场；时长与 FCL 页面切换动画（250ms）保持一致的手感。
 */
object DfyMotion {

    private const val PRESS_SCALE = 0.96f
    private const val PRESS_DURATION = 110L
    private const val ENTER_DURATION = 220L

    /** 给按钮加按压回弹：按下缩小，抬起恢复（不拦截点击事件） */
    fun press(view: View) {
        view.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN ->
                    v.animate().scaleX(PRESS_SCALE).scaleY(PRESS_SCALE).setDuration(PRESS_DURATION).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    v.animate().scaleX(1f).scaleY(1f).setDuration(PRESS_DURATION).start()
            }
            false
        }
    }

    /** 新内容淡入 + 上浮 12dp 进场 */
    fun fadeInUp(view: View, fromFar: Boolean = false) {
        view.animate().cancel()
        view.alpha = 0f
        view.translationY = if (fromFar) 28f else 12f
        view.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(ENTER_DURATION)
            .start()
    }
}
