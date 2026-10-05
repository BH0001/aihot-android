package dev.personal.aihotreader

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.net.http.SslError
import android.os.Bundle
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.ContextThemeWrapper
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.view.ViewCompat
import androidx.core.graphics.Insets
import androidx.core.graphics.ColorUtils
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import org.json.JSONTokener
import org.json.JSONObject
import kotlin.math.max

/** A single persistent WebView profile: site-owned bookmarks remain in app storage. */
class MainActivity : ComponentActivity() {
    private lateinit var root: LinearLayout
    private lateinit var content: FrameLayout
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var networkBanner: TextView
    private lateinit var progress: ProgressBar
    private lateinit var errorPanel: LinearLayout
    private lateinit var errorMessage: TextView
    private lateinit var errorTitle: TextView
    private lateinit var retryButton: Button
    private var pageMenu: AlertDialog? = null
    private var nativeThemeDark: Boolean? = null
    private lateinit var nativeContext: Context
    private var webView: WebView? = null
    private var activeMainUrl = NavigationPolicy.HOME_URL
    private var lastTrustedUrl = NavigationPolicy.HOME_URL
    private var failure: PageFailure? = null
    private var pendingScroll: SavedScroll? = null
    private var networkCallbackRegistered = false
    private var defaultNetwork: Network? = null
    private var networkAvailable: Boolean? = null
    private var networkRevision = 0
    private var touchSequence = 0
    private var blankMenuTarget = false
    private val edgePreferences by lazy { getSharedPreferences("reader_edges", MODE_PRIVATE) }
    private val edgeScript by lazy { assets.open("edge-colors.js").bufferedReader().use { it.readText() } }
    private lateinit var backdrop: ReaderBackdrop
    private var colorRequest = 0
    private var colorQueryView: WebView? = null
    private val colorSync = Runnable { syncPageColors() }
    private val colorSyncSettled = Runnable { syncPageColors() }
    private val colorSyncLate = Runnable { syncPageColors() }
    private var observePageDraws = false
    private var drawSyncPending = false
    private var lastDrawSync = 0L
    private val drawColorSync = Runnable {
        drawSyncPending = false
        lastDrawSync = SystemClock.uptimeMillis()
        syncPageColors()
    }
    private val pageDrawListener = ViewTreeObserver.OnDrawListener {
        // Web content can change through keyboard/accessibility actions or delayed scripts.
        // Never mutate views during drawing or postpone forever under a running animation.
        if (observePageDraws && !drawSyncPending) {
            drawSyncPending = true
            root.postDelayed(drawColorSync, max(0L, 300L - (SystemClock.uptimeMillis() - lastDrawSync)))
        }
    }

