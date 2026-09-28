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
import android.widget.Button
import android.app.AlertDialog
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.common.C
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import org.json.JSONObject
import java.net.URLEncoder
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

@UnstableApi
class PlayerActivity : Activity() {
    private val io = Executors.newSingleThreadExecutor()
    private var player: ExoPlayer? = null
    private var itemId = ""
    private var server = ""
    private var token = ""
    private var startTicks = 0L
    private var sessionStarted = false
    private var stoppedReported = false
    private var nextItemId = ""
    private var userId = ""
    private var mediaSourceId = ""
    private var availableTracks: Tracks? = null
    private data class MediaSegment(val type:String,val startMs:Long,val endMs:Long)
    private val mediaSegments=mutableListOf<MediaSegment>()
    private var skipIntroButton: Button? = null
    private var activeSegment: MediaSegment? = null
    private var statsText: TextView? = null
    private var lastPlaybackLabel = "Auto"
    private var lastMediaInfo = ""
    private val introUiHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val introUiTick = object : Runnable {
        override fun run() {
            updateIntroButton()
            introUiHandler.postDelayed(this, 500L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        server = intent.getStringExtra("server").orEmpty()
        token = intent.getStringExtra("token").orEmpty()
        itemId = intent.getStringExtra("itemId").orEmpty()
        userId = intent.getStringExtra("userId").orEmpty()
        startTicks = intent.getLongExtra("startTicks", 0L)
        nextItemId = intent.getStringExtra("nextItemId").orEmpty()
        if (server.isBlank() || token.isBlank() || itemId.isBlank()) {
            android.widget.Toast.makeText(this, "Ungültige Wiedergabeparameter", android.widget.Toast.LENGTH_LONG).show()
            finish()
            return
        }
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
        skipIntroButton = Button(this).apply {
            text = "Intro überspringen"
            contentDescription = "Intro überspringen"
            isAllCaps = false
            textSize = 17f
            setTextColor(Color.WHITE)
            backgroundTintList = android.content.res.ColorStateList.valueOf(Color.rgb(83,50,205))
            visibility = View.GONE
            setOnClickListener {
                val segment=activeSegment
                if(segment != null && segment.endMs > 0) {
                    player?.seekTo(segment.endMs)
                    visibility = View.GONE
                    playerView.requestFocus()
                }
            }
        }
        root.addView(skipIntroButton, FrameLayout.LayoutParams(300,72,android.view.Gravity.END or android.view.Gravity.BOTTOM).apply {
            marginEnd=42; bottomMargin=58
        })
        statsText=TextView(this).apply { textSize=14f; setTextColor(Color.WHITE); setBackgroundColor(0xAA000000.toInt()); setPadding(18,12,18,12); visibility=View.GONE }
        root.addView(statsText,FrameLayout.LayoutParams(-2,-2,android.view.Gravity.START or android.view.Gravity.TOP).apply { marginStart=28; topMargin=54 })
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
                if (state == Player.STATE_ENDED) {
                    reportStoppedOnce()
                    if(nextItemId.isNotBlank()) {
                        itemId=nextItemId; nextItemId=""; stoppedReported=false; sessionStarted=false
                        loadFollowingEpisodeAndPlay(exo,itemId)
                    } else finish()
                }
            }
            override fun onTracksChanged(tracks: Tracks) { availableTracks=tracks }
            override fun onPlayerError(error: PlaybackException) {
                android.widget.Toast.makeText(this@PlayerActivity, "Wiedergabefehler: ${error.errorCodeName}", android.widget.Toast.LENGTH_LONG).show()
            }
        })

