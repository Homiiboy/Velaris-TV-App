package com.novarion.velaristv

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Space
import android.widget.TextView

class MainActivity : Activity() {
    private val preferences by lazy {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
    }

    private val backHandler = Handler(Looper.getMainLooper())
    private var backLongPressTriggered = false
    private var webView: WebView? = null
    private var webContainer: FrameLayout? = null
    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null

    private val openSettingsRunnable = Runnable {
        backLongPressTriggered = true
        val currentUrl = preferences.getString(KEY_SERVER_URL, "").orEmpty()
        showServerSetup(currentUrl)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        applyImmersiveMode()

        val serverUrl = preferences.getString(KEY_SERVER_URL, "").orEmpty()
        if (serverUrl.isBlank()) {
            showServerSetup("")
        } else {
            openVelaris(serverUrl)
        }
    }

    override fun onResume() {
        super.onResume()
        applyImmersiveMode()
        webView?.onResume()
    }

    override fun onPause() {
        webView?.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        backHandler.removeCallbacks(openSettingsRunnable)
        disposeWebView()
        super.onDestroy()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode != KeyEvent.KEYCODE_BACK) {
            return super.dispatchKeyEvent(event)
        }

        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (event.repeatCount == 0) {
                    backLongPressTriggered = false
                    backHandler.postDelayed(openSettingsRunnable, LONG_BACK_PRESS_MS)
                }
                return true
            }

            KeyEvent.ACTION_UP -> {
                backHandler.removeCallbacks(openSettingsRunnable)
                if (!backLongPressTriggered) {
                    handleShortBackPress()
                }
                return true
            }
        }

        return true
    }

    private fun handleShortBackPress() {
        when {
            customView != null -> hideCustomView()
            webView?.canGoBack() == true -> webView?.goBack()
            else -> moveTaskToBack(true)
        }
    }

    private fun showServerSetup(prefill: String) {
        disposeWebView()
        applyImmersiveMode()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(72), dp(36), dp(72), dp(36))
            setBackgroundColor(Color.BLACK)
        }

        val logo = ImageView(this).apply {
            setImageResource(R.drawable.velaris_logo)
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        root.addView(logo, LinearLayout.LayoutParams(dp(190), dp(190)))

        root.addView(Space(this), LinearLayout.LayoutParams(1, dp(18)))

        val title = TextView(this).apply {
            text = getString(R.string.server_title)
            setTextColor(Color.WHITE)
            textSize = 28f
            gravity = Gravity.CENTER
        }
        root.addView(title)

        root.addView(Space(this), LinearLayout.LayoutParams(1, dp(10)))

        val description = TextView(this).apply {
            text = getString(R.string.server_description)
            setTextColor(Color.rgb(190, 190, 205))
            textSize = 16f
            gravity = Gravity.CENTER
        }
        root.addView(description)

        root.addView(Space(this), LinearLayout.LayoutParams(1, dp(22)))

        val input = EditText(this).apply {
            setText(prefill)
            hint = getString(R.string.server_hint)
            setHintTextColor(Color.rgb(110, 110, 130))
            setTextColor(Color.WHITE)
            textSize = 18f
            singleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            backgroundTintList = ColorStateList.valueOf(Color.rgb(105, 78, 255))
            setPadding(dp(14), dp(10), dp(14), dp(10))
        }
        root.addView(input, LinearLayout.LayoutParams(dp(620), dp(62)))

        root.addView(Space(this), LinearLayout.LayoutParams(1, dp(18)))

        val errorText = TextView(this).apply {
            setTextColor(Color.rgb(255, 110, 150))
            textSize = 14f
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        root.addView(errorText)

        val connectButton = createTvButton(getString(R.string.connect)).apply {
            setOnClickListener {
                val normalized = normalizeUrl(input.text.toString())
                if (normalized == null) {
                    errorText.text = "Bitte eine gültige Velaris-Adresse eingeben."
                    errorText.visibility = View.VISIBLE
                    input.requestFocus()
                    return@setOnClickListener
                }

                preferences.edit().putString(KEY_SERVER_URL, normalized).apply()
                openVelaris(normalized)
            }
        }
        root.addView(connectButton, LinearLayout.LayoutParams(dp(230), dp(64)).apply {
            topMargin = dp(16)
        })

        val hint = TextView(this).apply {
            text = "Tipp: Zur Serverauswahl später Zurück ca. 1,2 Sekunden gedrückt halten."
            setTextColor(Color.rgb(125, 125, 145))
            textSize = 13f
            gravity = Gravity.CENTER
        }
        root.addView(hint, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(20)
        })

        setContentView(root)
        if (prefill.isBlank()) input.requestFocus() else connectButton.requestFocus()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun openVelaris(serverUrl: String) {
        disposeWebView()
        applyImmersiveMode()

        val container = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
        }
        webContainer = container

        val browser = WebView(this).apply {
            setBackgroundColor(Color.BLACK)
            isFocusable = true
            isFocusableInTouchMode = true

            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                mediaPlaybackRequiresUserGesture = false
                allowFileAccess = false
                allowContentAccess = false
                builtInZoomControls = false
                displayZoomControls = false
                setSupportZoom(false)
                loadWithOverviewMode = false
                useWideViewPort = true
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                userAgentString = "$userAgentString VelarisTV/0.1.0"
            }

            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val uri = request.url
                    return if (uri.scheme == "http" || uri.scheme == "https") {
                        false
                    } else {
                        openExternalUri(uri)
                        true
                    }
                }

                override fun onPageFinished(view: WebView, url: String) {
                    super.onPageFinished(view, url)
                    view.requestFocus(View.FOCUS_DOWN)
                    applyImmersiveMode()
                }

                override fun onReceivedError(
                    view: WebView,
                    request: WebResourceRequest,
                    error: WebResourceError
                ) {
                    if (request.isForMainFrame) {
                        showConnectionError(serverUrl, error.description?.toString().orEmpty())
                    }
                }

                override fun onReceivedHttpError(
                    view: WebView,
                    request: WebResourceRequest,
                    errorResponse: WebResourceResponse
                ) {
                    if (request.isForMainFrame && errorResponse.statusCode >= 400) {
                        showConnectionError(serverUrl, "HTTP ${errorResponse.statusCode}")
                    }
                }

                override fun onReceivedSslError(
                    view: WebView,
                    handler: SslErrorHandler,
                    error: SslError
                ) {
                    handler.cancel()
                    showConnectionError(serverUrl, "Das SSL-Zertifikat konnte nicht überprüft werden.")
                }
            }

            webChromeClient = object : WebChromeClient() {
                override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                    if (customView != null) {
                        callback.onCustomViewHidden()
                        return
                    }

                    customView = view
                    customViewCallback = callback
                    this@MainActivity.webView?.visibility = View.GONE
                    webContainer?.addView(
                        view,
                        FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                    )
                    applyImmersiveMode()
                }

                override fun onHideCustomView() {
                    hideCustomView()
                }
            }
        }

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(browser, true)
        }

        if (BuildConfig.DEBUG) {
            WebView.setWebContentsDebuggingEnabled(true)
        }

        webView = browser
        container.addView(
            browser,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        setContentView(container)
        browser.requestFocus(View.FOCUS_DOWN)
        browser.loadUrl(serverUrl)
    }

    private fun showConnectionError(serverUrl: String, details: String) {
        runOnUiThread {
            disposeWebView()

            val root = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(80), dp(48), dp(80), dp(48))
                setBackgroundColor(Color.BLACK)
            }

            val logo = ImageView(this).apply {
                setImageResource(R.drawable.velaris_logo)
                adjustViewBounds = true
            }
            root.addView(logo, LinearLayout.LayoutParams(dp(150), dp(150)))

            val title = TextView(this).apply {
                text = "Velaris ist nicht erreichbar"
                setTextColor(Color.WHITE)
                textSize = 28f
                gravity = Gravity.CENTER
            }
            root.addView(title)

            val message = TextView(this).apply {
                text = buildString {
                    append(serverUrl)
                    if (details.isNotBlank()) {
                        append("\n\n")
                        append(details)
                    }
                }
                setTextColor(Color.rgb(185, 185, 205))
                textSize = 15f
                gravity = Gravity.CENTER
            }
            root.addView(message, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(14)
            })

            val buttonRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
            }

            val retryButton = createTvButton(getString(R.string.retry)).apply {
                setOnClickListener { openVelaris(serverUrl) }
            }
            buttonRow.addView(retryButton, LinearLayout.LayoutParams(dp(240), dp(64)).apply {
                marginEnd = dp(12)
            })

            val changeButton = createTvButton(getString(R.string.change_server)).apply {
                setOnClickListener { showServerSetup(serverUrl) }
            }
            buttonRow.addView(changeButton, LinearLayout.LayoutParams(dp(240), dp(64)).apply {
                marginStart = dp(12)
            })

            root.addView(buttonRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(26)
            })

            setContentView(root)
            retryButton.requestFocus()
        }
    }

    private fun createTvButton(label: String): Button = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 17f
        setTextColor(Color.WHITE)
        isFocusable = true
        isFocusableInTouchMode = true
        backgroundTintList = ColorStateList(
            arrayOf(
                intArrayOf(android.R.attr.state_focused),
                intArrayOf(android.R.attr.state_pressed),
                intArrayOf()
            ),
            intArrayOf(
                Color.rgb(35, 180, 255),
                Color.rgb(124, 92, 255),
                Color.rgb(83, 50, 205)
            )
        )
    }

    private fun normalizeUrl(raw: String): String? {
        var value = raw.trim()
        if (value.isBlank()) return null
        if (!value.startsWith("http://", true) && !value.startsWith("https://", true)) {
            value = "http://$value"
        }

        val uri = runCatching { Uri.parse(value) }.getOrNull() ?: return null
        if ((uri.scheme != "http" && uri.scheme != "https") || uri.host.isNullOrBlank()) {
            return null
        }

        return value.trimEnd('/')
    }

    private fun openExternalUri(uri: Uri) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (_: ActivityNotFoundException) {
            // Ignore unsupported external schemes and keep Velaris running.
        }
    }

    private fun hideCustomView() {
        val currentCustomView = customView ?: return
        webContainer?.removeView(currentCustomView)
        customView = null
        customViewCallback?.onCustomViewHidden()
        customViewCallback = null
        webView?.visibility = View.VISIBLE
        webView?.requestFocus(View.FOCUS_DOWN)
        applyImmersiveMode()
    }

    private fun disposeWebView() {
        if (customView != null) {
            hideCustomView()
        }

        webView?.apply {
            stopLoading()
            loadUrl("about:blank")
            clearHistory()
            (parent as? ViewGroup)?.removeView(this)
            removeAllViews()
            destroy()
        }
        webView = null
        webContainer = null
    }

    private fun applyImmersiveMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.apply {
                hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                    View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                )
        }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val PREFS_NAME = "velaris_tv"
        private const val KEY_SERVER_URL = "server_url"
        private const val LONG_BACK_PRESS_MS = 1_200L
    }
}