    private val connectivity by lazy { getSystemService(ConnectivityManager::class.java) }
    private val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            val view = webView
            if (view != null && view.canGoBack()) {
                clearFailure()
                pendingScroll = null
                view.goBack()
                view.post { updateBackState() }
            } else {
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
            }
        }
    }
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            if (!networkCallbackRegistered) return
            networkRevision++
            defaultNetwork = network
            // onCapabilitiesChanged follows onAvailable; do not query stale synchronous state.
        }

        override fun onLost(network: Network) {
            if (!networkCallbackRegistered || (defaultNetwork != null && defaultNetwork != network)) return
            defaultNetwork = null
            val revision = ++networkRevision
            applyNetworkState(false)
            // Two bounded checks cover handoffs whose replacement callback arrives late.
            for (delay in longArrayOf(350L, 1_200L)) {
                root.postDelayed({
                    if (!isDestroyed && networkCallbackRegistered && revision == networkRevision) {
                        val replacement = connectivity.activeNetwork
                        if (replacement != network) refreshNetworkSnapshot()
                    }
                }, delay)
            }
        }

        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            if (!networkCallbackRegistered || network != defaultNetwork) return
            networkRevision++
            applyNetworkState(caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (Build.VERSION.SDK_INT >= 28) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = if (Build.VERSION.SDK_INT >= 30)
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                else WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        if (Build.VERSION.SDK_INT >= 29) {
            window.isStatusBarContrastEnforced = false
            window.isNavigationBarContrastEnforced = false
        }
        buildLayout()
        onBackPressedDispatcher.addCallback(this, backCallback)
        val view = createWebView()
        val savedUrl = NavigationPolicy.shareableUrl(savedInstanceState?.getString(STATE_URL))
        activeMainUrl = savedUrl ?: NavigationPolicy.HOME_URL
        lastTrustedUrl = activeMainUrl
        if (savedInstanceState != null) {
            pendingScroll = SavedScroll(
                activeMainUrl,
                savedInstanceState.getInt(STATE_SCROLL_X),
                savedInstanceState.getInt(STATE_SCROLL_Y),
            )
        }
        val history = savedInstanceState?.getBundle(STATE_WEBVIEW)?.let(view::restoreState)
        if (history == null || history.size == 0) {
            loadInternal(activeMainUrl, keepPendingScroll = true)
        } else {
            updateCurrentUrl(view.url)
            updateBackState()
        }
        updateNetworkBanner()
    }

    private fun buildLayout() {
        val defaultPage = getColor(R.color.page_background)
        backdrop = ReaderBackdrop(
            edgePreferences.getInt("page", defaultPage),
            edgePreferences.getInt("top", defaultPage),
            edgePreferences.getInt("bottom", getColor(R.color.surface)),
        )
        root = LinearLayout(this).apply {
            id = R.id.reader_root
            orientation = LinearLayout.VERTICAL
            background = backdrop
        }
        networkBanner = TextView(this).apply {
            id = R.id.network_banner
            setText(R.string.offline_banner)
            textSize = 12f
            setTextColor(getColor(R.color.banner_text))
            setBackgroundColor(getColor(R.color.banner_background))
            setPadding(dp(16), dp(8), dp(16), dp(8))
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            visibility = View.GONE
        }
        root.addView(networkBanner, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            id = R.id.loading_progress
            max = 100
            progressTintList = ColorStateList.valueOf(getColor(R.color.accent))
            contentDescription = getString(R.string.loading)
            visibility = View.INVISIBLE
        }
        content = FrameLayout(this)
        root.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        swipeRefresh = SwipeRefreshLayout(this).apply {
            id = R.id.reader_refresh
            setColorSchemeColors(getColor(R.color.accent))
            setProgressBackgroundColorSchemeColor(getColor(R.color.surface))
            setOnChildScrollUpCallback { _, _ ->
                val reader = webView
                reader == null || reader.scrollY > 0 || reader.canScrollVertically(-1)
            }
            setOnRefreshListener { refreshPage() }
        }
        content.addView(swipeRefresh, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        errorPanel = LinearLayout(this).apply {
            id = R.id.error_panel
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(28), dp(20), dp(28), dp(20))
            setBackgroundColor(getColor(R.color.surface))
            visibility = View.GONE
        }
        errorTitle = TextView(this).apply {
            setText(R.string.error_title)
            textSize = 22f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(getColor(R.color.text_primary))
            gravity = Gravity.CENTER
        }
        errorPanel.addView(errorTitle)
        errorMessage = TextView(this).apply {
            id = R.id.error_message
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(getColor(R.color.text_secondary))
            setPadding(0, dp(14), 0, dp(22))
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        errorPanel.addView(errorMessage, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        retryButton = Button(this).apply {
            id = R.id.action_retry
            setText(R.string.retry)
            isAllCaps = false
            minWidth = dp(128)
            minHeight = dp(48)
            setOnClickListener { loadInternal(failure?.url ?: activeMainUrl) }
        }
        errorPanel.addView(retryButton)
        content.addView(errorPanel, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        content.addView(progress, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(3), Gravity.TOP))
        setContentView(root)

        applyEdgeColors()
        // Android 8.0 cannot draw dark navigation buttons on a light background.
        if (Build.VERSION.SDK_INT == Build.VERSION_CODES.O) {
            @Suppress("DEPRECATION")
            window.navigationBarColor = getColor(android.R.color.black)
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() or
                WindowInsetsCompat.Type.displayCutout())
            val keyboard = insets.getInsets(WindowInsetsCompat.Type.ime())
            view.updatePadding(left = safe.left, top = safe.top, right = safe.right,
                bottom = max(safe.bottom, keyboard.bottom))
            backdrop.topInset = safe.top
            backdrop.bottomInset = max(safe.bottom, keyboard.bottom)
            backdrop.invalidateSelf()
            // Notify WebView of zero handled insets, including when the keyboard closes.
            WindowInsetsCompat.Builder(insets)
                .setInsets(WindowInsetsCompat.Type.systemBars() or
                    WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.ime(), Insets.NONE)
                .build()
        }
        ViewCompat.requestApplyInsets(root)
    }

    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    @Suppress("DEPRECATION")
    private fun createWebView(): WebView {
        val view = WebView(this).apply {
            id = R.id.reader_webview
            // State is saved explicitly, once, in onSaveInstanceState.
            isSaveEnabled = false
            setBackgroundColor(backdrop.pageColor)
            // Observe only: WebView retains click, scrolling, and accessibility handling.
            setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        pendingScroll = null
                        blankMenuTarget = false
                        inspectMenuTouchTarget(this, event.x, event.y, ++touchSequence)
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        blankMenuTarget = false
                        touchSequence++
                        scheduleColorSync()
                    }
                }
                false
            }
            setOnLongClickListener {
                val type = hitTestResult?.type
                val allowed = blankMenuTarget && (type == WebView.HitTestResult.IMAGE_TYPE ||
                    type == WebView.HitTestResult.UNKNOWN_TYPE)
                if (allowed && failure == null) {
                    showPageMenu()
                    true
                } else {
                    false
                }
            }
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                allowFileAccess = false
                allowContentAccess = false
                allowFileAccessFromFileURLs = false
                allowUniversalAccessFromFileURLs = false
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                javaScriptCanOpenWindowsAutomatically = false
                // _blank / user-initiated window.open follow the same top-level navigation policy.
                setSupportMultipleWindows(false)
                useWideViewPort = true
                loadWithOverviewMode = true
                builtInZoomControls = true
                displayZoomControls = false
                cacheMode = WebSettings.LOAD_DEFAULT
                mediaPlaybackRequiresUserGesture = true
                setGeolocationEnabled(false)
            }
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
            webViewClient = ReaderClient()
            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView, newProgress: Int) {
                    if (view !== webView || failure != null) return
                    this@MainActivity.progress.progress = newProgress
                    this@MainActivity.progress.visibility = if (newProgress < 100) View.VISIBLE else View.INVISIBLE
                    if (newProgress == 100) swipeRefresh.isRefreshing = false
                }
            }
        }
        webView = view
        swipeRefresh.addView(view, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        ViewCompat.addAccessibilityAction(view, getString(R.string.home)) { _, _ ->
            loadInternal(NavigationPolicy.HOME_URL)
            true
        }
        ViewCompat.addAccessibilityAction(view, getString(R.string.refresh)) { _, _ ->
            refreshPage()
            true
        }
        ViewCompat.addAccessibilityAction(view, getString(R.string.share)) { _, _ ->
            shareCurrentPage()
            true
        }
        return view
    }

    private fun inspectMenuTouchTarget(view: WebView, x: Float, y: Float, sequence: Int) {
        if (view.width <= 0 || view.height <= 0 || failure != null) return
        val fractionX = x.toDouble() / view.width
        val fractionY = y.toDouble() / view.height
        val documentUrl = view.url
        // UNKNOWN_TYPE includes text: inspect character rectangles before claiming a long press.
        // This read-only query runs on touch-down, leaving native text/link handling synchronous.
        val script = """
            (function() {
                const x = innerWidth * $fractionX, y = innerHeight * $fractionY;
                const element = document.elementFromPoint(x, y);
                if (!element || element.closest('a,button,input,textarea,select,video,audio,[contenteditable],[role="button"]')) return false;
                const selection = window.getSelection();
                if (selection && !selection.isCollapsed) return false;
                if (element.tagName === 'IMG') return true;
                const caret = document.caretRangeFromPoint ? document.caretRangeFromPoint(x, y) : null;
                if (!caret || caret.startContainer.nodeType !== Node.TEXT_NODE) {
                    return (element.textContent || '').trim().length === 0;
                }
                const node = caret.startContainer, offset = caret.startOffset;
                for (const index of [offset - 1, offset]) {
                    if (index < 0 || index >= node.length) continue;
                    const character = document.createRange();
                    character.setStart(node, index);
                    character.setEnd(node, index + 1);
                    for (const rect of character.getClientRects()) {
                        if (x >= rect.left - 2 && x <= rect.right + 2 && y >= rect.top - 2 && y <= rect.bottom + 2) return false;
                    }
                }
                return true;
            })()
        """.trimIndent()
        view.evaluateJavascript(script) { result ->
            if (view === webView && sequence == touchSequence && sameDocument(view.url, documentUrl)) {
                blankMenuTarget = result == "true"
            }
        }
    }

    private inner class ReaderClient : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            if (view !== webView) return true
            if (!request.isForMainFrame) return false
            return routeNavigation(request.url.toString())
        }

        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
            if (view !== webView || url == null) return
            if (NavigationPolicy.classify(url) != NavigationPolicy.Destination.INTERNAL) {
                view.stopLoading()
                swipeRefresh.isRefreshing = false
                routeNavigation(url)
                return
            }
            if (pendingScroll?.let { !sameDocument(it.url, url) } == true) pendingScroll = null
            activeMainUrl = url
            clearFailure()
            progress.progress = 0
            progress.visibility = View.VISIBLE
            updateCurrentUrl(url)
            updateBackState()
        }

        override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
            if (view !== webView) return
            if (pendingScroll?.let { !sameDocument(it.url, url) } == true) pendingScroll = null
            // This also covers History API route changes without onPageStarted/onPageFinished.
            updateCurrentUrl(url)
            if (failure == null && NavigationPolicy.shareableUrl(url) != null) activeMainUrl = url!!
            updateBackState()
            scheduleColorSync()
        }

        override fun onPageCommitVisible(view: WebView, url: String?) {
            if (view !== webView || !isActiveUrl(url) || failure != null) return
            errorPanel.visibility = View.GONE
            updateCurrentUrl(url)
            scheduleColorSync()
        }

        override fun onPageFinished(view: WebView, url: String?) {
            if (view !== webView || !isActiveUrl(url)) return
            updateBackState()
            // A failed navigation can still emit onPageFinished; never hide its retry screen.
            if (failure != null) return
            progress.visibility = View.INVISIBLE
            swipeRefresh.isRefreshing = false
            updateCurrentUrl(view.url)
            scheduleColorSync()
            val scroll = pendingScroll
            if (scroll != null && sameDocument(scroll.url, url)) {
                restoreScrollWhenReady(view, scroll)
            } else {
                pendingScroll = null
            }
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (view !== webView || !request.isForMainFrame || !isActiveUrl(request.url.toString())) return
            val message = when {
                error.errorCode == ERROR_FAILED_SSL_HANDSHAKE -> getString(R.string.error_tls)
                !isNetworkAvailable() -> getString(R.string.error_offline)
                else -> getString(R.string.error_connection)
            }
            showFailure(request.url.toString(), message)
        }

        override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
            if (view !== webView || !request.isForMainFrame || !isActiveUrl(request.url.toString())) return
            showFailure(request.url.toString(), getString(R.string.error_server, response.statusCode))
        }

        override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
            handler.cancel()
            // This callback has no isForMainFrame; only the current document gets an error panel.
            if (view === webView && isActiveUrl(error.url)) {
                showFailure(error.url, getString(R.string.error_tls))
            }
        }

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            if (view === webView) {
                colorQueryView = null
                webView = null
                swipeRefresh.removeView(view)
                view.destroy()
                showFailure(activeMainUrl, getString(R.string.error_renderer))
                updateBackState()
            }
            return true
        }
    }

    private fun routeNavigation(url: String): Boolean = when (NavigationPolicy.classify(url)) {
        NavigationPolicy.Destination.INTERNAL -> false
        NavigationPolicy.Destination.EXTERNAL -> {
            swipeRefresh.isRefreshing = false
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE))
            } catch (_: ActivityNotFoundException) {
                toast(R.string.no_browser)
            } catch (_: SecurityException) {
                toast(R.string.no_browser)
            }
            true
        }
        NavigationPolicy.Destination.BLOCKED -> {
            swipeRefresh.isRefreshing = false
            toast(R.string.blocked_link)
            true
        }
    }

    private fun loadInternal(url: String, keepPendingScroll: Boolean = false) {
        val destination = NavigationPolicy.shareableUrl(url) ?: NavigationPolicy.HOME_URL
        if (!keepPendingScroll) pendingScroll = null
        clearFailure()
        activeMainUrl = destination
        val view = webView ?: createWebView()
        progress.visibility = View.VISIBLE
        view.loadUrl(destination)
    }

    private fun refreshPage() {
        failure?.let {
            loadInternal(it.url)
            return
        }
        val view = webView
        if (view == null) {
            loadInternal(activeMainUrl)
        } else if (!isNetworkAvailable()) {
            // Do not replace readable content with a failed refresh while offline.
            updateNetworkBanner()
            swipeRefresh.isRefreshing = false
            toast(R.string.error_offline)
        } else {
            activeMainUrl = NavigationPolicy.shareableUrl(view.url) ?: lastTrustedUrl
            pendingScroll = SavedScroll(activeMainUrl, view.scrollX, view.scrollY)
            clearFailure()
            view.reload()
        }
    }

    private fun shareCurrentPage() {
        val view = webView
        if (view == null || failure != null || NavigationPolicy.shareableUrl(view.url) == null) {
            toast(R.string.nothing_to_share)
            return
        }
        // Read the live location so even an immediately preceding SPA route change is reflected.
        // evaluateJavascript exposes no native object or callback to website scripts.
        view.evaluateJavascript("window.location.href") { value ->
            if (isDestroyed || view !== webView || failure != null) return@evaluateJavascript
            val location = runCatching { JSONTokener(value).nextValue() as? String }.getOrNull()
            val url = NavigationPolicy.shareableUrl(location)
                ?: NavigationPolicy.shareableUrl(view.url)
            if (url == null) {
                toast(R.string.nothing_to_share)
                return@evaluateJavascript
            }
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, url)
            }
            try {
                startActivity(Intent.createChooser(intent, getString(R.string.share_chooser)))
            } catch (_: ActivityNotFoundException) {
                toast(R.string.no_share_app)
            }
        }
    }

    private fun showFailure(url: String, message: String) {
        val safeUrl = NavigationPolicy.shareableUrl(url) ?: lastTrustedUrl
        failure = PageFailure(safeUrl, message)
        errorMessage.text = message
        errorPanel.visibility = View.VISIBLE
        progress.visibility = View.INVISIBLE
        swipeRefresh.isRefreshing = false
        swipeRefresh.isEnabled = false
        applyEdgeColors()
        updateBackState()
    }

    private fun clearFailure() {
        failure = null
        errorPanel.visibility = View.GONE
        swipeRefresh.isEnabled = true
        applyEdgeColors()
    }

    private fun updateCurrentUrl(url: String?) {
        NavigationPolicy.shareableUrl(url)?.let { lastTrustedUrl = it }
    }

    @Suppress("DEPRECATION")
    private fun restoreScrollWhenReady(view: WebView, scroll: SavedScroll) {
        val deadline = SystemClock.uptimeMillis() + 5_000L
        var attempts = 0
        fun isCurrent(): Boolean = !isDestroyed && view === webView &&
            pendingScroll === scroll && sameDocument(view.url, scroll.url) && failure == null

        fun attempt() {
            if (!isCurrent()) return
            attempts++
            view.postVisualStateCallback(attempts.toLong(), object : WebView.VisualStateCallback() {
                override fun onComplete(requestId: Long) {
                    if (!isCurrent()) return
                    val availableScroll = (view.contentHeight * view.scale - view.height)
                        .coerceAtLeast(0f)
                    if ((view.height > 0 && availableScroll >= scroll.y) || attempts >= 50 ||
                        SystemClock.uptimeMillis() >= deadline
                    ) {
                        // WebView clamps the final attempt if the new document is shorter.
                        view.scrollTo(scroll.x, scroll.y)
                        pendingScroll = null
                    } else {
                        view.postDelayed({ attempt() }, 100L)
                    }
                }
            })
        }
        attempt()
    }

    private fun isActiveUrl(url: String?): Boolean = sameDocument(activeMainUrl, url)

    private fun sameDocument(first: String?, second: String?): Boolean {
        if (first == null || second == null) return false
        return Uri.parse(first).buildUpon().fragment(null).build() ==
            Uri.parse(second).buildUpon().fragment(null).build()
    }

    private fun updateBackState() {
        backCallback.isEnabled = webView?.canGoBack() == true
    }

    private fun isNetworkAvailable(): Boolean {
        networkAvailable?.let { return it }
        val network = connectivity.activeNetwork ?: return false
        return connectivity.getNetworkCapabilities(network)
            ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    }

    private fun refreshNetworkSnapshot() {
        defaultNetwork = connectivity.activeNetwork
        val available = defaultNetwork?.let { connectivity.getNetworkCapabilities(it) }
            ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        applyNetworkState(available)
    }

    private fun applyNetworkState(available: Boolean) {
        networkAvailable = available
        if (!available) swipeRefresh.isRefreshing = false
        updateNetworkBanner()
    }

    private fun updateNetworkBanner() {
        networkBanner.visibility = if (isNetworkAvailable()) View.GONE else View.VISIBLE
        applyEdgeColors()
        // Network changes never reload the WebView or cover its current article.
    }

    override fun onStart() {
        super.onStart()
        refreshNetworkSnapshot()
        if (!networkCallbackRegistered) {
            connectivity.registerDefaultNetworkCallback(networkCallback, Handler(Looper.getMainLooper()))
            networkCallbackRegistered = true
        }
        updateNetworkBanner()
    }

    override fun onResume() {
        super.onResume()
        webView?.onResume()
        observePageDraws = true
        root.viewTreeObserver.also {
            it.removeOnDrawListener(pageDrawListener)
            it.addOnDrawListener(pageDrawListener)
        }
        updateBackState()
        scheduleColorSync()
    }

    override fun onPause() {
        observePageDraws = false
        root.viewTreeObserver.takeIf { it.isAlive }?.removeOnDrawListener(pageDrawListener)
        cancelColorSync()
        webView?.onPause()
        CookieManager.getInstance().flush()
        super.onPause()
    }

    override fun onStop() {
        if (networkCallbackRegistered) {
            connectivity.unregisterNetworkCallback(networkCallback)
            networkCallbackRegistered = false
            networkRevision++
        }
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        webView?.let { view ->
            outState.putBundle(STATE_WEBVIEW, Bundle().also { view.saveState(it) })
            outState.putInt(STATE_SCROLL_X, view.scrollX)
            outState.putInt(STATE_SCROLL_Y, view.scrollY)
        }
        outState.putString(STATE_URL, failure?.url ?: NavigationPolicy.shareableUrl(webView?.url)
            ?: lastTrustedUrl)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        colorQueryView = null
        observePageDraws = false
        root.viewTreeObserver.takeIf { it.isAlive }?.removeOnDrawListener(pageDrawListener)
        cancelColorSync()
        pageMenu?.dismiss()
        pageMenu = null
        webView?.let { view ->
            webView = null
            swipeRefresh.removeView(view)
            view.stopLoading()
            view.destroy()
        }
        super.onDestroy()
    }

    private fun toast(message: Int) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    private fun cancelColorSync() {
        root.removeCallbacks(colorSync)
        root.removeCallbacks(colorSyncSettled)
        root.removeCallbacks(colorSyncLate)
        root.removeCallbacks(drawColorSync)
        drawSyncPending = false
        colorRequest++
    }

    private fun scheduleColorSync() {
        if (isDestroyed) return
        cancelColorSync()
        root.post(colorSync)
        root.postDelayed(colorSyncSettled, 350)
        root.postDelayed(colorSyncLate, 900)
    }

    private fun syncPageColors() {
        val view = webView ?: return
        if (failure != null || colorQueryView === view || NavigationPolicy.shareableUrl(view.url) == null) return
        val request = ++colorRequest
        colorQueryView = view
        view.evaluateJavascript(edgeScript) { value ->
            if (colorQueryView === view) colorQueryView = null
            if (isDestroyed || request != colorRequest || view !== webView || failure != null) return@evaluateJavascript
            val data = runCatching { JSONTokener(value).nextValue() as? JSONObject }.getOrNull()
                ?: return@evaluateJavascript
            if (data.optString("url") != view.url) return@evaluateJavascript
            val colors = runCatching { listOf("page", "top", "bottom").map {
                Color.parseColor(data.getString(it))
            } }.getOrNull() ?: return@evaluateJavascript
            if (colors.any { Color.alpha(it) != 255 }) return@evaluateJavascript
            if (colors[0] == backdrop.pageColor && colors[1] == backdrop.webTopColor &&
                colors[2] == backdrop.webBottomColor) return@evaluateJavascript
            edgePreferences.edit().putInt("page", colors[0]).putInt("top", colors[1])
                .putInt("bottom", colors[2]).apply()
            backdrop.pageColor = colors[0]
            backdrop.webTopColor = colors[1]
            backdrop.webBottomColor = colors[2]
            view.setBackgroundColor(colors[0])
            applyEdgeColors()
        }
    }

    private fun applyEdgeColors() {
        if (!::backdrop.isInitialized || !::errorPanel.isInitialized) return
        updateNativeTheme()
        val error = failure != null
        backdrop.topColor = when {
            networkBanner.visibility == View.VISIBLE -> nativeContext.getColor(R.color.banner_background)
            error -> nativeContext.getColor(R.color.surface)
            else -> backdrop.webTopColor
        }
        backdrop.bottomColor = if (error) nativeContext.getColor(R.color.surface) else backdrop.webBottomColor
        backdrop.sideColor = if (error) nativeContext.getColor(R.color.surface) else backdrop.pageColor
        backdrop.invalidateSelf()
        WindowCompat.getInsetsController(window, root).apply {
            isAppearanceLightStatusBars = ColorUtils.calculateLuminance(backdrop.topColor) > 0.45
            isAppearanceLightNavigationBars = ColorUtils.calculateLuminance(backdrop.bottomColor) > 0.45
        }
        swipeRefresh.setProgressBackgroundColorSchemeColor(backdrop.pageColor)
    }

    private fun updateNativeTheme() {
        val dark = ColorUtils.calculateLuminance(backdrop.pageColor) < 0.45
        if (nativeThemeDark == dark) return
        nativeThemeDark = dark
        val config = Configuration(resources.configuration).apply {
            uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                (if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO)
        }
        nativeContext = createConfigurationContext(config)
        errorPanel.setBackgroundColor(nativeContext.getColor(R.color.surface))
        errorTitle.setTextColor(nativeContext.getColor(R.color.text_primary))
        errorMessage.setTextColor(nativeContext.getColor(R.color.text_secondary))
        networkBanner.setBackgroundColor(nativeContext.getColor(R.color.banner_background))
        networkBanner.setTextColor(nativeContext.getColor(R.color.banner_text))
        val accent = nativeContext.getColor(R.color.accent)
        retryButton.backgroundTintList = ColorStateList.valueOf(accent)
        retryButton.setTextColor(nativeContext.getColor(R.color.page_background))
        progress.progressTintList = ColorStateList.valueOf(accent)
        swipeRefresh.setColorSchemeColors(accent)
    }

    private fun showPageMenu() {
        updateNativeTheme()
        val theme = if (nativeThemeDark == true) android.R.style.Theme_Material_Dialog_Alert
            else android.R.style.Theme_Material_Light_Dialog_Alert
        pageMenu?.dismiss()
        // Keep the Activity as the base context so the dialog has a valid window token.
        val context = ContextThemeWrapper(this, theme).apply {
            applyOverrideConfiguration(nativeContext.resources.configuration)
        }
        pageMenu = AlertDialog.Builder(context)
            .setTitle(R.string.app_name)
            .setItems(arrayOf(getString(R.string.home), getString(R.string.refresh), getString(R.string.share))) { _, which ->
                when (which) {
                    0 -> loadInternal(NavigationPolicy.HOME_URL)
                    1 -> refreshPage()
                    2 -> shareCurrentPage()
                }
            }.create().also { dialog ->
                dialog.setOnDismissListener { if (pageMenu === dialog) pageMenu = null }
                dialog.show()
            }
    }
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()
    private data class PageFailure(val url: String, val message: String)
    private data class SavedScroll(val url: String, val x: Int, val y: Int)

    private companion object {
        const val STATE_WEBVIEW = "reader.webview"
        const val STATE_URL = "reader.url"
        const val STATE_SCROLL_X = "reader.scrollX"
        const val STATE_SCROLL_Y = "reader.scrollY"
    }
}
