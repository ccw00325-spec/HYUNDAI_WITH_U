package com.withu.app

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView

/**
 * 위디 버블: 앱 화면이 없을 때 다른 앱 위에 떠 있는 흰 동그라미.
 * 누르면 WiTH U 홈(사이렌 버튼)으로, 사고 확인 대기 중이면 확인 카드로 돌아온다. 끌어서 옮길 수 있다.
 * 길게(0.8초) 누르면 사이렌: 주행 중(D단)에는 Pleos가 앱 화면을 가려 홈의 사이렌 버튼을 누를 수 없지만, 버블은 다른 앱 위에 남아 있어 언제든 알릴 수 있다.
 * state: rec(초록 점) / alert(주황 테두리)
 */
class Bubble(private val ctx: Context) {
    private val ui = Handler(Looper.getMainLooper())
    private val wm = ctx.getSystemService(WindowManager::class.java)
    private var root: FrameLayout? = null
    private var face: ImageView? = null
    private var ring: GradientDrawable? = null
    private var dot: View? = null
    private var lp: WindowManager.LayoutParams? = null
    @Volatile var shown = false; private set
    private var state = "rec"

    fun show(reason: String) = ui.post {
        if (shown) return@post
        if (!Settings.canDrawOverlays(ctx)) { L("BUBBLE skipped reason=$reason canDrawOverlays=false"); return@post }
        try { if (root == null) build(); wm.addView(root, lp); shown = true; L("BUBBLE shown reason=$reason state=$state") }
        catch (e: Exception) { L("BUBBLE show FAILED ${e.javaClass.simpleName}: ${e.message}") }
    }
    fun hide(reason: String) = ui.post {
        if (!shown) return@post
        try { wm.removeView(root); shown = false; L("BUBBLE hidden reason=$reason") } catch (e: Exception) { L("BUBBLE hide FAILED $e") }
    }
    fun setState(s: String) = ui.post { state = s; apply(); L("BUBBLE state=$s shown=$shown") }

    private fun apply() {
        face?.setImageResource(if (state == "alert") R.drawable.wedi_worry else R.drawable.wedi_calm)
        ring?.setStroke(if (state == "alert") 10 else 0, Color.rgb(255, 90, 54))
        dot?.visibility = if (state == "alert") View.GONE else View.VISIBLE
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun build() {
        ring = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE) }
        root = FrameLayout(ctx).apply { background = ring; elevation = 16f; contentDescription = "WiTH U 열기" }
        face = ImageView(ctx).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        root!!.addView(face, FrameLayout.LayoutParams(104, 104, Gravity.CENTER))
        dot = View(ctx).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.rgb(16, 185, 129)); setStroke(5, Color.WHITE) } }
        root!!.addView(dot, FrameLayout.LayoutParams(40, 40, Gravity.END or Gravity.BOTTOM))
        apply()
        lp = WindowManager.LayoutParams(150, 150, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS, PixelFormat.TRANSLUCENT)
            .apply { gravity = Gravity.TOP or Gravity.START; x = 2300; y = 520 }
        var dx0 = 0f; var dy0 = 0f; var sx = 0; var sy = 0; var moved = false; var longFired = false
        val longPress = Runnable { if (!moved) { longFired = true; ring?.setStroke(10, Color.rgb(255, 90, 54)); L("BUBBLE long-press -> siren"); RecorderService.inst?.siren("bubble_long_press") } }
        root!!.setOnTouchListener { v, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { dx0 = e.rawX; dy0 = e.rawY; sx = lp!!.x; sy = lp!!.y; moved = false; longFired = false; ui.postDelayed(longPress, LONG_PRESS_MS); true }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - dx0; val dy = e.rawY - dy0
                    if (kotlin.math.abs(dx) + kotlin.math.abs(dy) > 12) { moved = true; ui.removeCallbacks(longPress) }
                    if (moved) { lp!!.x = sx + dx.toInt(); lp!!.y = sy + dy.toInt(); wm.updateViewLayout(v, lp) }
                    true
                }
                MotionEvent.ACTION_UP -> { ui.removeCallbacks(longPress); if (moved) L("BUBBLE dragged to ${lp!!.x},${lp!!.y}") else if (!longFired) tap(); true }
                MotionEvent.ACTION_CANCEL -> { ui.removeCallbacks(longPress); true }
                else -> false
            }
        }
    }

    companion object { const val LONG_PRESS_MS = 800L }

    private fun tap() {
        L("BUBBLE tapped state=$state")
        if (state == "alert") AppState.go(Route.CONFIRM) else if (AppState.route.value == Route.ONBOARD) Unit else if (AppState.route.value != Route.GUIDE) AppState.home()
        try { ctx.startActivity(Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)) }
        catch (e: Exception) { L("BUBBLE tap FAILED $e") }
    }
}
