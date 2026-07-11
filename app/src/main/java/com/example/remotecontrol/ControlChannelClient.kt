package com.example.remotecontrol

import android.util.Log
import org.json.JSONObject
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import java.net.URI
import java.nio.ByteBuffer

/**
 * ControlChannelClient
 *
 * One WebSocket connection carrying:
 *   - outbound: binary H.264 access units (video, agent -> controller)
 *   - inbound: JSON input commands (controller -> agent), e.g.
 *       {"type":"tap","x":123.0,"y":456.0}
 *       {"type":"swipe","x1":0,"y1":0,"x2":100,"y2":300,"durationMs":200}
 *       {"type":"stop"}
 *
 * A tiny 9-byte header is prefixed to each binary video frame so the
 * controller can reconstruct timing/keyframe info:
 *   [0]      flags (bit0 = keyframe)
 *   [1..8]   presentationTimeUs (big-endian long)
 *   [9..]    raw H.264 payload
 */
class ControlChannelClient(
    serverUri: URI,
    private val onCommand: (JSONObject) -> Unit,
    private val onOpenCallback: (() -> Unit)? = null,
    private val onCloseCallback: ((code: Int, reason: String) -> Unit)? = null
) : WebSocketClient(serverUri) {

    companion object {
        private const val TAG = "ControlChannelClient"
        const val FLAG_KEYFRAME = 0x1
    }

    override fun onOpen(handshakedata: ServerHandshake?) {
        Log.i(TAG, "Connected to relay")
        onOpenCallback?.invoke()
    }

    override fun onMessage(message: String?) {
        if (message == null) return
        try {
            val json = JSONObject(message)
            onCommand(json)
        } catch (e: Exception) {
            Log.w(TAG, "Ignoring malformed command: $message", e)
        }
    }

    // Binary messages aren't expected inbound (controller only sends JSON),
    // but handle gracefully rather than crash if the protocol is extended.
    override fun onMessage(bytes: ByteBuffer?) {
        Log.d(TAG, "Unexpected binary message from controller (${bytes?.remaining() ?: 0} bytes)")
    }

    override fun onClose(code: Int, reason: String?, remote: Boolean) {
        Log.i(TAG, "Relay connection closed: $code $reason (remote=$remote)")
        onCloseCallback?.invoke(code, reason ?: "")
    }

    override fun onError(ex: Exception?) {
        Log.e(TAG, "Relay connection error", ex)
    }

    /** Sends one encoded H.264 access unit with the small framing header described above. */
    fun sendVideoFrame(data: ByteArray, presentationTimeUs: Long, isKeyFrame: Boolean) {
        if (!isOpen) return
        val header = ByteBuffer.allocate(9)
        header.put((if (isKeyFrame) FLAG_KEYFRAME else 0).toByte())
        header.putLong(presentationTimeUs)
        val out = ByteArray(9 + data.size)
        System.arraycopy(header.array(), 0, out, 0, 9)
        System.arraycopy(data, 0, out, 9, data.size)
        send(out)
    }

    /** Dispatches a parsed input command to the accessibility service, if enabled. */
    fun defaultCommandHandler(json: JSONObject) {
        val svc = RemoteInputAccessibilityService.instance
        if (svc == null) {
            Log.w(TAG, "Received command but accessibility service is not enabled")
            return
        }
        when (json.optString("type")) {
            "tap" -> svc.performTap(
                json.getDouble("x").toFloat(),
                json.getDouble("y").toFloat()
            )
            "swipe" -> svc.performSwipe(
                json.getDouble("x1").toFloat(),
                json.getDouble("y1").toFloat(),
                json.getDouble("x2").toFloat(),
                json.getDouble("y2").toFloat(),
                json.optLong("durationMs", 200)
            )
            "stop" -> {
                Log.i(TAG, "Controller requested stop")
                // Hook: signal ScreenCaptureService.ACTION_STOP from here if desired.
            }
            else -> Log.w(TAG, "Unknown command type: ${json.optString("type")}")
        }
    }
}
