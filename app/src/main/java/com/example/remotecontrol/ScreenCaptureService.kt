package com.example.remotecontrol

import android.app.*
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.DisplayMetrics
import android.util.Log
import android.view.Surface
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import org.json.JSONObject
import java.net.URI
import java.nio.ByteBuffer

/**
 * ScreenCaptureService
 *
 * Design principles (do not remove when extending):
 *  1. This service NEVER starts capture without a foreground notification visible
 *     to the device owner for the entire duration of capture.
 *  2. Capture can only begin after the user has explicitly granted the
 *     MediaProjection consent dialog (system-level, cannot be bypassed).
 *  3. The notification includes a "Stop sharing" action that immediately
 *     tears down the projection.
 *
 * This class only handles LOCAL encoding of the screen into H.264 access
 * units. Delivering those bytes to a remote peer (WebRTC/WebSocket) is a
 * separate concern — hook into onEncodedFrame() below.
 */
class ScreenCaptureService : Service() {

    companion object {
        private const val TAG = "ScreenCaptureService"
        const val CHANNEL_ID = "screen_capture_channel"
        const val NOTIFICATION_ID = 1001

        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"

        const val ACTION_START = "com.example.remotecontrol.action.START"
        const val ACTION_STOP = "com.example.remotecontrol.action.STOP"

        // Passed in alongside the MediaProjection consent result.
        const val EXTRA_RELAY_URL = "extra_relay_url"   // e.g. ws://your-server:8080
        const val EXTRA_ROOM_CODE = "extra_room_code"

        // Tune to device / bandwidth budget.
        private const val TARGET_BITRATE = 4_000_000 // 4 Mbps
        private const val TARGET_FRAMERATE = 30
        private const val I_FRAME_INTERVAL = 2 // seconds
    }

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var encoder: MediaCodec? = null
    private var inputSurface: Surface? = null
    private var encoderThread: Thread? = null
    @Volatile private var running = false
    private var controlChannel: ControlChannelClient? = null

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            Log.i(TAG, "MediaProjection stopped by system/user")
            stopCapture()
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                val resultData: Intent? = intent.getParcelableExtra(EXTRA_RESULT_DATA)
                val relayUrl = intent.getStringExtra(EXTRA_RELAY_URL)
                val roomCode = intent.getStringExtra(EXTRA_ROOM_CODE)
                if (resultCode != Activity.RESULT_CANCELED && resultData != null
                    && relayUrl != null && roomCode != null) {
                    startForeground(NOTIFICATION_ID, buildNotification())
                    connectControlChannel(relayUrl, roomCode)
                    startCapture(resultCode, resultData)
                } else {
                    Log.e(TAG, "Missing/invalid MediaProjection consent result; refusing to start")
                    stopSelf()
                }
            }
            ACTION_STOP -> stopCapture()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopCapture()
        super.onDestroy()
    }

    // ---- Relay connection -----------------------------------------------

    private fun connectControlChannel(relayUrl: String, roomCode: String) {
        val uri = URI("$relayUrl/?role=agent&code=$roomCode")
        val client = ControlChannelClient(
            serverUri = uri,
            onCommand = { json -> handleIncomingCommand(json) },
            onOpenCallback = { Log.i(TAG, "Control channel open (room $roomCode)") },
            onCloseCallback = { code, reason -> Log.i(TAG, "Control channel closed: $code $reason") }
        )
        controlChannel = client
        client.connect()
    }

    private fun handleIncomingCommand(json: JSONObject) {
        when (json.optString("type")) {
            "stop" -> stopCapture()
            else -> controlChannel?.defaultCommandHandler(json)
        }
    }

    // ---- Capture setup -----------------------------------------------

    private fun startCapture(resultCode: Int, resultData: Intent) {
        val projectionManager =
            getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val projection = projectionManager.getMediaProjection(resultCode, resultData)
        mediaProjection = projection
        projection.registerCallback(projectionCallback, null)

        val metrics = DisplayMetrics()
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val density = metrics.densityDpi

        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, TARGET_BITRATE)
            setInteger(MediaFormat.KEY_FRAME_RATE, TARGET_FRAMERATE)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL)
        }

        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val surface = codec.createInputSurface()
        codec.start()

        encoder = codec
        inputSurface = surface

        virtualDisplay = projection.createVirtualDisplay(
            "RemoteControlCapture",
            width, height, density,
            android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            surface, null, null
        )

        running = true
        encoderThread = Thread({ drainEncoder(codec) }, "EncoderDrainThread").apply { start() }

        Log.i(TAG, "Capture started: ${width}x${height} @${density}dpi")
    }

    /** Pulls encoded H.264 access units off the codec's output queue. */
    private fun drainEncoder(codec: MediaCodec) {
        val bufferInfo = MediaCodec.BufferInfo()
        while (running) {
            val outIndex = try {
                codec.dequeueOutputBuffer(bufferInfo, 10_000)
            } catch (e: IllegalStateException) {
                break
            }
            when {
                outIndex >= 0 -> {
                    val encodedData: ByteBuffer? = codec.getOutputBuffer(outIndex)
                    if (encodedData != null && bufferInfo.size > 0) {
                        encodedData.position(bufferInfo.offset)
                        encodedData.limit(bufferInfo.offset + bufferInfo.size)
                        val chunk = ByteArray(bufferInfo.size)
                        encodedData.get(chunk)
                        onEncodedFrame(chunk, bufferInfo.presentationTimeUs, bufferInfo.flags)
                    }
                    codec.releaseOutputBuffer(outIndex, false)
                }
                outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    // no output yet, loop
                }
                else -> {
                    // format changed / output buffers changed — ignore for raw H.264 Annex-B use
                }
            }
        }
    }

    /**
     * Hook point: wire this up to your WebRTC/WebSocket sender.
     * `flags` will have MediaCodec.BUFFER_FLAG_KEY_FRAME set on keyframes —
     * useful for requesting a fresh IDR when a new viewer joins.
     */
    private fun onEncodedFrame(data: ByteArray, presentationTimeUs: Long, flags: Int) {
        val isKeyFrame = (flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
        controlChannel?.sendVideoFrame(data, presentationTimeUs, isKeyFrame)
    }

    private fun stopCapture() {
        if (!running && encoder == null) return
        running = false
        try { encoderThread?.join(500) } catch (_: InterruptedException) {}
        encoderThread = null

        virtualDisplay?.release(); virtualDisplay = null
        inputSurface?.release(); inputSurface = null
        encoder?.let {
            try { it.stop() } catch (_: Exception) {}
            try { it.release() } catch (_: Exception) {}
        }
        encoder = null

        mediaProjection?.unregisterCallback(projectionCallback)
        mediaProjection?.stop()
        mediaProjection = null

        controlChannel?.close()
        controlChannel = null

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        Log.i(TAG, "Capture stopped")
    }

    // ---- Notification (mandatory visible-consent UI) ------------------

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Screen sharing",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shown while your screen is being shared/controlled"
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val stopIntent = Intent(this, ScreenCaptureService::class.java).apply { action = ACTION_STOP }
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Screen sharing is active")
            .setContentText("Your screen is currently visible/controllable remotely.")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop sharing", stopPendingIntent)
            .build()
    }
}
