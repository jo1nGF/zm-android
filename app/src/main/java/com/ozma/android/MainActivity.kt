package com.ozma.android

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView

class MainActivity : Activity(), SurfaceHolder.Callback {
    private lateinit var rt: OzmaRuntime
    private lateinit var pad: PadView
    private lateinit var msg: TextView
    private lateinit var restart: Button
    private var started = false

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        rt = OzmaRuntime(this) { rc, log -> runOnUiThread { showExit(rc, log) } }
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        val sv = SurfaceView(this).also { it.holder.addCallback(this) }
        pad = PadView(this, rt)
        msg = TextView(this).apply { setTextColor(Color.WHITE); textSize = 12f; setPadding(40, 40, 40, 40); visibility = View.GONE; setBackgroundColor(0xDD000000.toInt()) }
        restart = Button(this).apply { text = "Reiniciar OZMA"; visibility = View.GONE; setOnClickListener { msg.visibility = View.GONE; visibility = View.GONE; pad.visibleControls = true; rt.start() } }
        root.addView(sv); root.addView(pad); root.addView(msg)
        root.addView(restart, FrameLayout.LayoutParams(-2, -2, android.view.Gravity.BOTTOM or android.view.Gravity.CENTER_HORIZONTAL))
        setContentView(root)
        hideBars()
    }

    private fun hideBars() {
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)
        if (android.os.Build.VERSION.SDK_INT >= 28) window.attributes = window.attributes.also { it.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES }
    }
    override fun onWindowFocusChanged(f: Boolean) { super.onWindowFocusChanged(f); if (f) hideBars() }

    private fun showExit(rc: Int, log: String) {
        pad.visibleControls = false; pad.invalidate()
        msg.text = "OZMA terminou (código $rc)\n\n$log\n\nLog completo: ${rt.logFile.path}"
        msg.visibility = View.VISIBLE; restart.visibility = View.VISIBLE
    }

    // ---- surface lifecycle: start the process once we have somewhere to draw
    override fun surfaceCreated(h: SurfaceHolder) { rt.attachSurface(h); if (!started) { started = true; rt.start() } }
    override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, hh: Int) {}
    override fun surfaceDestroyed(h: SurfaceHolder) { rt.attachSurface(null) }
    override fun onDestroy() { super.onDestroy(); if (isFinishing) rt.stop() }

    // ---- physical gamepad / keyboard
    private fun map(k: Int): Int = when (k) {
        KeyEvent.KEYCODE_BUTTON_A -> OzmaRuntime.BTN_A; KeyEvent.KEYCODE_BUTTON_B -> OzmaRuntime.BTN_B
        KeyEvent.KEYCODE_BUTTON_X -> OzmaRuntime.BTN_X; KeyEvent.KEYCODE_BUTTON_Y -> OzmaRuntime.BTN_Y
        KeyEvent.KEYCODE_BUTTON_L1 -> OzmaRuntime.BTN_LSHOULDER; KeyEvent.KEYCODE_BUTTON_R1 -> OzmaRuntime.BTN_RSHOULDER
        KeyEvent.KEYCODE_BUTTON_SELECT -> OzmaRuntime.BTN_BACK; KeyEvent.KEYCODE_BUTTON_START -> OzmaRuntime.BTN_START
        KeyEvent.KEYCODE_BUTTON_THUMBL -> OzmaRuntime.BTN_LSTICK; KeyEvent.KEYCODE_BUTTON_THUMBR -> OzmaRuntime.BTN_RSTICK
        KeyEvent.KEYCODE_DPAD_UP -> OzmaRuntime.BTN_UP; KeyEvent.KEYCODE_DPAD_DOWN -> OzmaRuntime.BTN_DOWN
        KeyEvent.KEYCODE_DPAD_LEFT -> OzmaRuntime.BTN_LEFT; KeyEvent.KEYCODE_DPAD_RIGHT -> OzmaRuntime.BTN_RIGHT
        KeyEvent.KEYCODE_Z -> OzmaRuntime.BTN_A; KeyEvent.KEYCODE_X -> OzmaRuntime.BTN_B
        KeyEvent.KEYCODE_ENTER -> OzmaRuntime.BTN_START; KeyEvent.KEYCODE_SPACE -> OzmaRuntime.BTN_BACK
        else -> -1
    }
    override fun onKeyDown(k: Int, e: KeyEvent): Boolean { val b = map(k); if (b < 0) return super.onKeyDown(k, e); if (e.repeatCount == 0) rt.button(b, true); return true }
    override fun onKeyUp(k: Int, e: KeyEvent): Boolean { val b = map(k); if (b < 0) return super.onKeyUp(k, e); rt.button(b, false); return true }
    override fun onGenericMotionEvent(e: MotionEvent): Boolean {
        if ((e.source and InputDevice.SOURCE_JOYSTICK) == 0 || e.action != MotionEvent.ACTION_MOVE) return super.onGenericMotionEvent(e)
        fun a(v: Float) = (v * 32767).toInt()
        rt.axis(OzmaRuntime.AX_LX, a(e.getAxisValue(MotionEvent.AXIS_X))); rt.axis(OzmaRuntime.AX_LY, a(e.getAxisValue(MotionEvent.AXIS_Y)))
        rt.axis(OzmaRuntime.AX_RX, a(e.getAxisValue(MotionEvent.AXIS_Z))); rt.axis(OzmaRuntime.AX_RY, a(e.getAxisValue(MotionEvent.AXIS_RZ)))
        val hx = e.getAxisValue(MotionEvent.AXIS_HAT_X); val hy = e.getAxisValue(MotionEvent.AXIS_HAT_Y)
        rt.button(OzmaRuntime.BTN_LEFT, hx < -0.5f); rt.button(OzmaRuntime.BTN_RIGHT, hx > 0.5f)
        rt.button(OzmaRuntime.BTN_UP, hy < -0.5f); rt.button(OzmaRuntime.BTN_DOWN, hy > 0.5f)
        return true
    }
}
