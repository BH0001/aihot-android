package dev.personal.aihotreader

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.net.Uri
import android.view.View
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.GeneralClickAction
import androidx.test.espresso.action.Press
import androidx.test.espresso.action.Tap
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.swipeDown
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import androidx.test.espresso.intent.matcher.IntentMatchers.hasExtra
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.hamcrest.Matchers.allOf
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONArray

/** Device tests use a tiny same-origin fixture, keeping checks independent of changing news. */
@RunWith(AndroidJUnit4::class)
class ReaderInstrumentedTest {
    private val fixtureUrl = "https://aihot.news/__android_reader_test__"

    private fun js(scenario: ActivityScenario<MainActivity>, expression: String): String {
        val answer = AtomicReference<String>()
        val completed = CountDownLatch(1)
        scenario.onActivity { activity ->
            activity.findViewById<WebView>(R.id.reader_webview).evaluateJavascript(expression) {
                answer.set(it)
                completed.countDown()
            }
        }
        check(completed.await(10, TimeUnit.SECONDS)) { "JavaScript did not respond" }
        return answer.get()
    }

    private fun fixture(scenario: ActivityScenario<MainActivity>) {
        val readyToken = System.nanoTime().toString()
        scenario.onActivity { activity ->
            activity.findViewById<WebView>(R.id.reader_webview).apply {
                stopLoading()
                HttpsFixture.install(this, fixtureUrl,
                    """<!doctype html><meta name="viewport" content="width=device-width,initial-scale=1">
                       <title>阅读器验证</title><h1>阅读器验证</h1><p>测试正文</p>
                       <button id="article" onclick="history.pushState({}, '', '/items/test-current-article'); document.title='当前文章';">打开文章</button>
                       <a id="external" href="https://example.com/article" target="_blank">外部原文</a>
                       <script>window.fixtureReady='$readyToken';</script>""".trimIndent()
                )
                loadUrl(fixtureUrl)
            }
        }
        await("HTTPS fixture did not load", scenario) {
            js(scenario, "window.fixtureReady === '$readyToken'") == "true"
        }
    }

    private fun await(message: String, scenario: ActivityScenario<MainActivity>, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        while (System.nanoTime() < deadline) {
            if (condition()) return
            Thread.sleep(100)
        }
        var state = "Activity unavailable"
        scenario.onActivity { state = HttpsFixture.describe(it.findViewById(R.id.reader_webview)) }
        val documentState = js(scenario, "JSON.stringify({url:location.href,historyLength:history.length,ready:document.readyState})")
        assertTrue("$message; $state; document=$documentState", condition())
    }

