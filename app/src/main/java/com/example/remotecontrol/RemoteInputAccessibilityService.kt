package com.example.remotecontrol

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.util.Log
import android.view.accessibility.AccessibilityEvent

/**
 * RemoteInputAccessibilityService
 *
 * Injects synthetic gestures on behalf of a remote controller. This service
 * only becomes active once the device owner manually enables it in
 * Settings > Accessibility — Android does not allow silently enabling
 * accessibility services from code, which is intentional and should not be
 * worked around (e.g. do not attempt Settings.Secure writes that require
 * WRITE_SECURE_SETTINGS; that is a red flag for malware behavior and most
 * such attempts fail on non-rooted devices anyway).
 *
 * Wire `performTap` / `performSwipe` up to your transport layer's incoming
 * command handler.
 */
class RemoteInputAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "RemoteInputA11yService"

        // Simple singleton handle so other components (e.g. your WebRTC data
        // channel handler) can reach the running service instance. Null when
        // the user hasn't enabled the service.
        @Volatile var instance: RemoteInputAccessibilityService? = null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "Accessibility service connected (remote input enabled)")
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Not used for input injection; only required override.
    }

    override fun onInterrupt() {}

    /** Injects a single tap at (x, y) in screen pixel coordinates. */
    fun performTap(x: Float, y: Float, durationMs: Long = 50) {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        dispatchGesture(gesture, null, null)
    }

    /** Injects a swipe/drag from (x1,y1) to (x2,y2) over durationMs. */
    fun performSwipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long = 200) {
        val path = Path().apply {
            moveTo(x1, y1)
            lineTo(x2, y2)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        dispatchGesture(gesture, null, null)
    }

    /** Convenience for pinch/zoom or multi-touch — extend as needed. */
    fun performMultiStrokeGesture(strokes: List<GestureDescription.StrokeDescription>) {
        val builder = GestureDescription.Builder()
        strokes.forEach { builder.addStroke(it) }
        dispatchGesture(builder.build(), null, null)
    }
}