        preparePlayback(exo, itemId, startTicks)
        introUiHandler.post(introUiTick)
        playerView.requestFocus()
    }

    private fun loadFollowingEpisodeAndPlay(exo: ExoPlayer, id:String) {
        io.execute {
            var following=""
            try {
                val c=URL("$server/Shows/NextUp?UserId=$userId&StartItemId=$id&Limit=1").openConnection() as HttpURLConnection
                try {
                    c.connectTimeout=6000; c.readTimeout=8000
                    c.setRequestProperty("Authorization", """MediaBrowser Client="Velaris TV", Device="Android TV", DeviceId="velaris-tv", Version="0.6.0", Token="$token"""")
                    if(c.responseCode in 200..299) {
                        val json=JSONObject(c.inputStream.bufferedReader().use { it.readText() })
                        val arr=json.optJSONArray("Items")
                        if(arr!=null && arr.length()>0) following=arr.getJSONObject(0).optString("Id")
                    }
                } finally { c.disconnect() }
            } catch(_:Exception) {}
            nextItemId=following
            runOnUiThread { if(!isFinishing && !isDestroyed) preparePlayback(exo,id,0L) }
        }
    }

    private fun preparePlayback(exo: ExoPlayer, id:String, resumeTicks:Long) {
        synchronized(mediaSegments) { mediaSegments.clear() }; activeSegment=null
        skipIntroButton?.visibility=View.GONE
        loadMediaSegments(id)
        io.execute {
            val url = try { resolvePlaybackUrl(id) } catch (_:Exception) { "$server/Videos/$id/stream?static=true&api_key=$token" }
            runOnUiThread {
                if(isFinishing || isDestroyed || player == null) return@runOnUiThread
                exo.setMediaItem(MediaItem.fromUri(url))
                exo.prepare()
                if(resumeTicks > 0) exo.seekTo(resumeTicks / 10_000L)
                exo.playWhenReady=true
            }
        }
    }

    private fun loadMediaSegments(id:String) {
        io.execute {
            try {
                val types=URLEncoder.encode("Intro,Recap,Outro,Credits,Preview","UTF-8")
                val c=URL("$server/MediaSegments/$id?includeSegmentTypes=$types").openConnection() as HttpURLConnection
                try {
                    c.connectTimeout=6000; c.readTimeout=8000
                    c.setRequestProperty("Accept","application/json")
                    c.setRequestProperty("Authorization", """MediaBrowser Client="Velaris TV", Device="Android TV", DeviceId="velaris-tv", Version="0.6.0", Token="$token"""")
                    if(c.responseCode in 200..299) {
                        val json=JSONObject(c.inputStream.bufferedReader().use { it.readText() })
                        val arr=json.optJSONArray("Items")
                        val loaded=mutableListOf<MediaSegment>()
                        if(arr!=null) for(i in 0 until arr.length()) {
                            val s=arr.getJSONObject(i)
                            val type=s.optString("Type")
                            val start=s.optLong("StartTicks",0L)/10_000L
                            val end=s.optLong("EndTicks",0L)/10_000L
                            if(type.isNotBlank() && end>start) loaded.add(MediaSegment(type,start,end))
                        }
                        synchronized(mediaSegments) { mediaSegments.clear(); mediaSegments.addAll(loaded) }
                    }
                } finally { c.disconnect() }
            } catch(_:Exception) {}
        }
    }

    private fun updateIntroButton() {
        val p=player ?: return
        val current=p.currentPosition
        val segment=synchronized(mediaSegments) { mediaSegments.firstOrNull { current in it.startMs until it.endMs } }
        activeSegment=segment
        val button=skipIntroButton ?: return
        if(segment!=null) {
            button.text=when(segment.type.lowercase()) {
                "recap" -> "Rückblick überspringen"
                "outro","credits" -> if(nextItemId.isNotBlank()) "Nächste Folge" else "Abspann überspringen"
                "preview" -> "Vorschau überspringen"
                else -> "Intro überspringen"
            }
            button.contentDescription=button.text
            if(button.visibility!=View.VISIBLE) { button.visibility=View.VISIBLE; button.requestFocus() }
        } else if(button.visibility==View.VISIBLE) {
            button.visibility=View.GONE
        }
    }

    private fun resolvePlaybackUrl(id:String):String {
        val body=JSONObject().put("UserId",intent.getStringExtra("userId").orEmpty()).put("StartTimeTicks",0).put("AutoOpenLiveStream",true).toString()
        val c=URL("$server/Items/$id/PlaybackInfo").openConnection() as HttpURLConnection
        try {
            c.requestMethod="POST"; c.doOutput=true; c.connectTimeout=8000; c.readTimeout=12000
            c.setRequestProperty("Content-Type","application/json")
            c.setRequestProperty("Authorization", """MediaBrowser Client="Velaris TV", Device="Android TV", DeviceId="velaris-tv", Version="0.6.0", Token="$token"""")
            c.outputStream.use { it.write(body.toByteArray()) }
            if(c.responseCode !in 200..299) throw IllegalStateException("PlaybackInfo HTTP ${c.responseCode}")
            val json=JSONObject(c.inputStream.bufferedReader().use { it.readText() })
            val sources=json.optJSONArray("MediaSources") ?: throw IllegalStateException("Keine Medienquelle")
            if(sources.length()==0) throw IllegalStateException("Keine Medienquelle")
            val source=sources.getJSONObject(0)
            mediaSourceId=source.optString("Id")
            val streams=source.optJSONArray("MediaStreams")
            var video:JSONObject?=null
            if(streams!=null) for(i in 0 until streams.length()) { val s=streams.getJSONObject(i); if(s.optString("Type")=="Video") { video=s; break } }
            lastMediaInfo=listOfNotNull(video?.optString("Codec")?.uppercase()?.takeIf { it.isNotBlank() },video?.optInt("Width")?.takeIf { it>0 }?.let { "${it} px" },source.optLong("Bitrate").takeIf { it>0 }?.let { "${it/1_000_000} Mbps" }).joinToString(" • ")
            val transcoding=source.optString("TranscodingUrl")
            if(transcoding.isNotBlank()) { lastPlaybackLabel="Transcoding"; return if(transcoding.startsWith("http")) transcoding else server+transcoding }
            lastPlaybackLabel="Direct Play"
            val container=source.optString("Container","mp4").split(",").firstOrNull().orEmpty().ifBlank{"mp4"}
            val encoded=URLEncoder.encode(mediaSourceId,"UTF-8")
            return "$server/Videos/$id/stream.$container?Static=true&MediaSourceId=$encoded&api_key=$token"
        } finally { c.disconnect() }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> { if (player?.isPlaying == true) player?.pause() else player?.play(); return true }
                KeyEvent.KEYCODE_MEDIA_PLAY -> { player?.play(); return true }
                KeyEvent.KEYCODE_MEDIA_PAUSE -> { player?.pause(); return true }
                KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { player?.seekForward(); return true }
                KeyEvent.KEYCODE_MEDIA_REWIND -> { player?.seekBack(); return true }
                KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_SETTINGS -> { showTrackMenu(); return true }
                KeyEvent.KEYCODE_INFO -> { toggleStats(); return true }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun toggleStats() {
        val v=statsText ?: return
        if(v.visibility==View.VISIBLE) v.visibility=View.GONE else { v.text="Velaris Wiedergabe\n$lastPlaybackLabel${if(lastMediaInfo.isNotBlank()) "\n$lastMediaInfo" else ""}"; v.visibility=View.VISIBLE }
    }

    private fun showTrackMenu() {
        val exo=player ?: return
        val tracks=availableTracks ?: return
        val choices=mutableListOf<Pair<String,Pair<Tracks.Group,Int>>>()
        for(group in tracks.groups) {
            if(group.type!=C.TRACK_TYPE_AUDIO && group.type!=C.TRACK_TYPE_TEXT) continue
            for(i in 0 until group.length) {
                if(!group.isTrackSupported(i)) continue
                val format=group.getTrackFormat(i)
                val kind=if(group.type==C.TRACK_TYPE_AUDIO) "Audio" else "Untertitel"
                val label=format.label ?: format.language ?: "Spur ${i+1}"
                choices.add("$kind: $label" to (group to i))
            }
        }
        val labels=mutableListOf("Untertitel aus")
        labels.addAll(choices.map{it.first})
        AlertDialog.Builder(this).setTitle("Audio & Untertitel").setItems(labels.toTypedArray()) { _,which ->
            if(which==0) {
                exo.trackSelectionParameters=exo.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT,true).build()
            } else {
                val (group,index)=choices[which-1].second
                val builder=exo.trackSelectionParameters.buildUpon()
                if(group.type==C.TRACK_TYPE_TEXT) builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT,false)
                builder.setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup,index))
                exo.trackSelectionParameters=builder.build()
            }
        }.setNegativeButton("Schließen",null).show()
    }

    override fun onStop() {
        reportStoppedOnce()
        player?.release(); player = null
        super.onStop()
    }

    override fun onDestroy() { introUiHandler.removeCallbacks(introUiTick); io.shutdownNow(); super.onDestroy() }

    private fun reportStoppedOnce() {
        if (stoppedReported) return
        stoppedReported = true
        report("Stopped")
    }

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
                try {
                c.requestMethod = "POST"; c.doOutput = true; c.connectTimeout = 6000; c.readTimeout = 6000
                c.setRequestProperty("Content-Type","application/json")
                c.setRequestProperty("Authorization", """MediaBrowser Client="Velaris TV", Device="Android TV", DeviceId="velaris-tv", Version="0.6.0", Token="$token"""")
                c.outputStream.use { it.write(body.toByteArray()) }
                c.responseCode
                } finally { c.disconnect() }
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
