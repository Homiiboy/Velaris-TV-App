package com.novarion.velaristv

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.KeyEvent
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
    private val io = Executors.newFixedThreadPool(6)
    private val imageCacheMaxKb = ((Runtime.getRuntime().maxMemory() / 1024L) / 8L).coerceIn(12L * 1024L, 48L * 1024L).toInt()
    private val imageCache = object: android.util.LruCache<String,android.graphics.Bitmap>(imageCacheMaxKb) {
        override fun sizeOf(key:String, value:android.graphics.Bitmap):Int = (value.byteCount / 1024).coerceAtLeast(1)
    }
    private val imageLoads = java.util.Collections.synchronizedSet(mutableSetOf<String>())
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
            else -> showProfiles(true)
        }
    }

    override fun onDestroy() { destroyed = true; io.shutdownNow(); super.onDestroy() }

    override fun onTrimMemory(level:Int) {
        super.onTrimMemory(level)
        if(level >= TRIM_MEMORY_BACKGROUND) imageCache.evictAll()
        else if(level >= TRIM_MEMORY_RUNNING_LOW) imageCache.trimToSize(imageCacheMaxKb / 2)
    }

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

        val content = LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(8,8,13))
            setPadding(0,0,0,dp(30))
            clipToPadding=false
        }
        homeRoot=content

        val scroll=ScrollView(this).apply {
            isVerticalScrollBarEnabled=false; clipToPadding=false; addView(content)
        }
        val screen=FrameLayout(this).apply { setBackgroundColor(Color.rgb(8,8,13)) }
        screen.addView(scroll,FrameLayout.LayoutParams(-1,-1))

        val top=LinearLayout(this).apply {
            orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER_VERTICAL
            setPadding(dp(52),dp(16),dp(44),dp(8))
            background=GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0xE608080D.toInt(),0x9908080D.toInt(),0x0008080D)
            )
        }
        val nav=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER_VERTICAL }
        listOf(
            "Startseite" to navButton("Startseite"){showHome()},
            "Filme" to navButton("Filme"){showLibrary("Filme","Movie")},
            "Serien" to navButton("Serien"){showLibrary("Serien","Series")},
            "Anime" to navButton("Anime"){showMediaFolderLibrary("Anime","Series")},
            "Animefilme" to navButton("Animefilme"){showMediaFolderLibrary("Animefilme","Movie")},
            "Sammlungen" to navButton("Sammlungen"){showLibrary("Sammlungen","BoxSet")}
        ).forEach { (label,button) ->
            val width=when(label) { "Startseite"->dp(112); "Animefilme"->dp(122); "Sammlungen"->dp(132); else->dp(88) }
            nav.addView(button,LinearLayout.LayoutParams(width,dp(46)).apply{marginEnd=dp(3)})
        }
        top.addView(nav,LinearLayout.LayoutParams(0,dp(50),1f))
        top.addView(navButton("⌕"){showSearch()},LinearLayout.LayoutParams(dp(60),dp(46)).apply{marginEnd=dp(6)})
        top.addView(navButton("Profil"){showProfiles()},LinearLayout.LayoutParams(dp(82),dp(46)).apply{marginEnd=dp(4)})
        top.addView(navButton("⚙"){showSettingsDialog()},LinearLayout.LayoutParams(dp(60),dp(46)))
        screen.addView(top,FrameLayout.LayoutParams(-1,dp(82),Gravity.TOP))
        setContentView(screen)
        loadHome()
    }

    private fun loadHome() {
        io.execute {
            try {
                fun async(path:String)=java.util.concurrent.CompletableFuture.supplyAsync({ items(path) },io)
                val resumeF=async("/Users/$userId/Items/Resume?Limit=12&Fields=PrimaryImageAspectRatio,Overview,RunTimeTicks&MediaTypes=Video")
                val latestF=async("/Users/$userId/Items/Latest?Limit=18&Fields=PrimaryImageAspectRatio,Overview&IncludeItemTypes=Movie,Series")
                val favoritesF=async("/Users/$userId/Items?Recursive=true&Limit=18&Filters=IsFavorite&IncludeItemTypes=Movie,Series&Fields=PrimaryImageAspectRatio")
                val recommendedF=async("/Users/$userId/Items?Recursive=true&Limit=18&SortBy=CommunityRating,DatePlayed&SortOrder=Descending&IncludeItemTypes=Movie,Series&Filters=IsNotFolder&Fields=PrimaryImageAspectRatio,CommunityRating")
                val topTenF=async("/Users/$userId/Items?Recursive=true&Limit=10&SortBy=CommunityRating&SortOrder=Descending&IncludeItemTypes=Movie,Series&Filters=IsNotFolder&Fields=PrimaryImageAspectRatio,CommunityRating")
                val actionF=async("/Users/$userId/Items?Recursive=true&Limit=18&Genres=Action&IncludeItemTypes=Movie,Series&Fields=PrimaryImageAspectRatio")
                val comedyF=async("/Users/$userId/Items?Recursive=true&Limit=18&Genres=Comedy&IncludeItemTypes=Movie,Series&Fields=PrimaryImageAspectRatio")
                val sciFiF=async("/Users/$userId/Items?Recursive=true&Limit=18&Genres=Science%20Fiction&IncludeItemTypes=Movie,Series&Fields=PrimaryImageAspectRatio")
                val replayF=async("/Users/$userId/Items?Recursive=true&Limit=18&SortBy=DatePlayed&SortOrder=Descending&IncludeItemTypes=Movie,Series&Filters=IsPlayed&Fields=PrimaryImageAspectRatio")
                val watchedF=async("/Users/$userId/Items?Recursive=true&Limit=1&SortBy=DatePlayed&SortOrder=Descending&IncludeItemTypes=Movie,Series&Filters=IsPlayed&Fields=PrimaryImageAspectRatio")
                val resume=resumeF.get(); val latest=latestF.get(); val favorites=favoritesF.get()
                val recommended=recommendedF.get(); val topTen=topTenF.get(); val action=actionF.get()
                val comedy=comedyF.get(); val sciFi=sciFiF.get(); val replay=replayF.get(); val watched=watchedF.get()
                val becauseTitle=watched.firstOrNull()?.optString("Name").orEmpty()
                val because=watched.firstOrNull()?.optString("Id")?.takeIf{it.isNotBlank()}?.let{watchedId->
                    runCatching{items("/Items/$watchedId/Similar?UserId=$userId&Limit=18&Fields=PrimaryImageAspectRatio,CommunityRating")}.getOrDefault(emptyList())
                }?:emptyList()
                runOnUiThread {
                    homeRoot?.let { if(it.childCount>2) it.removeViews(2,it.childCount-2) }
                    addHeroCarousel((latest+recommended).distinctBy{it.optString("Id")}.take(5))
                    addContinueRow(resume)
                    if(becauseTitle.isNotBlank()) addRow("Weil du „$becauseTitle“ gesehen hast",because)
                    addRow("Meine Liste",favorites); addRow("Für dich",recommended)
                    addRankedRow("Top 10 in deiner Mediathek",topTen)
                    addRow("Action",action); addRow("Komödien",comedy); addRow("Science-Fiction",sciFi)
                    addRow("Noch einmal ansehen",replay); addRow("Kürzlich hinzugefügt",latest)
                }
            } catch(e:Exception) { runOnUiThread {
                val root=homeRoot ?: return@runOnUiThread
                root.let { if(it.childCount>2) it.removeViews(2,it.childCount-2) }
                root.addView(TextView(this).apply{text="Velaris konnte den Server gerade nicht erreichen.";textSize=20f;setTextColor(Color.LTGRAY);gravity=Gravity.CENTER;setPadding(0,dp(32),0,dp(18))})
                root.addView(button("Erneut versuchen"){loadHome()},params(260,60,8))
            }}
        }
    }

    private fun items(path: String): List<JSONObject> {
        val json=request(path)
        val arr=if(json.has("Items")) json.getJSONArray("Items") else json.optJSONArray("array")
        val out=mutableListOf<JSONObject>()
        if(arr!=null) for(i in 0 until arr.length()) out.add(arr.getJSONObject(i))
        return out
    }

    private fun addHeroCarousel(items:List<JSONObject>) {
        if(items.isEmpty()) return
        val holder=FrameLayout(this)
        homeRoot?.addView(holder,LinearLayout.LayoutParams(-1,dp(500)).apply{bottomMargin=dp(-26)})
        fun render(index:Int) {
            if(destroyed || items.isEmpty()) return
            holder.animate().alpha(0f).setDuration(150).withEndAction {
                holder.removeAllViews()
                val previous=homeRoot
                val temp=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}
                homeRoot=temp; addHero(items[index % items.size]); homeRoot=previous
                if(temp.childCount>0) {
                    val hero=temp.getChildAt(0); temp.removeView(hero)
                    holder.addView(hero,FrameLayout.LayoutParams(-1,-1))
                }
                holder.animate().alpha(1f).setDuration(220).start()
            }.start()
        }
        render(0)
        if(items.size>1) holder.postDelayed(object:Runnable {
            var index=1
            override fun run() {
                if(holder.isAttachedToWindow && !holder.hasFocus()) { render(index); index=(index+1)%items.size }
                if(holder.isAttachedToWindow) holder.postDelayed(this,9000)
            }
        },9000)
    }

    private fun addHero(item: JSONObject?) {
        if(item==null) return
        val id=item.optString("Id"); if(id.isBlank()) return
        val title=item.optString("Name","Velaris")
        val overview=item.optString("Overview")
        val year=item.optInt("ProductionYear",0)
        val rating=item.optDouble("CommunityRating",0.0)
        val ticks=item.optJSONObject("UserData")?.optLong("PlaybackPositionTicks",0L) ?: 0L

        val hero=FrameLayout(this).apply {
            minimumHeight=dp(480)
            setBackgroundColor(Color.rgb(12,12,18))
        }
        val image=ImageView(this).apply { scaleType=ImageView.ScaleType.CENTER_CROP }
        hero.addView(image, FrameLayout.LayoutParams(-1,dp(480)))
        loadImage(image,id,"Backdrop",1600)

        val shade=View(this).apply {
            background=GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(0xF708080D.toInt(),0xB808080D.toInt(),0x3308080D,0x0808080D)
            )
        }
        hero.addView(shade,FrameLayout.LayoutParams(-1,-1))
        hero.addView(View(this).apply {
            background=GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,intArrayOf(0x0008080D,0x1108080D,0xF508080D.toInt()))
        },FrameLayout.LayoutParams(-1,dp(180),Gravity.BOTTOM))

        val info=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL
            gravity=Gravity.BOTTOM
            setPadding(dp(58),0,dp(58),dp(50))
        }
        val logo=ImageView(this).apply{scaleType=ImageView.ScaleType.FIT_START;visibility=View.INVISIBLE}
        info.addView(logo,LinearLayout.LayoutParams(dp(430),dp(120)))
        loadImageWithFallback(logo,id,"Logo",700) {
            logo.visibility=View.GONE
            info.addView(TextView(this).apply { text=title; textSize=42f; setTextColor(Color.WHITE); typeface=Typeface.DEFAULT_BOLD },0)
        }
        val meta=listOfNotNull(
            if(year>0) year.toString() else null,
            if(rating>0) "★ %.1f".format(rating) else null
        ).joinToString("   •   ")
        if(meta.isNotBlank()) info.addView(TextView(this).apply {
            text=meta; textSize=17f; setTextColor(Color.LTGRAY); setPadding(0,dp(8),0,0)
        })
        if(overview.isNotBlank()) info.addView(TextView(this).apply {
            text=overview; textSize=17f; setTextColor(Color.WHITE); maxLines=3
            setPadding(0,dp(12),0,0)
        },LinearLayout.LayoutParams(dp(650),-2))

        val actions=LinearLayout(this).apply {
            orientation=LinearLayout.HORIZONTAL
            setPadding(0,dp(20),0,0)
        }
        actions.addView(actionButton(if(ticks>0) "▶  Fortsetzen" else "▶  Abspielen",true) {
            playNative(id,ticks)
        },LinearLayout.LayoutParams(dp(220),dp(58)).apply { marginEnd=dp(12) })
        val isFav=item.optJSONObject("UserData")?.optBoolean("IsFavorite",false)?:false
        actions.addView(actionButton(if(isFav)"✓  Meine Liste" else "+  Meine Liste",false) { setFavorite(id,!isFav){showHome()} },
            LinearLayout.LayoutParams(dp(210),dp(58)))
        info.addView(actions)
        hero.addView(info,FrameLayout.LayoutParams(-1,-1))
        homeRoot?.addView(hero,LinearLayout.LayoutParams(-1,dp(480)).apply { bottomMargin=dp(8) })
    }

    private fun addContinueRow(data:List<JSONObject>) {
        if(data.isEmpty()) return
        homeRoot?.addView(sectionTitle("Weiterschauen"))
        val row=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; setPadding(dp(68),dp(8),dp(60),dp(30)); clipChildren=false; clipToPadding=false }
        data.forEach { item ->
            val id=item.optString("Id"); if(id.isBlank()) return@forEach
            val ticks=item.optJSONObject("UserData")?.optLong("PlaybackPositionTicks",0L) ?: 0L
            val total=item.optLong("RunTimeTicks",0L)
            val card=LinearLayout(this).apply {
                orientation=LinearLayout.VERTICAL; isFocusable=true; isClickable=true
                setOnClickListener { playNative(id,ticks) }; applyCardFocus(this)
            }
            val img=ImageView(this).apply { scaleType=ImageView.ScaleType.CENTER_CROP }
            card.addView(img,LinearLayout.LayoutParams(dp(310),dp(174))); loadImage(img,id,"Backdrop",620)
            card.addView(TextView(this).apply { text=item.optString("Name"); setTextColor(Color.WHITE); textSize=15f; maxLines=1; setPadding(dp(4),dp(7),0,0) },LinearLayout.LayoutParams(dp(310),dp(30)))
            val remaining=((total-ticks).coerceAtLeast(0L)/600_000_000L)
            val ep=item.optInt("IndexNumber",0); val season=item.optInt("ParentIndexNumber",0)
            val resumeMeta=listOfNotNull(if(season>0&&ep>0)"S$season:E$ep" else null,if(remaining>0)"Noch $remaining Min." else null).joinToString("  •  ")
            if(resumeMeta.isNotBlank()) card.addView(TextView(this).apply{text=resumeMeta;textSize=12f;setTextColor(Color.GRAY);setPadding(dp(4),0,0,0)},LinearLayout.LayoutParams(dp(310),dp(24)))
            if(ticks>0 && total>0) card.addView(ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal).apply {
                max=1000; progress=((ticks.toDouble()/total)*1000).toInt().coerceIn(0,1000)
                progressTintList=android.content.res.ColorStateList.valueOf(Color.rgb(126,87,255))
            },LinearLayout.LayoutParams(dp(310),dp(5)))
            row.addView(card,LinearLayout.LayoutParams(dp(322),dp(230)).apply{marginEnd=dp(12)})
        }
        homeRoot?.addView(HorizontalScrollView(this).apply{isHorizontalScrollBarEnabled=false;clipChildren=false;addView(row)},LinearLayout.LayoutParams(-1,dp(268)))
    }

    private fun addRankedRow(title:String,data:List<JSONObject>) {
        if(data.isEmpty()) return
        homeRoot?.addView(sectionTitle(title))
        val row=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; setPadding(dp(68),dp(8),dp(60),dp(30)); clipChildren=false }
        data.take(10).forEachIndexed { index,item ->
            val wrap=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; gravity=Gravity.BOTTOM; isFocusable=true; isClickable=true
                setOnClickListener { if(item.optString("Type")=="Series") showSeries(item.optString("Id")) else showDetails(item.optString("Id")) }; applyCardFocus(this) }
            wrap.addView(TextView(this).apply { text="${index+1}"; textSize=68f; typeface=Typeface.DEFAULT_BOLD; setTextColor(0xFF2D2D35.toInt()); gravity=Gravity.BOTTOM },LinearLayout.LayoutParams(dp(58),dp(218)))
            val img=ImageView(this).apply { scaleType=ImageView.ScaleType.CENTER_CROP }
            img.clipToOutline=true
            img.outlineProvider=object:android.view.ViewOutlineProvider(){override fun getOutline(v:View,o:android.graphics.Outline){o.setRoundRect(0,0,v.width.coerceAtLeast(1),v.height.coerceAtLeast(1),dp(12).toFloat())}}
            wrap.addView(img,LinearLayout.LayoutParams(dp(154),dp(218))); loadImage(img,item.optString("Id"),"Primary",380)
            row.addView(wrap,LinearLayout.LayoutParams(dp(220),dp(228)).apply{marginEnd=dp(4)})
        }
        homeRoot?.addView(HorizontalScrollView(this).apply{isHorizontalScrollBarEnabled=false;clipChildren=false;addView(row)},LinearLayout.LayoutParams(-1,dp(246)))
    }

    private fun sectionTitle(label:String)=TextView(this).apply {
        text=label; setTextColor(Color.WHITE); textSize=24f; typeface=Typeface.DEFAULT_BOLD
        setPadding(dp(68),dp(18),0,dp(8))
    }

    private fun applyCardFocus(v:View) {
        v.setOnFocusChangeListener { view,focused ->
            view.animate().scaleX(if(focused)1.075f else 1f).scaleY(if(focused)1.075f else 1f)
                .translationZ(if(focused)dp(14).toFloat() else 0f).setDuration(140).start()
        }
    }

    private fun addRow(title: String, data: List<JSONObject>) {
        if(data.isEmpty()) return
        homeRoot?.addView(sectionTitle(title))
        val row=LinearLayout(this).apply {
            orientation=LinearLayout.HORIZONTAL
            setPadding(dp(52),dp(8),dp(52),dp(22))
            clipChildren=false
            clipToPadding=false
        }
        data.forEach { item ->
            val id=item.optString("Id")
            val type=item.optString("Type")
            if(id.isBlank()) return@forEach
            val card=LinearLayout(this).apply {
                orientation=LinearLayout.VERTICAL; isFocusable=true; isClickable=true
                setPadding(dp(3),dp(3),dp(3),dp(3))
                setOnClickListener { if(type=="Series") showSeries(id) else showDetails(id) }
                applyCardFocus(this)
            }
            val img=ImageView(this).apply {
                scaleType=ImageView.ScaleType.CENTER_CROP
                clipToOutline=true
                outlineProvider=object:android.view.ViewOutlineProvider(){override fun getOutline(v:View,o:android.graphics.Outline){o.setRoundRect(0,0,v.width.coerceAtLeast(1),v.height.coerceAtLeast(1),dp(12).toFloat())}}
            }
            card.addView(img,LinearLayout.LayoutParams(dp(176),dp(248)))
            loadImage(img,id,"Primary",420)
            card.setOnLongClickListener { showDetails(id); true }
            val progress=item.optJSONObject("UserData")?.optLong("PlaybackPositionTicks",0L) ?: 0L
            val total=item.optLong("RunTimeTicks",0L)
            if(progress>0 && total>0) card.addView(ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal).apply {
                max=1000; this.progress=((progress.toDouble()/total)*1000).toInt().coerceIn(0,1000)
                progressTintList=android.content.res.ColorStateList.valueOf(Color.rgb(126,87,255))
            },LinearLayout.LayoutParams(dp(176),dp(5)))
            row.addView(card,LinearLayout.LayoutParams(dp(188),dp(304)).apply { marginEnd=dp(8) })
        }
        homeRoot?.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled=false; clipChildren=false; clipToPadding=false; addView(row)
        },LinearLayout.LayoutParams(-1,dp(334)))
    }


    private fun showMediaFolderLibrary(title:String, type:String) {
        rememberBack { showHome() }
        val root=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(48),dp(28),dp(48),dp(28)); setBackgroundColor(Color.rgb(8,8,13)) }
        root.addView(TextView(this).apply { text=title; textSize=32f; setTextColor(Color.WHITE); typeface=Typeface.DEFAULT_BOLD })
        root.addView(ProgressBar(this))
        setContentView(ScrollView(this).apply { addView(root) })
        io.execute {
            try {
                val views=items("/Users/$userId/Views")
                fun normalized(value:String)=java.text.Normalizer.normalize(value.lowercase(),java.text.Normalizer.Form.NFD)
                    .replace(Regex("[\\u0300-\\u036f]"),"")
                    .replace(Regex("[^a-z0-9]"),"")
                val aliases=if(type=="Movie")
                    listOf("animemovies","animefilme","animefilms","animefilme")
                else listOf("anime","animes","animeserien","animeseries")
                val folder=views.firstOrNull { view -> aliases.contains(normalized(view.optString("Name"))) }
                val folderId=folder?.optString("Id").orEmpty()
                val data=if(folderId.isBlank()) emptyList() else items("/Users/$userId/Items?ParentId=$folderId&Recursive=true&Limit=250&SortBy=SortName&SortOrder=Ascending&IncludeItemTypes=$type&Fields=PrimaryImageAspectRatio")
                runOnUiThread {
                    root.removeViewAt(1); homeRoot=root
                    if(folderId.isBlank()) {
                        val names=views.mapNotNull{it.optString("Name").takeIf(String::isNotBlank)}.joinToString(", ")
                        root.addView(TextView(this).apply { text="Die Jellyfin-Bibliothek „$title“ wurde nicht gefunden.${if(names.isNotBlank()) "\nGefunden: $names" else ""}"; textSize=18f; setTextColor(Color.LTGRAY); setPadding(0,dp(24),0,dp(24)) })
                    } else if(data.isEmpty()) root.addView(TextView(this).apply { text="In „$title“ wurden keine passenden Inhalte gefunden."; textSize=18f; setTextColor(Color.LTGRAY); setPadding(0,dp(24),0,dp(24)) })
                    else addRow(title,data)
                }
            } catch(e:Exception){ runOnUiThread{toast("$title konnten nicht geladen werden")} }
        }
    }

    private fun showGenreLibrary(title:String, type:String) {
        rememberBack { showHome() }
        val root=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(48),dp(28),dp(48),dp(28)); setBackgroundColor(Color.rgb(8,8,13)) }
        root.addView(TextView(this).apply { text=title; textSize=32f; setTextColor(Color.WHITE) })
        root.addView(ProgressBar(this))
        setContentView(ScrollView(this).apply { addView(root) })
        io.execute {
            try {
                val data=items("/Users/$userId/Items?Recursive=true&Limit=100&SortBy=SortName&SortOrder=Ascending&IncludeItemTypes=$type&Genres=Anime&Fields=PrimaryImageAspectRatio")
                runOnUiThread {
                    root.removeViewAt(1); homeRoot=root
                    if(data.isEmpty()) root.addView(TextView(this).apply { text="Keine $title gefunden. Prüfe, ob deine Jellyfin-Titel das Genre „Anime“ verwenden."; textSize=18f; setTextColor(Color.LTGRAY); setPadding(0,dp(24),0,dp(24)) })
                    else addRow(title,data)
                }
            } catch(e:Exception){ runOnUiThread{toast("$title konnten nicht geladen werden")} }
        }
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
        val root=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;setPadding(dp(54),dp(48),dp(54),dp(40));setBackgroundColor(Color.rgb(8,8,13))}
        val left=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(0,0,dp(36),0)}
        left.addView(TextView(this).apply{text="Suche";textSize=36f;setTextColor(Color.WHITE);typeface=Typeface.DEFAULT_BOLD})
        val input=EditText(this).apply{
            hint="Titel suchen…";setTextColor(Color.WHITE);setHintTextColor(Color.GRAY);textSize=20f;setSingleLine()
            background=GradientDrawable().apply{setColor(0xFF202026.toInt());cornerRadius=dp(10).toFloat()}
            setPadding(dp(18),0,dp(18),0)
            setOnEditorActionListener{_,_,_->val q=text.toString().trim();if(q.isNotBlank())search(q);true}
        }
        left.addView(input,LinearLayout.LayoutParams(dp(440),dp(64)).apply{topMargin=dp(24)})
        left.addView(actionButton("Suchen",true){val q=input.text.toString().trim();if(q.isNotBlank())search(q)},LinearLayout.LayoutParams(dp(180),dp(58)).apply{topMargin=dp(16)})
        root.addView(left,LinearLayout.LayoutParams(dp(500),-1))
        root.addView(TextView(this).apply{text="Gib einen Film, eine Serie oder einen Anime ein.\nDie Ergebnisse erscheinen als Poster.";textSize=20f;setTextColor(Color.LTGRAY);gravity=Gravity.CENTER},LinearLayout.LayoutParams(0,-1,1f))
        setContentView(root);input.requestFocus()
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
                    val root=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setBackgroundColor(Color.rgb(8,8,13)) }
                    val hero=FrameLayout(this)
                    val bg=ImageView(this).apply{scaleType=ImageView.ScaleType.CENTER_CROP}; hero.addView(bg,FrameLayout.LayoutParams(-1,dp(390))); loadImage(bg,seriesId,"Backdrop",1600)
                    hero.addView(View(this).apply{background=GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,intArrayOf(0xF708080D.toInt(),0xA808080D.toInt(),0x2208080D))},FrameLayout.LayoutParams(-1,-1))
                    val info=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;gravity=Gravity.BOTTOM;setPadding(dp(54),dp(80),dp(54),dp(38))}
                    info.addView(TextView(this).apply{text=series.optString("Name");textSize=40f;setTextColor(Color.WHITE);typeface=Typeface.DEFAULT_BOLD})
                    info.addView(TextView(this).apply{text=series.optString("Overview");textSize=16f;setTextColor(Color.LTGRAY);maxLines=3;setPadding(0,dp(10),0,0)},LinearLayout.LayoutParams(dp(720),-2))
                    val favorite=series.optJSONObject("UserData")?.optBoolean("IsFavorite",false)?:false
                    val acts=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;setPadding(0,dp(16),0,0)}
                    acts.addView(actionButton(if(favorite)"✓  Meine Liste" else "+  Meine Liste",false){setFavorite(seriesId,!favorite){showSeries(seriesId)}},LinearLayout.LayoutParams(dp(220),dp(56)).apply{marginEnd=dp(10)})
                    acts.addView(actionButton("Zufällige Folge",false){playRandomEpisode(seriesId)},LinearLayout.LayoutParams(dp(200),dp(56)))
                    info.addView(acts); hero.addView(info,FrameLayout.LayoutParams(-1,-1)); root.addView(hero,LinearLayout.LayoutParams(-1,dp(390)))
                    root.addView(sectionTitle("Staffeln"))
                    val seasonRow=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;setPadding(dp(52),dp(8),dp(52),dp(22))}
                    seasons.forEach { season -> seasonRow.addView(actionButton(season.optString("Name","Staffel"),false){showSeason(seriesId,season.optString("Id"))},LinearLayout.LayoutParams(dp(180),dp(56)).apply{marginEnd=dp(10)}) }
                    root.addView(HorizontalScrollView(this).apply{isHorizontalScrollBarEnabled=false;addView(seasonRow)})
                    setContentView(ScrollView(this).apply{isVerticalScrollBarEnabled=false;addView(root)})
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
                        val card=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER_VERTICAL; isFocusable=true; isClickable=true; setPadding(dp(8),dp(8),dp(8),dp(8)); setOnClickListener { startEpisode(eid,ticks,nextId) }; applyCardFocus(this) }
                        val thumb=ImageView(this).apply { scaleType=ImageView.ScaleType.CENTER_CROP }
                        card.addView(thumb,LinearLayout.LayoutParams(dp(250),dp(140))); loadImage(thumb,eid,"Primary",500)
                        val info=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(18),0,0,0) }
                        info.addView(TextView(this).apply { text=(if(number>0) "$number. " else "")+name; textSize=20f; setTextColor(Color.WHITE); typeface=Typeface.DEFAULT_BOLD })
                        val runtime=ep.optLong("RunTimeTicks",0L)/600_000_000L
                        if(runtime>0) info.addView(TextView(this).apply { text="$runtime Min."; textSize=13f; setTextColor(Color.GRAY) },params(-1,-2,3))
                        info.addView(TextView(this).apply { text=ep.optString("Overview"); textSize=14f; setTextColor(Color.LTGRAY); maxLines=2 },params(-1,-2,5))
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
                val similar=runCatching { items("/Items/$id/Similar?UserId=$userId&Limit=16&Fields=PrimaryImageAspectRatio") }.getOrDefault(emptyList())
                runOnUiThread {
                    val outer=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setBackgroundColor(Color.rgb(8,8,13)) }
                    val hero=FrameLayout(this)
                    val bg=ImageView(this).apply{scaleType=ImageView.ScaleType.CENTER_CROP}; hero.addView(bg,FrameLayout.LayoutParams(-1,dp(500))); loadImage(bg,id,"Backdrop",1600)
                    hero.addView(View(this).apply{background=GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,intArrayOf(0xF708080D.toInt(),0xB008080D.toInt(),0x2208080D))},FrameLayout.LayoutParams(-1,-1))
                    val info=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;gravity=Gravity.BOTTOM;setPadding(dp(58),dp(90),dp(58),dp(42))}
                    info.addView(TextView(this).apply{text=x.optString("Name");textSize=42f;setTextColor(Color.WHITE);typeface=Typeface.DEFAULT_BOLD})
                    val year=x.optInt("ProductionYear",0); val rating=x.optDouble("CommunityRating",0.0); val runtime=x.optLong("RunTimeTicks",0L)/600_000_000L
                    val meta=listOfNotNull(if(year>0)year.toString()else null,if(runtime>0)"$runtime Min."else null,if(rating>0)"★ %.1f".format(rating)else null).joinToString("  •  ")
                    if(meta.isNotBlank()) info.addView(TextView(this).apply{text=meta;textSize=16f;setTextColor(Color.LTGRAY)})
                    info.addView(TextView(this).apply{text=x.optString("Overview");textSize=17f;setTextColor(Color.WHITE);maxLines=4;setPadding(0,dp(12),0,0)},LinearLayout.LayoutParams(dp(700),-2))
                    val ud=x.optJSONObject("UserData"); val ticks=ud?.optLong("PlaybackPositionTicks",0L)?:0L; val fav=ud?.optBoolean("IsFavorite",false)?:false
                    val actions=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;setPadding(0,dp(20),0,0)}
                    actions.addView(actionButton(if(ticks>0)"▶  Fortsetzen" else "▶  Abspielen",true){playNative(id,ticks)},LinearLayout.LayoutParams(dp(220),dp(58)).apply{marginEnd=dp(12)})
                    actions.addView(actionButton(if(fav)"✓  Meine Liste" else "+  Meine Liste",false){setFavorite(id,!fav){showDetails(id)}},LinearLayout.LayoutParams(dp(220),dp(58)).apply{marginEnd=dp(12)})
                    if(x.optString("Type")=="Series") actions.addView(actionButton("Zufällige Folge",false){playRandomEpisode(id)},LinearLayout.LayoutParams(dp(210),dp(58)))
                    info.addView(actions); hero.addView(info,FrameLayout.LayoutParams(-1,-1)); outer.addView(hero,LinearLayout.LayoutParams(-1,dp(500)))
                    homeRoot=outer
                    addRow("Ähnliche Titel",similar)
                    setContentView(ScrollView(this).apply{isVerticalScrollBarEnabled=false;addView(outer)})
                }
            } catch(e:Exception){ runOnUiThread{toast("Details konnten nicht geladen werden")} }
        }
    }

    private fun showSimilar(id:String) {
        rememberBack { showDetails(id) }
        io.execute {
            try {
                val data=items("/Items/$id/Similar?UserId=$userId&Limit=20&Fields=PrimaryImageAspectRatio")
                runOnUiThread {
                    val root=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(48),dp(28),dp(48),dp(28)); setBackgroundColor(Color.rgb(8,8,13)) }
                    root.addView(TextView(this).apply { text="Ähnliche Titel"; textSize=32f; setTextColor(Color.WHITE) })
                    homeRoot=root; addRow("Das könnte dir gefallen",data)
                    root.addView(button("Zurück") { showDetails(id) },params(220,56,18))
                    setContentView(ScrollView(this).apply { addView(root) })
                }
            } catch(e:Exception) { runOnUiThread { toast("Ähnliche Titel konnten nicht geladen werden") } }
        }
    }

    private fun playRandomEpisode(seriesId:String) {
        io.execute {
            try {
                val data=items("/Users/$userId/Items?Recursive=true&ParentId=$seriesId&IncludeItemTypes=Episode&Limit=200")
                val ep=if(data.isEmpty()) null else data[java.util.concurrent.ThreadLocalRandom.current().nextInt(data.size)]
                runOnUiThread { if(ep==null) toast("Keine Episode gefunden") else playNative(ep.optString("Id"),0L) }
            } catch(e:Exception) { runOnUiThread { toast("Zufällige Folge konnte nicht gestartet werden") } }
        }
    }

    private fun readProfiles():org.json.JSONArray {
        val stored=prefs.getString("velaris_profiles","").orEmpty()
        if(stored.isNotBlank()) return runCatching{org.json.JSONArray(stored)}.getOrElse{org.json.JSONArray()}
        val seed=org.json.JSONArray().apply {
            put(JSONObject().put("name",prefs.getString("active_profile_name","Sandro") ?: "Sandro")
                .put("avatar",prefs.getInt("active_profile_avatar",0)))
        }
        prefs.edit().putString("velaris_profiles",seed.toString()).apply()
        return seed
    }

    private fun showProfiles(startup:Boolean=false) {
        if(!startup) rememberBack { showHome() }
        val profiles=readProfiles()
        val root=LinearLayout(this).apply{
            orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER
            setPadding(dp(70),dp(34),dp(70),dp(34));setBackgroundColor(Color.rgb(8,8,13))
        }
        root.addView(TextView(this).apply{
            text="Wer schaut gerade?";textSize=40f;setTextColor(Color.WHITE)
            typeface=Typeface.DEFAULT_BOLD;gravity=Gravity.CENTER
        },params(-1,-2,0))
        root.addView(TextView(this).apply{
            text="Wähle dein Profil";textSize=17f;setTextColor(0xFF9B9BA4.toInt());gravity=Gravity.CENTER
        },params(-1,-2,8))

        val grid=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER;setPadding(0,dp(26),0,0)}
        var row=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER}
        fun addTile(tile:View,index:Int){
            if(index>0 && index%5==0){grid.addView(row);row=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER}}
            row.addView(tile,LinearLayout.LayoutParams(dp(180),dp(205)).apply{marginEnd=dp(12)})
        }
        for(i in 0 until profiles.length()) {
            val p=profiles.optJSONObject(i) ?: continue
            val name=p.optString("name","Profil");val av=p.optInt("avatar",i)
            val tile=LinearLayout(this).apply{
                orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER;isFocusable=true;isClickable=true
                setPadding(dp(8),dp(8),dp(8),dp(4))
                setOnFocusChangeListener{v,focused->
                    v.animate().scaleX(if(focused)1.08f else 1f).scaleY(if(focused)1.08f else 1f).setDuration(120).start()
                    background=GradientDrawable().apply{
                        setColor(if(focused)0xFF222229.toInt() else Color.TRANSPARENT)
                        cornerRadius=dp(12).toFloat()
                        if(focused)setStroke(dp(2),Color.WHITE)
                    }
                }
                setOnClickListener{
                    prefs.edit().putString("active_profile_name",name).putInt("active_profile_avatar",av).apply()
                    showHome()
                }
            }
            tile.addView(avatarFace(av,name),LinearLayout.LayoutParams(dp(142),dp(142)))
            tile.addView(TextView(this).apply{
                text=name;textSize=17f;setTextColor(0xFFBDBDC4.toInt());gravity=Gravity.CENTER;maxLines=1
            },LinearLayout.LayoutParams(dp(164),dp(42)))
            addTile(tile,i)
        }
        if(profiles.length()<10){
            val add=LinearLayout(this).apply{
                orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER;isFocusable=true;isClickable=true
                setPadding(dp(8),dp(8),dp(8),dp(4));setOnClickListener{showCreateProfile()}
                setOnFocusChangeListener{v,focused->
                    v.animate().scaleX(if(focused)1.08f else 1f).scaleY(if(focused)1.08f else 1f).setDuration(120).start()
                    background=GradientDrawable().apply{setColor(if(focused)0xFF222229.toInt() else Color.TRANSPARENT);cornerRadius=dp(12).toFloat();if(focused)setStroke(dp(2),Color.WHITE)}
                }
            }
            add.addView(TextView(this).apply{
                text="+";textSize=54f;setTextColor(0xFFBDBDC4.toInt());gravity=Gravity.CENTER
                background=GradientDrawable().apply{setColor(0xFF24242A.toInt());cornerRadius=dp(14).toFloat()}
            },LinearLayout.LayoutParams(dp(142),dp(142)))
            add.addView(TextView(this).apply{text="Profil hinzufügen";textSize=15f;setTextColor(0xFFBDBDC4.toInt());gravity=Gravity.CENTER},LinearLayout.LayoutParams(dp(164),dp(42)))
            addTile(add,profiles.length())
        }
        grid.addView(row);root.addView(grid);setContentView(root)
        root.post{root.focusSearch(View.FOCUS_DOWN)?.requestFocus()}
    }

    private fun avatarFace(index:Int,initial:String):View {
        val avatars=intArrayOf(
            R.drawable.avatar_seal_white,
            R.drawable.avatar_seal_gray
        )
        return ImageView(this).apply {
            scaleType=ImageView.ScaleType.CENTER_CROP
            setImageResource(avatars[index.coerceIn(0,avatars.lastIndex)])
            clipToOutline=true
            outlineProvider=object:android.view.ViewOutlineProvider(){
                override fun getOutline(v:View,o:android.graphics.Outline){
                    o.setRoundRect(0,0,v.width.coerceAtLeast(1),v.height.coerceAtLeast(1),dp(16).toFloat())
                }
            }
            contentDescription=if(initial.isBlank()) "Avatar "+(index+1) else "Avatar "+initial
        }
    }

    private fun showCreateProfile() {
        val root=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER;setPadding(dp(80),dp(50),dp(80),dp(50));setBackgroundColor(Color.rgb(8,8,13))}
        root.addView(TextView(this).apply{text="Profil erstellen";textSize=36f;setTextColor(Color.WHITE);typeface=Typeface.DEFAULT_BOLD},params(-2,-2,0))
        val name=EditText(this).apply{hint="Profilname";setTextColor(Color.WHITE);setHintTextColor(Color.GRAY);setSingleLine();background=GradientDrawable().apply{setColor(0xFF202026.toInt());cornerRadius=dp(10).toFloat()};setPadding(dp(18),0,dp(18),0)}
        root.addView(name,params(460,62,26))
        root.addView(TextView(this).apply{text="Avatar auswählen";textSize=18f;setTextColor(Color.LTGRAY)},params(-2,-2,18))
        val avatars=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER}
        var selected=0
        val colors=IntArray(2){it}
        colors.forEachIndexed{i,_->avatars.addView(avatarFace(i,"").apply{isFocusable=true;isClickable=true;applyCardFocus(this);setOnClickListener{selected=i}},LinearLayout.LayoutParams(dp(82),dp(82)).apply{marginEnd=dp(10)})}
        root.addView(HorizontalScrollView(this).apply{isHorizontalScrollBarEnabled=false;addView(avatars)},params(-1,86,12))
        root.addView(actionButton("Profil erstellen",true){
            val n=name.text.toString().trim();if(n.isBlank())return@actionButton toast("Profilname eingeben")
            val arr=readProfiles()
            if(arr.length()>=10)return@actionButton toast("Maximal 10 Profile")
            if((0 until arr.length()).any{arr.optJSONObject(it)?.optString("name","")?.equals(n,true)==true})return@actionButton toast("Profilname existiert bereits")
            arr.put(JSONObject().put("name",n).put("avatar",selected));prefs.edit().putString("velaris_profiles",arr.toString()).apply();showProfiles()
        },params(230,58,24))
        setContentView(root);name.requestFocus()
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
        val cacheKey="$id:$type:$width"
        imageCache.get(cacheKey)?.let { view.setImageBitmap(it); return }
        view.clipToOutline=true
        view.outlineProvider=object:android.view.ViewOutlineProvider() {
            override fun getOutline(v:View,outline:android.graphics.Outline) {
                outline.setRoundRect(0,0,v.width.coerceAtLeast(1),v.height.coerceAtLeast(1),dp(10).toFloat())
            }
        }
        val disk=java.io.File(cacheDir,"img_"+cacheKey.hashCode().toUInt().toString(16)+".jpg")
        if(disk.exists()) {
            val bmp=runCatching{android.graphics.BitmapFactory.decodeFile(disk.absolutePath)}.getOrNull()
            if(bmp!=null) { imageCache.put(cacheKey,bmp); view.setImageBitmap(bmp); return } else disk.delete()
        }
        if(!imageLoads.add(cacheKey)) return
        io.execute {
            try {
                val conn=URL("$server/Items/$id/Images/$type?maxWidth=$width&quality=82").openConnection() as HttpURLConnection
                conn.setRequestProperty("X-Emby-Token",token); conn.connectTimeout=4500; conn.readTimeout=7000
                try {
                    if(conn.responseCode in 200..299) {
                        val bytes=conn.inputStream.use{it.readBytes()}
                        val bmp=android.graphics.BitmapFactory.decodeByteArray(bytes,0,bytes.size)
                        if(bmp!=null && !destroyed) {
                            imageCache.put(cacheKey,bmp)
                            runCatching{disk.writeBytes(bytes)}
                            runOnUiThread{if(!isFinishing&&!isDestroyed)view.setImageBitmap(bmp)}
                        }
                    }
                } finally { conn.disconnect() }
            } catch(_:Exception) {} finally { imageLoads.remove(cacheKey) }
        }
    }

    private fun loadImageWithFallback(view:ImageView,id:String,type:String,width:Int,onMissing:()->Unit) {
        if(id.isBlank() || server.isBlank() || token.isBlank()) return onMissing()
        io.execute {
            try {
                val conn=URL("$server/Items/$id/Images/$type?maxWidth=$width&quality=92").openConnection() as HttpURLConnection
                try {
                    conn.setRequestProperty("X-Emby-Token",token);conn.connectTimeout=5000;conn.readTimeout=7000
                    if(conn.responseCode in 200..299) {
                        val bmp=conn.inputStream.use{android.graphics.BitmapFactory.decodeStream(it)}
                        runOnUiThread{if(bmp!=null){view.visibility=View.VISIBLE;view.setImageBitmap(bmp)}else onMissing()}
                    } else runOnUiThread{onMissing()}
                } finally {conn.disconnect()}
            } catch(_:Exception){runOnUiThread{onMissing()}}
        }
    }

    private fun request(path:String, method:String="GET", body:String?=null, auth:Boolean=true):JSONObject {
        if(server.isBlank()) throw IllegalStateException("Kein Server konfiguriert")
        val conn=URL(server+path).openConnection() as HttpURLConnection
        try {
            conn.requestMethod=method; conn.connectTimeout=8000; conn.readTimeout=12000
            conn.setRequestProperty("Accept","application/json")
            conn.setRequestProperty("Authorization", """MediaBrowser Client="Velaris TV", Device="Android TV", DeviceId="velaris-tv", Version="0.7.0"${if(auth && token.isNotBlank()) ", Token=\"$token\"" else ""}""")
            if(body!=null){ conn.doOutput=true; conn.setRequestProperty("Content-Type","application/json"); conn.outputStream.use{it.write(body.toByteArray())} }
            val code=conn.responseCode
            val text=(if(code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use{it.readText()}.orEmpty()
            if(code == 401 || code == 403) throw SecurityException("Sitzung abgelaufen")
            if(code !in 200..299) throw IllegalStateException("Serverfehler HTTP $code")
            return if(text.trim().startsWith("[")) JSONObject().put("array",org.json.JSONArray(text)) else if(text.isBlank()) JSONObject() else JSONObject(text)
        } finally { conn.disconnect() }
    }

    private fun showSettingsDialog() {
        rememberBack { showHome() }
        val root=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(64),dp(44),dp(64),dp(44));setBackgroundColor(Color.rgb(8,8,13))}
        root.addView(TextView(this).apply{text="Einstellungen";textSize=38f;setTextColor(Color.WHITE);typeface=Typeface.DEFAULT_BOLD})
        root.addView(TextView(this).apply{text="Velaris TV";textSize=16f;setTextColor(Color.GRAY);setPadding(0,dp(4),0,dp(24))})
        val playback=settingsCard("▶","Wiedergabe","Autoplay, Intro, Rückblick und Abspann"){showPlaybackSettings()}
        val profiles=settingsCard("●","Profile","Profile verwalten und wechseln"){showProfiles()}
        val serverCard=settingsCard("◉","Server",server.ifBlank{"Nicht verbunden"}){clearConnection();showServer()}
        val logout=settingsCard("↪","Abmelden","Jellyfin-Sitzung auf diesem TV beenden"){token="";userId="";prefs.edit().remove("access_token").remove("user_id").apply();showLogin()}
        listOf(playback,profiles,serverCard,logout).forEach{root.addView(it,LinearLayout.LayoutParams(-1,dp(86)).apply{bottomMargin=dp(12)})}
        setContentView(root)
    }

    private fun settingsCard(icon:String,title:String,sub:String,click:()->Unit)=LinearLayout(this).apply{
        orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;setPadding(dp(22),0,dp(22),0);isFocusable=true;isClickable=true
        background=GradientDrawable().apply{setColor(0xFF18181E.toInt());cornerRadius=dp(12).toFloat()}
        addView(TextView(this@MainActivity).apply{text=icon;textSize=26f;setTextColor(Color.WHITE);gravity=Gravity.CENTER},LinearLayout.LayoutParams(dp(54),dp(54)))
        addView(LinearLayout(this@MainActivity).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(18),0,0,0);addView(TextView(this@MainActivity).apply{text=title;textSize=20f;setTextColor(Color.WHITE);typeface=Typeface.DEFAULT_BOLD});addView(TextView(this@MainActivity).apply{text=sub;textSize=14f;setTextColor(Color.GRAY)})},LinearLayout.LayoutParams(0,-2,1f))
        addView(TextView(this@MainActivity).apply{text="›";textSize=30f;setTextColor(Color.LTGRAY)})
        setOnClickListener{click()};setOnFocusChangeListener{v,f->background=GradientDrawable().apply{setColor(if(f)0xFF303038.toInt() else 0xFF18181E.toInt());cornerRadius=dp(12).toFloat()};v.animate().scaleX(if(f)1.015f else 1f).scaleY(if(f)1.015f else 1f).setDuration(120).start()}
    }

    private fun showPlaybackSettings() {
        val root=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(64),dp(44),dp(64),dp(44));setBackgroundColor(Color.rgb(8,8,13))}
        root.addView(TextView(this).apply{text="Wiedergabe";textSize=36f;setTextColor(Color.WHITE);typeface=Typeface.DEFAULT_BOLD})
        val options=listOf("Automatisch nächste Folge" to "auto_next","Intro automatisch überspringen" to "auto_skip_intro","Rückblick automatisch überspringen" to "auto_skip_recap","Abspann automatisch überspringen" to "auto_skip_credits")
        options.forEachIndexed{i,(label,key)->
            val row=settingsCard(if(prefs.getBoolean(key,i==0))"●" else "○",label,if(prefs.getBoolean(key,i==0))"Ein" else "Aus"){
                val value=!prefs.getBoolean(key,i==0);prefs.edit().putBoolean(key,value).apply();showPlaybackSettings()
            }
            root.addView(row,LinearLayout.LayoutParams(-1,dp(82)).apply{topMargin=dp(10)})
        }
        root.addView(actionButton("Zurück",false){showSettingsDialog()},params(180,56,22));setContentView(root)
    }


    private fun clearConnection(){ server="";token="";userId="";prefs.edit().clear().apply() }
    private fun setupPage(title:String,sub:String,first:View)=LinearLayout(this).apply {
        orientation=LinearLayout.VERTICAL; gravity=Gravity.CENTER; setPadding(dp(72),dp(36),dp(72),dp(36)); setBackgroundColor(Color.BLACK)
        addView(ImageView(this@MainActivity).apply{setImageResource(R.drawable.velaris_logo);scaleType=ImageView.ScaleType.FIT_CENTER},params(180,180,0))
        addView(TextView(this@MainActivity).apply{text=title;textSize=30f;setTextColor(Color.WHITE);gravity=Gravity.CENTER},params(-2,-2,12))
        addView(TextView(this@MainActivity).apply{text=sub;textSize=16f;setTextColor(Color.LTGRAY);gravity=Gravity.CENTER},params(-2,-2,8))
        addView(first,params(600,62,22))
    }
    private fun navButton(label:String, click:()->Unit)=Button(this).apply {
        text=label; contentDescription=label; isAllCaps=false; textSize=15f; setSingleLine(true); includeFontPadding=false
        setTextColor(Color.WHITE); isFocusable=true; isClickable=true
        setPadding(dp(9),0,dp(9),0)
        background=GradientDrawable().apply {
            setColor(Color.TRANSPARENT)
            cornerRadius=dp(7).toFloat()
        }
        stateListAnimator=null
        elevation=0f
        setOnClickListener { click() }
        setOnFocusChangeListener { v, focused ->
            background=GradientDrawable().apply {
                setColor(if(focused) 0x663F3F46 else Color.TRANSPARENT)
                cornerRadius=dp(7).toFloat()
            }
            v.animate()
                .scaleX(if(focused)1.03f else 1f)
                .scaleY(if(focused)1.03f else 1f)
                .setDuration(110).start()
        }
    }

    private fun actionButton(label:String, primary:Boolean, click:()->Unit)=Button(this).apply {
        text=label; contentDescription=label; isAllCaps=false; textSize=17f; typeface=Typeface.DEFAULT_BOLD
        setTextColor(if(primary) Color.BLACK else Color.WHITE); isFocusable=true
        background=GradientDrawable().apply {
            setColor(if(primary) Color.WHITE else 0x99282830.toInt()); cornerRadius=dp(8).toFloat()
        }
        setOnClickListener { click() }
        setOnFocusChangeListener { v, focused ->
            background=GradientDrawable().apply {
                setColor(if(focused) Color.rgb(126,87,255) else if(primary) Color.WHITE else 0x99282830.toInt())
                cornerRadius=dp(8).toFloat()
            }
            setTextColor(if(focused || !primary) Color.WHITE else Color.BLACK)
            v.animate().scaleX(if(focused)1.05f else 1f).scaleY(if(focused)1.05f else 1f).setDuration(100).start()
        }
    }

    private fun button(label:String, click:()->Unit)=Button(this).apply {
        text=label; contentDescription=label; isAllCaps=false; textSize=17f; setTextColor(Color.WHITE); isFocusable=true; minHeight=dp(48)
        backgroundTintList=android.content.res.ColorStateList.valueOf(Color.rgb(83,50,205)); setOnClickListener{click()}
    }
    private fun params(w:Int,h:Int,top:Int)=LinearLayout.LayoutParams(if(w<0)w else dp(w),if(h<0)h else dp(h)).apply{topMargin=dp(top)}
    private fun normalize(raw:String):String? { var v=raw.trim();if(v.isBlank())return null;if(!v.contains("://"))v="http://$v";val u=runCatching{Uri.parse(v)}.getOrNull()?:return null;return if(u.host.isNullOrBlank() || (u.scheme!="http" && u.scheme!="https"))null else v.trimEnd('/') }
    private fun toast(s:String)=Toast.makeText(this,s,Toast.LENGTH_LONG).show()
    private fun dp(v:Int)=(v*resources.displayMetrics.density).toInt()
    private fun immersive() {
        // Some Android/Google TV firmware exposes WindowInsetsController only after
        // the decor view has been attached. Defer immersive mode until then so
        // startup never depends on an unavailable DecorView.
        window.decorView.post {
            if (isFinishing || isDestroyed) return@post
            if (Build.VERSION.SDK_INT >= 30) {
                window.decorView.windowInsetsController?.apply {
                    hide(WindowInsets.Type.systemBars())
                    systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                }
            } else {
                @Suppress("DEPRECATION")
                window.decorView.systemUiVisibility = 5894
            }
        }
    }
}
