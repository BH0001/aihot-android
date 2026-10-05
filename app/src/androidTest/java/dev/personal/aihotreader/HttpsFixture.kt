package dev.personal.aihotreader

import android.graphics.Bitmap
import android.net.http.SslError
import android.os.Message
import android.view.KeyEvent
import android.webkit.ClientCertRequest
import android.webkit.HttpAuthHandler
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.ByteArrayInputStream
import java.util.concurrent.ConcurrentHashMap

/** Test-only HTTPS responses; real loadUrl/history behavior and the production client stay intact. */
internal object HttpsFixture {
    /** Call on the UI thread. Installs a response without starting or stopping a navigation. */
    fun install(web: WebView, url: String, html: String) {
        require(NavigationPolicy.classify(url) == NavigationPolicy.Destination.INTERNAL)
        val client = web.webViewClient as? FixtureClient
            ?: FixtureClient(web.webViewClient).also { web.webViewClient = it }
        client.pages[url] = html.toByteArray(Charsets.UTF_8)
    }

    fun describe(web: WebView): String {
        val history = web.copyBackForwardList()
        val urls = (0 until history.size).joinToString { history.getItemAtIndex(it).url }
        return "WebView=${WebView.getCurrentWebViewPackage()?.versionName}, url=${web.url}, " +
            "canGoBack=${web.canGoBack()}, historyIndex=${history.currentIndex}, history=[$urls]"
    }

    @Suppress("DEPRECATION")
    private class FixtureClient(val production: WebViewClient) : WebViewClient() {
        val pages = ConcurrentHashMap<String, ByteArray>()

        private fun response(url: String): WebResourceResponse? = pages[url]?.let {
            WebResourceResponse("text/html", "UTF-8", 200, "OK",
                mapOf("Cache-Control" to "no-store"), ByteArrayInputStream(it))
        }

        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
            (if (request.method == "GET") response(request.url.toString()) else null)
                ?: production.shouldInterceptRequest(view, request)

        override fun shouldInterceptRequest(view: WebView, url: String): WebResourceResponse? =
            response(url) ?: production.shouldInterceptRequest(view, url)

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
            production.shouldOverrideUrlLoading(view, request)

        override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean =
            production.shouldOverrideUrlLoading(view, url)

        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) =
            production.onPageStarted(view, url, favicon)

        override fun onPageFinished(view: WebView, url: String?) = production.onPageFinished(view, url)

        override fun onPageCommitVisible(view: WebView, url: String?) =
            production.onPageCommitVisible(view, url)

        override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) =
            production.doUpdateVisitedHistory(view, url, isReload)

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) =
            production.onReceivedError(view, request, error)

        override fun onReceivedError(view: WebView, errorCode: Int, description: String?, failingUrl: String?) =
            production.onReceivedError(view, errorCode, description, failingUrl)

        override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) =
            production.onReceivedHttpError(view, request, response)

        override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) =
            production.onReceivedSslError(view, handler, error)

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean =
            production.onRenderProcessGone(view, detail)

        override fun onLoadResource(view: WebView, url: String?) = production.onLoadResource(view, url)

        override fun onScaleChanged(view: WebView, oldScale: Float, newScale: Float) =
            production.onScaleChanged(view, oldScale, newScale)

        override fun onFormResubmission(view: WebView, dontResend: Message, resend: Message) =
            production.onFormResubmission(view, dontResend, resend)

        override fun onReceivedHttpAuthRequest(view: WebView, handler: HttpAuthHandler, host: String, realm: String?) =
            production.onReceivedHttpAuthRequest(view, handler, host, realm)

        override fun onReceivedClientCertRequest(view: WebView, request: ClientCertRequest) =
            production.onReceivedClientCertRequest(view, request)

        override fun shouldOverrideKeyEvent(view: WebView, event: KeyEvent): Boolean =
            production.shouldOverrideKeyEvent(view, event)

        override fun onUnhandledKeyEvent(view: WebView, event: KeyEvent) =
            production.onUnhandledKeyEvent(view, event)

        override fun onReceivedLoginRequest(view: WebView, realm: String, account: String?, args: String) =
            production.onReceivedLoginRequest(view, realm, account, args)

    }
}