    @Test fun requiredWebSettingsAndSubresourceFailurePreserveReading() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            fixture(scenario)
            scenario.onActivity { activity ->
                val web = activity.findViewById<WebView>(R.id.reader_webview)
                assertTrue(web.settings.javaScriptEnabled)
                assertTrue(web.settings.domStorageEnabled)
                assertFalse(web.settings.allowFileAccess)
                assertFalse(web.settings.allowContentAccess)
                assertFalse(web.settings.supportMultipleWindows())
                assertFalse(web.settings.javaScriptCanOpenWindowsAutomatically)
                assertEquals(android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW, web.settings.mixedContentMode)
                val imageRequest = object : WebResourceRequest {
                    override fun getUrl() = Uri.parse("https://aihot.news/missing.png")
                    override fun isForMainFrame() = false
                    override fun isRedirect() = false
                    override fun hasGesture() = false
                    override fun getMethod() = "GET"
                    override fun getRequestHeaders() = emptyMap<String, String>()
                }
                web.webViewClient.onReceivedHttpError(web, imageRequest,
                    WebResourceResponse("image/png", "UTF-8", 404, "Not Found", emptyMap(), null))
                assertNotEquals(View.VISIBLE, activity.findViewById<View>(R.id.error_panel).visibility)
            }
            assertTrue(js(scenario, "document.body.innerText").contains("测试正文"))
        }
    }

    @Test fun singlePageNavigationSharesCurrentArticleAndReturns() {
        Intents.init()
        try {
            Intents.intending(hasAction(Intent.ACTION_CHOOSER))
                .respondWith(Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null))
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                fixture(scenario)
                val article = "https://aihot.news/items/test-current-article"
                // A native touch gives the document user activation. Chromium deliberately skips
                // history entries inserted without interaction when handling the system Back UI.
                tapFixture(scenario, "article")
                await("SPA document URL did not change", scenario) { js(scenario, "location.href") == "\"$article\"" }
                await("SPA native history did not update", scenario) {
                    var ready = false
                    scenario.onActivity {
                        val web = it.findViewById<WebView>(R.id.reader_webview)
                        ready = web.url == article && web.canGoBack()
                    }
                    ready
                }
                onView(withId(R.id.reader_webview)).perform(GeneralClickAction(Tap.LONG, { view ->
                    val origin = IntArray(2)
                    view.getLocationOnScreen(origin)
                    floatArrayOf(origin[0] + view.width * 0.5f, origin[1] + view.height * 0.8f)
                }, Press.FINGER))
                onView(withText(R.string.share)).perform(click())
                await("Share chooser was not sent", scenario) {
                    Intents.getIntents().any { it.action == Intent.ACTION_CHOOSER }
                }
                Intents.intended(allOf(
                    hasAction(Intent.ACTION_CHOOSER),
                    hasExtra(Intent.EXTRA_INTENT, allOf(
                        hasAction(Intent.ACTION_SEND),
                        hasExtra(Intent.EXTRA_TEXT, org.hamcrest.Matchers.containsString(article))
                    ))
                ))
                scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
                await("System back did not return to the fixture", scenario) {
                    js(scenario, "location.href") == "\"$fixtureUrl\""
                }
            }
        } finally { Intents.release() }
    }

    @Test fun externalPopupOpensBrowserAndKeepsReaderPage() {
        Intents.init()
        try {
            Intents.intending(hasAction(Intent.ACTION_VIEW))
                .respondWith(Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null))
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                fixture(scenario)
                tapFixture(scenario, "external")
                await("External popup did not send a browser intent", scenario) {
                    Intents.getIntents().any { it.action == Intent.ACTION_VIEW && it.dataString == "https://example.com/article" }
                }
                assertEquals("\"$fixtureUrl\"", js(scenario, "location.href"))
            }
        } finally { Intents.release() }
    }

    @Test fun localStorageSurvivesActivityRecreation() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            fixture(scenario)
            try {
                js(scenario, "localStorage.setItem('__aihot_reader_test__','retained');")
                scenario.recreate()
                fixture(scenario)
                assertEquals("\"retained\"", js(scenario, "localStorage.getItem('__aihot_reader_test__')"))
            } finally { js(scenario, "localStorage.removeItem('__aihot_reader_test__');") }
        }
    }

    @Test fun pullToRefreshReloadsWithoutAddingAHeader() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            fixture(scenario)
            js(scenario, "window.beforeRefresh=true;")
            onView(withId(R.id.reader_refresh)).perform(swipeDown())
            await("Pull-to-refresh did not reload the page", scenario) {
                js(scenario, "typeof window.beforeRefresh === 'undefined' && !!window.fixtureReady") == "true"
            }
            scenario.onActivity { activity ->
                val web = activity.findViewById<WebView>(R.id.reader_webview)
                val container = activity.findViewById<View>(R.id.reader_refresh)
                assertEquals("No native toolbar should consume reading height", container.height, web.height)
            }
        }
    }

    @Test fun longPressTextKeepsNativeSelection() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            fixture(scenario)
            val point = JSONArray(js(scenario, """(function(){
                var range=document.createRange();range.selectNodeContents(document.querySelector('h1'));
                var r=range.getBoundingClientRect();
                return [(r.left+r.width/2)/innerWidth,(r.top+r.height/2)/innerHeight];
            })()""".trimIndent()))
            onView(withId(R.id.reader_webview)).perform(GeneralClickAction(Tap.LONG, { view ->
                val origin = IntArray(2)
                view.getLocationOnScreen(origin)
                floatArrayOf(origin[0] + (point.getDouble(0) * view.width).toFloat(),
                    origin[1] + (point.getDouble(1) * view.height).toFloat())
            }, Press.FINGER))
            await("Text long-press was intercepted by the page menu", scenario) {
                js(scenario, "window.getSelection().toString().length > 0") == "true"
            }
        }
    }

    private fun tapFixture(scenario: ActivityScenario<MainActivity>, id: String) {
        val point = JSONArray(js(scenario,
            """(function(){var r=document.getElementById('$id').getBoundingClientRect();
            return [(r.left+r.width/2)/innerWidth,(r.top+r.height/2)/innerHeight];})()"""))
        onView(withId(R.id.reader_webview)).perform(GeneralClickAction(Tap.SINGLE, { view ->
            val origin = IntArray(2)
            view.getLocationOnScreen(origin)
            floatArrayOf(origin[0] + (point.getDouble(0) * view.width).toFloat(),
                origin[1] + (point.getDouble(1) * view.height).toFloat())
        }, Press.FINGER))
    }

    @Test fun mainDocumentFailureIsNotClearedByFinishOrStaleCallbacks() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            fixture(scenario)
            scenario.onActivity { activity ->
                val web = activity.findViewById<WebView>(R.id.reader_webview)
                fun request(address: String) = object : WebResourceRequest {
                    override fun getUrl() = Uri.parse(address)
                    override fun isForMainFrame() = true
                    override fun isRedirect() = false
                    override fun hasGesture() = false
                    override fun getMethod() = "GET"
                    override fun getRequestHeaders() = emptyMap<String, String>()
                }
                web.webViewClient.onReceivedHttpError(web, request(fixtureUrl),
                    WebResourceResponse("text/html", "UTF-8", 404, "Not Found", emptyMap(), null))
                assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.error_panel).visibility)
                web.webViewClient.onPageFinished(web, fixtureUrl)
                assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.error_panel).visibility)
                web.webViewClient.onReceivedHttpError(web, request("https://aihot.news/old-page"),
                    WebResourceResponse("text/html", "UTF-8", 500, "Server Error", emptyMap(), null))
                val message = activity.findViewById<android.widget.TextView>(R.id.error_message).text.toString()
                assertTrue(message.contains("404"))
                assertFalse(message.contains("500"))
            }
        }
    }
}
