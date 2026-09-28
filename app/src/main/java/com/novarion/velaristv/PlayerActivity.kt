package com.novarion.velaristv

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.FrameLayout
import android.widget.TextView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
class PlayerActivity : Activity() {
    private val io = Executors.newSingleThreadExecutor()
    private var player: ExoPlayer? = null
    private var itemId = ""
    private var server = ""
    private var token = ""
    private var userId = ""
    private var startTicks = 0L
    private var sessionStarted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        server = intent.getStringExtra("server").orEmpty()
        token = intent.getStringExtra("token").orEmpty()
        userId = intent.getStringExtra("userId").orEmpty()
        itemId = intent.getStringExtra("itemId").orEmpty()
        startTicks = intent.getLongExtra("startTicks", 0L)
        immersive()

        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        val playerView = PlayerView(this).apply {
            useController = true
            controllerShowTimeoutMs = 4000
            controllerHideOnTouch = true
            setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
        }
        root.addView(playerView, FrameLayout.LayoutParams(-1, -1))
        root.addView(TextView(this).apply {
            text = "VELARIS"; textSize = 16f; setTextColor(0x99FFFFFF.toInt())
            setPadding(28, 18, 0, 0)
        })
        setContentView(root)

        val exo = ExoPlayer.Builder(this).build()
        player = exo
        playerView.player = exo
        exo.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying && !sessionStarted) { sessionStarted = true; report("Playing") }
                else if (!isPlaying && sessionStarted) report("Progress")
            }
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) report("Stopped")
            }
        })

        val streamUrl = "$server/Videos/$itemId/stream?static=true&api_key=$token"
        exo.setMediaItem(MediaItem.fromUri(streamUrl))
        exo.prepare()
        if (startTicks > 0) exo.seekTo(startTicks / 10_000L)
        exo.playWhenReady = true
        playerView.requestFocus()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> { if (player?.isPlaying == true) player?.pause() else player?.play(); return true }
                KeyEvent.KEYCODE_MEDIA_PLAY -> { player?.play(); return true }
                KeyEvent.KEYCODE_MEDIA_PAUSE -> { player?.pause(); return true }
                KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { player?.seekForward(); return true }
                KeyEvent.KEYCODE_MEDIA_REWIND -> { player?.seekBack(); return true }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onStop() {
        report("Stopped")
        player?.release(); player = null
        super.onStop()
    }

    override fun onDestroy() { io.shutdownNow(); super.onDestroy() }

    private fun report(kind: String) {
        val p = player ?: return
        val body = JSONObject()
            .put("ItemId", itemId)
            .put("PositionTicks", p.currentPosition * 10_000L)
            .put("IsPaused", !p.isPlaying)
            .put("CanSeek", true)
            .toString()
        io.execute {
            try {
                val endpoint = when(kind) {
                    "Playing" -> "/Sessions/Playing"
                    "Stopped" -> "/Sessions/Playing/Stopped"
                    else -> "/Sessions/Playing/Progress"
                }
                val c = URL(server + endpoint).openConnection() as HttpURLConnection
                c.requestMethod = "POST"; c.doOutput = true
                c.setRequestProperty("Content-Type","application/json")
                c.setRequestProperty("Authorization", """MediaBrowser Client="Velaris TV", Device="Android TV", DeviceId="velaris-tv", Version="0.2.0", Token="$token"""")
                c.outputStream.use { it.write(body.toByteArray()) }
                c.responseCode
                c.disconnect()
            } catch (_: Exception) {}
        }
    }

    private fun immersive() {
        if (android.os.Build.VERSION.SDK_INT >= 30) window.insetsController?.apply {
            hide(WindowInsets.Type.systemBars())
            systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else @Suppress("DEPRECATION") run { window.decorView.systemUiVisibility = 5894 }
    }
}
