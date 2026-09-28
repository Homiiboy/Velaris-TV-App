package com.novarion.velaristv

import android.app.Activity
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.*
import androidx.media3.common.util.UnstableApi
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

@UnstableApi
class MainActivity : Activity() {
    private val prefs by lazy { getSharedPreferences("velaris_tv", MODE_PRIVATE) }
    private val io = Executors.newFixedThreadPool(4)
    private var server = ""
    private var token = ""
    private var userId = ""
    private var homeRoot: LinearLayout? = null
    @Volatile private var destroyed = false
    private val backStack = java.util.ArrayDeque<()->Unit>()
    private var suppressHistory = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        immersive()
        server = prefs.getString("server_url", "").orEmpty()
        token = prefs.getString("access_token", "").orEmpty()
        userId = prefs.getString("user_id", "").orEmpty()
        when {
            server.isBlank() -> showServer()
            token.isBlank() || userId.isBlank() -> showLogin()
            else -> showHome()
        }
    }

    override fun onDestroy() { destroyed = true; io.shutdownNow(); super.onDestroy() }

    override fun onBackPressed() {
        if(backStack.isNotEmpty()) {
            val action=backStack.removeLast()
            suppressHistory=true
            action()
            suppressHistory=false
        } else showSettingsDialog()
    }

    private fun rememberBack(action:()->Unit) {
        if(!suppressHistory) backStack.addLast(action)
    }

    private fun showServer() {
        val input = EditText(this).apply {
            hint = "http://192.168.1.50:8096"; setText(server); setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY); textSize = 18f; setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        val root = setupPage("Velaris verbinden", "Adresse deines Jellyfin-Servers", input)
        root.addView(button("Verbinden") {
            val value = normalize(input.text.toString())
            if (value == null) toast("Ungültige Server-Adresse") else {
                server = value; prefs.edit().putString("server_url", server).apply(); showLogin()
            }
        }, params(260, 64, 18))
        setContentView(root)
    }

    private fun showLogin() {
        val user = EditText(this).apply { hint="Benutzername"; setTextColor(Color.WHITE); setHintTextColor(Color.GRAY); setSingleLine() }
        val pass = EditText(this).apply {
            hint="Passwort"; setTextColor(Color.WHITE); setHintTextColor(Color.GRAY); setSingleLine()
            inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val root = setupPage("Willkommen bei Velaris", "Mit deinem Jellyfin-Konto anmelden", user)
        root.addView(pass, params(600, 62, 12))
        root.addView(button("Anmelden") { login(user.text.toString(), pass.text.toString()) }, params(260,64,18))
        root.addView(button("Server ändern") { clearConnection(); showServer() }, params(260,58,10))
        setContentView(root); user.requestFocus()
    }

    private fun login(username: String, password: String) {
        if (username.isBlank()) return toast("Benutzername eingeben")
        io.execute {
            try {
                val body = JSONObject().put("Username", username).put("Pw", password).toString()
                val json = request("/Users/AuthenticateByName", "POST", body, false)
                token = json.getString("AccessToken")
                userId = json.getJSONObject("User").getString("Id")
                prefs.edit().putString("access_token", token).putString("user_id", userId).apply()
                runOnUiThread { showHome() }
            } catch (e: Exception) { runOnUiThread { toast("Anmeldung fehlgeschlagen: ${e.message ?: "Server nicht erreichbar"}") } }
        }
    }

    private fun showHome() {
        if(!suppressHistory) backStack.clear()
        val root = LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL; setBackgroundColor(Color.rgb(8,8,13))
            setPadding(dp(48),dp(24),dp(48),dp(24))
        }
        homeRoot=root
        val top = LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER_VERTICAL }
        top.addView(ImageView(this).apply { setImageResource(R.drawable.velaris_logo); scaleType=ImageView.ScaleType.FIT_CENTER }, LinearLayout.LayoutParams(dp(120),dp(62)))
        val nav=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER_VERTICAL }
        nav.addView(button("Startseite") { showHome() }, LinearLayout.LayoutParams(dp(150),dp(54)))
        nav.addView(button("Filme") { showLibrary("Filme","Movie") }, LinearLayout.LayoutParams(dp(130),dp(54)))
        nav.addView(button("Serien") { showLibrary("Serien","Series") }, LinearLayout.LayoutParams(dp(130),dp(54)))
        nav.addView(button("Meine Liste") { showFavorites() }, LinearLayout.LayoutParams(dp(160),dp(54)))
        nav.addView(button("Suche") { showSearch() }, LinearLayout.LayoutParams(dp(130),dp(54)))
        top.addView(nav, LinearLayout.LayoutParams(0,dp(62),1f))
        top.addView(button("👤") { showProfiles() }, LinearLayout.LayoutParams(dp(72),dp(54)))
        top.addView(button("⚙") { showSettingsDialog() }, LinearLayout.LayoutParams(dp(72),dp(54)))
        root.addView(top)
        root.addView(TextView(this).apply { text="Dein Velaris"; setTextColor(Color.WHITE); textSize=32f; setPadding(0,dp(16),0,dp(8)) })
        root.addView(ProgressBar(this))
        setContentView(ScrollView(this).apply { addView(root) })
        loadHome()
    }

    private fun loadHome() {
        io.execute {
            try {
                val resume = items("/Users/$userId/Items/Resume?Limit=12&Fields=PrimaryImageAspectRatio,Overview&MediaTypes=Video")
                val latest = items("/Users/$userId/Items/Latest?Limit=18&Fields=PrimaryImageAspectRatio,Overview&IncludeItemTypes=Movie,Series")
                val movies = items("/Users/$userId/Items?Recursive=true&Limit=18&SortBy=DateCreated&SortOrder=Descending&IncludeItemTypes=Movie&Fields=PrimaryImageAspectRatio")
                val series = items("/Users/$userId/Items?Recursive=true&Limit=18&SortBy=DateCreated&SortOrder=Descending&IncludeItemTypes=Series&Fields=PrimaryImageAspectRatio")
                val favorites = items("/Users/$userId/Items?Recursive=true&Limit=18&Filters=IsFavorite&IncludeItemTypes=Movie,Series&Fields=PrimaryImageAspectRatio")
                val recommended = items("/Users/$userId/Items?Recursive=true&Limit=18&SortBy=CommunityRating,DatePlayed&SortOrder=Descending&IncludeItemTypes=Movie,Series&Filters=IsNotFolder&Fields=PrimaryImageAspectRatio,CommunityRating")
                runOnUiThread {
                    homeRoot?.let { if (it.childCount > 2) it.removeViews(2, it.childCount - 2) }
                    addHero((resume + latest).firstOrNull())
                    addRow("Weiterschauen", resume)
                    addRow("Neu bei Velaris", latest)
                    addRow("Meine Liste", favorites)
                    addRow("Für dich", recommended)
                    addRow("Filme", movies)
                    addRow("Serien", series)
                }
            } catch(e: Exception) { runOnUiThread {
                val root=homeRoot ?: return@runOnUiThread
                root.let { if(it.childCount>2) it.removeViews(2,it.childCount-2) }
                root.addView(TextView(this).apply { text="Velaris konnte den Server gerade nicht erreichen."; textSize=20f; setTextColor(Color.LTGRAY); gravity=Gravity.CENTER; setPadding(0,dp(32),0,dp(18)) })
                root.addView(button("Erneut versuchen") { loadHome() },params(260,60,8))
            } }
        }
    }

    private fun items(path: String): List<JSONObject> {
        val json=request(path)
        val arr=if(json.has("Items")) json.getJSONArray("Items") else json.optJSONArray("array")
        val out=mutableListOf<JSONObject>()
        if(arr!=null) for(i in 0 until arr.length()) out.add(arr.getJSONObject(i))
        return out
    }

    private fun addHero(item: JSONObject?) {
        if(item==null) return
        val id=item.optString("Id"); if(id.isBlank()) return
        val title=item.optString("Name","Velaris")
        val hero=FrameLayout(this).apply {
            minimumHeight=dp(330); isFocusable=true
            background=android.graphics.drawable.GradientDrawable().apply { setColor(Color.rgb(18,18,28)); cornerRadius=dp(12).toFloat() }
            setOnClickListener { showDetails(id) }
        }
        val image=ImageView(this).apply { scaleType=ImageView.ScaleType.CENTER_CROP }
        hero.addView(image, FrameLayout.LayoutParams(-1,dp(330)))
        loadImage(image, id, "Backdrop", 1280)
        val shade=View(this).apply { background=android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(Color.rgb(8,8,13),0x2208080D)) }
        hero.addView(shade, FrameLayout.LayoutParams(-1,-1))
        val text=TextView(this).apply { this.text=title; textSize=36f; setTextColor(Color.WHITE); gravity=Gravity.BOTTOM; setPadding(dp(32),0,0,dp(34)) }
        hero.addView(text, FrameLayout.LayoutParams(-1,-1))
        homeRoot?.addView(hero, LinearLayout.LayoutParams(-1,dp(330)).apply { bottomMargin=dp(26) })
    }

    private fun addRow(title: String, data: List<JSONObject>) {
        if(data.isEmpty()) return
        homeRoot?.addView(TextView(this).apply { text=title; setTextColor(Color.WHITE); textSize=23f; setPadding(0,dp(12),0,dp(10)) })
        val row=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; setPadding(dp(4),dp(8),dp(4),dp(18)) }
        data.forEach { item ->
            val id=item.optString("Id")
            val type=item.optString("Type")
            if(id.isBlank()) return@forEach
            val card=LinearLayout(this).apply {
                orientation=LinearLayout.VERTICAL; isFocusable=true; isClickable=true
                setPadding(dp(5),dp(5),dp(5),dp(5)); setOnClickListener { if(type=="Series") showSeries(id) else showDetails(id) }
                setOnFocusChangeListener { v, focused -> v.animate().scaleX(if(focused)1.08f else 1f).scaleY(if(focused)1.08f else 1f).setDuration(120).start() }
            }
            val img=ImageView(this).apply { scaleType=ImageView.ScaleType.CENTER_CROP }
            card.addView(img, LinearLayout.LayoutParams(dp(180),dp(260)))
            loadImage(img,id,"Primary",360)
            card.addView(TextView(this).apply { text=item.optString("Name"); setTextColor(Color.WHITE); textSize=15f; maxLines=1 }, LinearLayout.LayoutParams(dp(180),dp(38)))
            row.addView(card, LinearLayout.LayoutParams(dp(194),dp(315)).apply { marginEnd=dp(12) })
        }
        homeRoot?.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled=false; addView(row) }, LinearLayout.LayoutParams(-1,dp(325)))
    }


    private fun showLibrary(title:String, type:String) {
        rememberBack { showHome() }
        val root=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(48),dp(28),dp(48),dp(28)); setBackgroundColor(Color.rgb(8,8,13)) }
        root.addView(TextView(this).apply { text=title; textSize=32f; setTextColor(Color.WHITE) })
        root.addView(ProgressBar(this))
        setContentView(ScrollView(this).apply { addView(root) })
        io.execute {
            try {
                val data=items("/Users/$userId/Items?Recursive=true&Limit=100&SortBy=SortName&SortOrder=Ascending&IncludeItemTypes=$type&Fields=PrimaryImageAspectRatio")
                runOnUiThread {
                    root.removeViewAt(1)
                    homeRoot=root
                    addRow(title,data)
                }
            } catch(e:Exception){ runOnUiThread{toast("$title konnten nicht geladen werden")} }
        }
    }

    private fun showFavorites() {
        rememberBack { showHome() }
        val root=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(48),dp(28),dp(48),dp(28)); setBackgroundColor(Color.rgb(8,8,13)) }
        root.addView(TextView(this).apply { text="Meine Liste"; textSize=32f; setTextColor(Color.WHITE) })
        root.addView(ProgressBar(this))
        setContentView(ScrollView(this).apply { addView(root) })
        io.execute {
            try {
                val data=items("/Users/$userId/Items?Recursive=true&Limit=100&Filters=IsFavorite&IncludeItemTypes=Movie,Series&Fields=PrimaryImageAspectRatio")
                runOnUiThread {
                    root.removeViewAt(1); homeRoot=root
                    if(data.isEmpty()) root.addView(TextView(this).apply { text="Deine Liste ist noch leer."; textSize=18f; setTextColor(Color.LTGRAY); setPadding(0,dp(24),0,dp(24)) })
                    else addRow("Favoriten",data)
                    root.addView(button("Zurück") { showHome() },params(220,56,16))
                }
            } catch(e:Exception){ runOnUiThread{toast("Meine Liste konnte nicht geladen werden")} }
        }
    }

    private fun showSearch() {
        rememberBack { showHome() }
        val input=EditText(this).apply { hint="Filme und Serien suchen"; setTextColor(Color.WHITE); setHintTextColor(Color.GRAY); setSingleLine() }
        val root=setupPage("Suche","Durchsuche deine Jellyfin-Mediathek",input)
        root.addView(button("Suchen") {
            val q=input.text.toString().trim()
            if(q.isNotBlank()) search(q)
        },params(240,60,16))
        root.addView(button("Zurück") { showHome() },params(220,56,10))
        setContentView(root); input.requestFocus()
    }

    private fun search(query:String) {
        rememberBack { showSearch() }
        val encoded=java.net.URLEncoder.encode(query,"UTF-8")
        io.execute {
            try {
                val data=items("/Users/$userId/Items?Recursive=true&Limit=50&SearchTerm=$encoded&IncludeItemTypes=Movie,Series&Fields=PrimaryImageAspectRatio")
                runOnUiThread {
                    val root=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(48),dp(28),dp(48),dp(28)); setBackgroundColor(Color.rgb(8,8,13)) }
                    root.addView(TextView(this).apply { text="Suchergebnisse für „$query“"; textSize=30f; setTextColor(Color.WHITE) })
                    homeRoot=root
                    addRow("Ergebnisse",data)
                    root.addView(button("Neue Suche") { showSearch() },params(220,56,16))
                    setContentView(ScrollView(this).apply { addView(root) })
                }
            } catch(e:Exception){ runOnUiThread{toast("Suche fehlgeschlagen")} }
        }
    }

    private fun showSeries(seriesId:String) {
        rememberBack { showHome() }
        io.execute {
            try {
                val series=request("/Users/$userId/Items/$seriesId")
                val seasons=items("/Shows/$seriesId/Seasons?UserId=$userId&Fields=PrimaryImageAspectRatio")
                runOnUiThread {
                    val root=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(48),dp(28),dp(48),dp(28)); setBackgroundColor(Color.rgb(8,8,13)) }
                    root.addView(TextView(this).apply { text=series.optString("Name"); textSize=34f; setTextColor(Color.WHITE) })
                    root.addView(TextView(this).apply { text=series.optString("Overview"); textSize=16f; setTextColor(Color.LTGRAY); maxLines=4 },params(-1,-2,12))
                    val favorite=series.optJSONObject("UserData")?.optBoolean("IsFavorite",false) ?: false
                    root.addView(button(if(favorite) "✓ Meine Liste" else "+ Meine Liste") { setFavorite(seriesId,!favorite) { showSeries(seriesId) } },params(260,56,12))
                    seasons.forEach { season ->
                        root.addView(button(season.optString("Name","Staffel")) { showSeason(seriesId,season.optString("Id")) },params(360,58,12))
                    }
                    root.addView(button("Zurück") { showHome() },params(220,56,18))
                    setContentView(ScrollView(this).apply { addView(root) })
                }
            } catch(e:Exception){ runOnUiThread{toast("Serie konnte nicht geladen werden")} }
        }
    }

    private fun showSeason(seriesId:String, seasonId:String) {
        rememberBack { showSeries(seriesId) }
        io.execute {
            try {
                val episodes=items("/Shows/$seriesId/Episodes?UserId=$userId&SeasonId=$seasonId&Fields=Overview,PrimaryImageAspectRatio,RunTimeTicks")
                runOnUiThread {
                    val root=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(48),dp(28),dp(48),dp(28)); setBackgroundColor(Color.rgb(8,8,13)) }
                    root.addView(TextView(this).apply { text="Episoden"; textSize=32f; setTextColor(Color.WHITE) })
                    episodes.forEach { ep ->
                        val eid=ep.optString("Id")
                        val ticks=ep.optJSONObject("UserData")?.optLong("PlaybackPositionTicks",0L) ?: 0L
                        val number=ep.optInt("IndexNumber",0)
                        val name=ep.optString("Name")
                        val nextId=episodes.getOrNull(episodes.indexOf(ep)+1)?.optString("Id").orEmpty()
                        val card=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER_VERTICAL; isFocusable=true; isClickable=true; setPadding(dp(8),dp(8),dp(8),dp(8)); setOnClickListener { startEpisode(eid,ticks,nextId) }; setOnFocusChangeListener { v,f -> v.animate().scaleX(if(f)1.025f else 1f).scaleY(if(f)1.025f else 1f).setDuration(120).start() } }
                        val thumb=ImageView(this).apply { scaleType=ImageView.ScaleType.CENTER_CROP }
                        card.addView(thumb,LinearLayout.LayoutParams(dp(250),dp(140))); loadImage(thumb,eid,"Primary",500)
                        val info=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(18),0,0,0) }
                        info.addView(TextView(this).apply { text=(if(number>0) "$number. " else "")+name; textSize=20f; setTextColor(Color.WHITE) })
                        info.addView(TextView(this).apply { text=ep.optString("Overview"); textSize=14f; setTextColor(Color.LTGRAY); maxLines=3 },params(-1,-2,6))
                        card.addView(info,LinearLayout.LayoutParams(0,dp(140),1f))
                        root.addView(card,params(-1,156,8))
                    }
                    root.addView(button("Zurück zur Serie") { showSeries(seriesId) },params(260,56,18))
                    setContentView(ScrollView(this).apply { addView(root) })
                }
            } catch(e:Exception){ runOnUiThread{toast("Episoden konnten nicht geladen werden")} }
        }
    }

    private fun startEpisode(id:String,ticks:Long,nextId:String="")=playNative(id,ticks,nextId)

    private fun showDetails(id:String) {
        rememberBack { showHome() }
        io.execute {
            try {
                val x=request("/Users/$userId/Items/$id")
                runOnUiThread {
                    val root=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; gravity=Gravity.CENTER; setPadding(dp(80),dp(50),dp(80),dp(50)); setBackgroundColor(Color.rgb(8,8,13)) }
                    val img=ImageView(this).apply { scaleType=ImageView.ScaleType.CENTER_CROP }; root.addView(img,params(260,370,0)); loadImage(img,id,"Primary",520)
                    root.addView(TextView(this).apply { text=x.optString("Name"); textSize=34f; setTextColor(Color.WHITE); gravity=Gravity.CENTER })
                    val year=x.optInt("ProductionYear",0)
                    val rating=x.optDouble("CommunityRating",0.0)
                    val runtime=x.optLong("RunTimeTicks",0L)/600_000_000L
                    val genres=x.optJSONArray("Genres")
                    val genreText=if(genres!=null) (0 until minOf(genres.length(),3)).joinToString(" • ") { genres.optString(it) } else ""
                    val meta=listOfNotNull(if(year>0) year.toString() else null, if(runtime>0) "${runtime} Min." else null, if(rating>0) "★ %.1f".format(rating) else null).joinToString("  •  ")
                    if(meta.isNotBlank()) root.addView(TextView(this).apply { text=meta; textSize=15f; setTextColor(Color.GRAY); gravity=Gravity.CENTER },params(-1,-2,8))
                    root.addView(TextView(this).apply { text=x.optString("Overview"); textSize=16f; setTextColor(Color.LTGRAY); gravity=Gravity.CENTER; maxLines=5 }, params(-1,-2,18))
                    val userData=x.optJSONObject("UserData")
                    val ticks=userData?.optLong("PlaybackPositionTicks",0L) ?: 0L
                    val favorite=userData?.optBoolean("IsFavorite",false) ?: false
                    val played=userData?.optBoolean("Played",false) ?: false
                    root.addView(button(if(ticks>0) "▶ Fortsetzen" else "▶ Abspielen") { playNative(id,ticks) },params(260,64,22))
                    root.addView(button(if(favorite) "✓ Meine Liste" else "+ Meine Liste") { setFavorite(id,!favorite) { showDetails(id) } },params(260,58,10))
                    root.addView(button(if(played) "↺ Als ungesehen markieren" else "✓ Als gesehen markieren") { setPlayed(id,!played) { showDetails(id) } },params(300,58,10))
                    root.addView(button("Zurück") { showHome() },params(220,58,10))
                    setContentView(root)
                }
            } catch(e:Exception){ runOnUiThread{toast("Details konnten nicht geladen werden")} }
        }
    }

    private fun showProfiles() {
        rememberBack { showHome() }
        io.execute {
            try {
                val users=request("/Users","GET",null,true).optJSONArray("array")
                runOnUiThread {
                    val root=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; gravity=Gravity.CENTER; setPadding(dp(72),dp(36),dp(72),dp(36)); setBackgroundColor(Color.rgb(8,8,13)) }
                    root.addView(TextView(this).apply { text="Profile"; textSize=32f; setTextColor(Color.WHITE); gravity=Gravity.CENTER },params(-1,-2,0))
                    if(users==null || users.length()==0) root.addView(TextView(this).apply { text="Keine weiteren Profile verfügbar."; setTextColor(Color.LTGRAY); textSize=17f },params(-1,-2,18))
                    else for(i in 0 until users.length()) {
                        val u=users.getJSONObject(i); val uid=u.optString("Id"); val name=u.optString("Name","Profil")
                        root.addView(button(if(uid==userId) "✓ $name" else name) {
                            if(uid==userId) showHome() else {
                                token=""; userId=""; prefs.edit().remove("access_token").remove("user_id").apply()
                                toast("Bitte als $name anmelden"); showLogin()
                            }
                        },params(320,60,12))
                    }
                    root.addView(button("Zurück") { showHome() },params(220,56,18))
                    setContentView(root)
                }
            } catch(e:Exception) { runOnUiThread { toast("Profile konnten nicht geladen werden") } }
        }
    }

    private fun setPlayed(id:String, played:Boolean, done:()->Unit) {
        if(id.isBlank()) return
        io.execute {
            try {
                request("/Users/$userId/PlayedItems/$id", if(played) "POST" else "DELETE")
                runOnUiThread { toast(if(played) "Als gesehen markiert" else "Als ungesehen markiert"); done() }
            } catch(e:Exception) { runOnUiThread { toast("Wiedergabestatus konnte nicht geändert werden") } }
        }
    }

    private fun setFavorite(id:String, favorite:Boolean, done:()->Unit) {
        if(id.isBlank()) return
        io.execute {
            try {
                request("/Users/$userId/FavoriteItems/$id", if(favorite) "POST" else "DELETE")
                runOnUiThread { toast(if(favorite) "Zu Meine Liste hinzugefügt" else "Aus Meine Liste entfernt"); done() }
            } catch(e:Exception) { runOnUiThread { toast("Meine Liste konnte nicht geändert werden") } }
        }
    }

    private fun playNative(id:String, startTicks:Long=0L, nextId:String="") {
        if(id.isBlank() || server.isBlank() || token.isBlank() || destroyed) return toast("Wiedergabe kann nicht gestartet werden")
        startActivity(android.content.Intent(this, PlayerActivity::class.java).apply {
            putExtra("server",server)
            putExtra("token",token)
            putExtra("userId",userId)
            putExtra("itemId",id)
            putExtra("startTicks",startTicks)
            putExtra("nextItemId",nextId)
            putExtra("autoNext",true)
        })
    }

    private fun loadImage(view:ImageView,id:String,type:String,width:Int) {
        if(id.isBlank() || server.isBlank() || token.isBlank()) return
        io.execute {
            try {
                val conn=URL("$server/Items/$id/Images/$type?maxWidth=$width&quality=90").openConnection() as HttpURLConnection
                conn.setRequestProperty("X-Emby-Token",token); conn.connectTimeout=6000; conn.readTimeout=8000
                try {
                    if(conn.responseCode in 200..299) {
                        val bmp=conn.inputStream.use { android.graphics.BitmapFactory.decodeStream(it) }
                        if(bmp != null && !destroyed) runOnUiThread { if(!isFinishing && !isDestroyed) view.setImageBitmap(bmp) }
                    }
                } finally { conn.disconnect() }
            } catch(_:Exception){}
        }
    }

    private fun request(path:String, method:String="GET", body:String?=null, auth:Boolean=true):JSONObject {
        if(server.isBlank()) throw IllegalStateException("Kein Server konfiguriert")
        val conn=URL(server+path).openConnection() as HttpURLConnection
        try {
            conn.requestMethod=method; conn.connectTimeout=8000; conn.readTimeout=12000
            conn.setRequestProperty("Accept","application/json")
            conn.setRequestProperty("Authorization", """MediaBrowser Client="Velaris TV", Device="Android TV", DeviceId="velaris-tv", Version="0.6.0"${if(auth && token.isNotBlank()) ", Token=\"$token\"" else ""}""")
            if(body!=null){ conn.doOutput=true; conn.setRequestProperty("Content-Type","application/json"); conn.outputStream.use{it.write(body.toByteArray())} }
            val code=conn.responseCode
            val text=(if(code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use{it.readText()}.orEmpty()
            if(code == 401 || code == 403) throw SecurityException("Sitzung abgelaufen")
            if(code !in 200..299) throw IllegalStateException("Serverfehler HTTP $code")
            return if(text.trim().startsWith("[")) JSONObject().put("array",org.json.JSONArray(text)) else if(text.isBlank()) JSONObject() else JSONObject(text)
        } finally { conn.disconnect() }
    }

    private fun showSettingsDialog() {
        android.app.AlertDialog.Builder(this).setTitle("Velaris TV").setItems(arrayOf("Startseite","Abmelden","Server ändern")) { _,w ->
            when(w){0->showHome();1->{token="";userId="";prefs.edit().remove("access_token").remove("user_id").apply();showLogin()};2->{clearConnection();showServer()}}
        }.setNegativeButton("Abbrechen",null).show()
    }

    private fun clearConnection(){ server="";token="";userId="";prefs.edit().clear().apply() }
    private fun setupPage(title:String,sub:String,first:View)=LinearLayout(this).apply {
        orientation=LinearLayout.VERTICAL; gravity=Gravity.CENTER; setPadding(dp(72),dp(36),dp(72),dp(36)); setBackgroundColor(Color.BLACK)
        addView(ImageView(this@MainActivity).apply{setImageResource(R.drawable.velaris_logo);scaleType=ImageView.ScaleType.FIT_CENTER},params(180,180,0))
        addView(TextView(this@MainActivity).apply{text=title;textSize=30f;setTextColor(Color.WHITE);gravity=Gravity.CENTER},params(-2,-2,12))
        addView(TextView(this@MainActivity).apply{text=sub;textSize=16f;setTextColor(Color.LTGRAY);gravity=Gravity.CENTER},params(-2,-2,8))
        addView(first,params(600,62,22))
    }
    private fun button(label:String, click:()->Unit)=Button(this).apply {
        text=label; contentDescription=label; isAllCaps=false; textSize=17f; setTextColor(Color.WHITE); isFocusable=true; minHeight=dp(48)
        backgroundTintList=android.content.res.ColorStateList.valueOf(Color.rgb(83,50,205)); setOnClickListener{click()}
    }
    private fun params(w:Int,h:Int,top:Int)=LinearLayout.LayoutParams(if(w<0)w else dp(w),if(h<0)h else dp(h)).apply{topMargin=dp(top)}
    private fun normalize(raw:String):String? { var v=raw.trim();if(v.isBlank())return null;if(!v.contains("://"))v="http://$v";val u=runCatching{Uri.parse(v)}.getOrNull()?:return null;return if(u.host.isNullOrBlank() || (u.scheme!="http" && u.scheme!="https"))null else v.trimEnd('/') }
    private fun toast(s:String)=Toast.makeText(this,s,Toast.LENGTH_LONG).show()
    private fun dp(v:Int)=(v*resources.displayMetrics.density).toInt()
    private fun immersive(){ if(Build.VERSION.SDK_INT>=30) window.insetsController?.apply{hide(WindowInsets.Type.systemBars());systemBarsBehavior=WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE} else @Suppress("DEPRECATION") run{window.decorView.systemUiVisibility=5894} }
}
