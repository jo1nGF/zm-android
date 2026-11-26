package com.ozma.android

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View

/** Transparent on-screen gamepad drawn over the video. D-pad + left stick area on the left, A/B/X/Y + shoulders on the right. */
class PadView(ctx: Context, private val rt: OzmaRuntime) : View(ctx) {
    private class Btn(val code: Int, val label: String, var cx: Float = 0f, var cy: Float = 0f, var r: Float = 0f, var pressed: Boolean = false)
    private val btns = listOf(
        Btn(OzmaRuntime.BTN_UP, "▲"), Btn(OzmaRuntime.BTN_DOWN, "▼"), Btn(OzmaRuntime.BTN_LEFT, "◀"), Btn(OzmaRuntime.BTN_RIGHT, "▶"),
        Btn(OzmaRuntime.BTN_A, "A"), Btn(OzmaRuntime.BTN_B, "B"), Btn(OzmaRuntime.BTN_X, "X"), Btn(OzmaRuntime.BTN_Y, "Y"),
        Btn(OzmaRuntime.BTN_LSHOULDER, "L"), Btn(OzmaRuntime.BTN_RSHOULDER, "R"),
        Btn(OzmaRuntime.BTN_BACK, "SEL"), Btn(OzmaRuntime.BTN_START, "START"))
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(70, 255, 255, 255) }
    private val fillOn = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(150, 255, 255, 255) }
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(200, 0, 0, 0); textAlign = Paint.Align.CENTER }
    private val owner = HashMap<Int, Btn?>()   // pointerId -> button currently held
    var visibleControls = true

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        val u = minOf(w, h) / 9f                         // unit: 1/9 of the short side
        val lx = u * 2.6f; val ly = h - u * 3.0f         // d-pad centre
        fun set(c: Int, x: Float, y: Float, r: Float) { btns.first { it.code == c }.apply { cx = x; cy = y; this.r = r } }
        set(OzmaRuntime.BTN_UP, lx, ly - u * 1.25f, u * 0.65f); set(OzmaRuntime.BTN_DOWN, lx, ly + u * 1.25f, u * 0.65f)
        set(OzmaRuntime.BTN_LEFT, lx - u * 1.25f, ly, u * 0.65f); set(OzmaRuntime.BTN_RIGHT, lx + u * 1.25f, ly, u * 0.65f)
        val rx = w - u * 2.6f; val ry = h - u * 3.0f      // face buttons centre
        set(OzmaRuntime.BTN_A, rx + u * 1.1f, ry, u * 0.7f); set(OzmaRuntime.BTN_B, rx, ry + u * 1.1f, u * 0.7f)
        set(OzmaRuntime.BTN_X, rx, ry - u * 1.1f, u * 0.7f); set(OzmaRuntime.BTN_Y, rx - u * 1.1f, ry, u * 0.7f)
        set(OzmaRuntime.BTN_LSHOULDER, u * 1.6f, u * 1.0f, u * 0.75f); set(OzmaRuntime.BTN_RSHOULDER, w - u * 1.6f, u * 1.0f, u * 0.75f)
        set(OzmaRuntime.BTN_BACK, u * 1.6f, h - u * 0.9f, u * 0.6f); set(OzmaRuntime.BTN_START, w - u * 1.6f, h - u * 0.9f, u * 0.6f)
        txt.textSize = u * 0.55f
    }

    override fun onDraw(c: Canvas) {
        if (!visibleControls) return
        for (b in btns) { c.drawCircle(b.cx, b.cy, b.r, if (b.pressed) fillOn else fill); c.drawText(b.label, b.cx, b.cy + txt.textSize * 0.35f, txt) }
    }

    private fun hit(x: Float, y: Float): Btn? = btns.minByOrNull { val dx = it.cx - x; val dy = it.cy - y; dx * dx + dy * dy }
        ?.takeIf { val dx = it.cx - x; val dy = it.cy - y; dx * dx + dy * dy <= (it.r * 1.4f) * (it.r * 1.4f) }

    private fun setPressed(b: Btn, down: Boolean) { if (b.pressed != down) { b.pressed = down; rt.button(b.code, down) } }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!visibleControls) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = e.actionIndex; val b = hit(e.getX(i), e.getY(i)); owner[e.getPointerId(i)] = b; b?.let { setPressed(it, true) }
            }
            MotionEvent.ACTION_MOVE -> for (i in 0 until e.pointerCount) {          // sliding a thumb across the d-pad moves the press
                val id = e.getPointerId(i); val old = owner[id]; val nb = hit(e.getX(i), e.getY(i))
                if (nb !== old) { old?.let { b -> if (owner.values.count { it === b } == 1) setPressed(b, false) }; owner[id] = nb; nb?.let { setPressed(it, true) } }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                val id = e.getPointerId(e.actionIndex); val b = owner.remove(id)
                b?.let { if (owner.values.none { o -> o === it }) setPressed(it, false) }
                if (e.actionMasked == MotionEvent.ACTION_CANCEL) { btns.forEach { setPressed(it, false) }; owner.clear() }
            }
        }
        invalidate(); return true
    }
}
