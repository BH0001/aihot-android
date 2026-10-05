package dev.personal.aihotreader

import android.os.Build
import android.view.View
import android.webkit.WebView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.max

/** Live-site restoration: no loadUrl or scroll command is allowed after recreate(). */
@RunWith(AndroidJUnit4::class)
class ReadingRestoreTest {
    private val observations = StringBuilder()

    @Test fun articlePositionAndHistorySurviveActivityRecreation() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            try {
                await(scenario, "Homepage did not finish loading", 60) {
                    js(scenario, "location.href===${q(NavigationPolicy.HOME_URL)} && " +
                        "document.readyState==='complete' && !!document.querySelector('#main a[href^=\"/items/\"]')") == "true"
                }
                assertNoError(scenario)
                // Give the article exactly one preceding entry. No synthetic history is inserted.
                scenario.onActivity { it.findViewById<WebView>(R.id.reader_webview).clearHistory() }
                val candidates = JSONArray(js(scenario, """(function(){
                    return Array.from(new Set(Array.from(document.querySelectorAll('#main a[href^="/items/"]'))
                      .map(function(a){return a.href;}))).slice(0,6);
                })()""".trimIndent()))
                assertTrue("Homepage returned no article candidates", candidates.length() > 0)

                var articleUrl: String? = null
                var articleTitle = ""
                for (index in 0 until candidates.length()) {
                    val candidate = candidates.getString(index)
                    assertEquals(NavigationPolicy.Destination.INTERNAL, NavigationPolicy.classify(candidate))
                    scenario.onActivity { it.findViewById<WebView>(R.id.reader_webview).loadUrl(candidate) }
                    await(scenario, "Article did not render: $candidate", 60) {
                        js(scenario, "location.href===${q(candidate)} && document.readyState==='complete' && " +
                            "!!document.querySelector('h1[data-page-title]') && document.body.innerText.length>300") == "true"
                    }
                    assertNoError(scenario)
                    waitForVisual(scenario)
                    // Wait for the article layout to stop growing before choosing a scroll offset.
                    var lastHeight = -1
                    var stable = 0
                    await(scenario, "Article height never settled: $candidate", 20) {
                        val currentHeight = nativeState(scenario).contentHeight
                        stable = if (currentHeight == lastHeight) stable + 1 else 0
                        lastHeight = currentHeight
                        stable >= 4
                    }
                    val state = nativeState(scenario)
                    observations.appendLine("candidate=$candidate; $state")
                    // Short summaries cannot demonstrate recovery of a meaningful reading position.
                    if (state.maxScroll >= state.height && state.height > 0) {
                        articleUrl = candidate
                        articleTitle = JSONArray("[" + js(scenario,
                            "document.querySelector('h1[data-page-title]').textContent.trim()") + "]").getString(0)
                        break
                    }
                    scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
                    awaitHomepage(scenario)
                }
                val chosenUrl = articleUrl ?: throw AssertionError(
                    "None of the first ${candidates.length()} articles can scroll a full viewport; $observations")
                val beforeScroll = nativeState(scenario)
                assertTrue("Article has no preceding homepage; $beforeScroll", beforeScroll.canGoBack)
                val targetY = beforeScroll.maxScroll / 2
                scenario.onActivity { it.findViewById<WebView>(R.id.reader_webview).scrollTo(0, targetY) }
                await(scenario, "Article did not scroll to its middle", 10) {
                    abs(nativeState(scenario).scrollY - targetY) <= 4
                }
                waitForVisual(scenario)
                val saved = nativeState(scenario)
                assertTrue("The saved reading position is too close to the top; $saved",
                    saved.scrollY >= saved.height / 2)
                observations.appendLine("beforeRecreate=$saved; title=$articleTitle")

                scenario.recreate()

                // From this point onward the test only observes; production restoration must load
                // the document and position it. In particular, do not call loadUrl or scrollTo here.
                await(scenario, "Recreated Activity did not restore the article document", 60) {
                    val state = nativeState(scenario)
                    state.url == chosenUrl && js(scenario, """(function(){
                        var title=document.querySelector('h1[data-page-title]');
                        return location.href===${q(chosenUrl)} && document.readyState==='complete' &&
                          !!title && title.textContent.trim()===${q(articleTitle)} && document.body.innerText.length>300;
                    })()""".trimIndent()) == "true"
                }
                assertNoError(scenario)
                val tolerance = max(24, saved.height / 20)
                var stablePosition = 0
                await(scenario, "Article scroll was not restored (expected ${saved.scrollY}, tolerance $tolerance)", 20) {
                    val state = nativeState(scenario)
                    stablePosition = if (abs(state.scrollY - saved.scrollY) <= tolerance &&
                        state.scrollY >= saved.height / 2) stablePosition + 1 else 0
                    stablePosition >= 4
                }
                val restored = nativeState(scenario)
                observations.appendLine("afterRecreate=$restored")
                assertTrue("Restored article lost the preceding homepage; $restored", restored.canGoBack)
                assertEquals("History entry count changed during recreation", saved.historySize, restored.historySize)
                assertEquals("Current history index changed during recreation", saved.historyIndex, restored.historyIndex)

                scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
                awaitHomepage(scenario)
                assertNoError(scenario)
                observations.appendLine("afterBack=${nativeState(scenario)}")
            } finally {
                observations.appendLine(runCatching { diagnostics(scenario) }
                    .getOrElse { "Final diagnostics unavailable: $it" })
                val directory = File(InstrumentationRegistry.getInstrumentation().targetContext
                    .getExternalFilesDir(null), "validation").also { it.mkdirs() }
                File(directory, "reading-restore-state.txt").writeText(observations.toString())
            }
        }
    }

    private fun awaitHomepage(scenario: ActivityScenario<MainActivity>) {
        await(scenario, "System back did not restore the homepage", 60) {
            nativeState(scenario).url == NavigationPolicy.HOME_URL &&
                js(scenario, "location.href===${q(NavigationPolicy.HOME_URL)} && " +
                    "!!document.querySelector('#main a[href^=\"/items/\"]')") == "true"
        }
    }

    private fun assertNoError(scenario: ActivityScenario<MainActivity>) {
        scenario.onActivity {
            assertNotEquals("Main-frame error visible", View.VISIBLE,
                it.findViewById<View>(R.id.error_panel).visibility)
        }
    }

    @Suppress("DEPRECATION")
    private fun nativeState(scenario: ActivityScenario<MainActivity>): ReaderState {
        var state: ReaderState? = null
        scenario.onActivity {
            val web = it.findViewById<WebView>(R.id.reader_webview)
            val history = web.copyBackForwardList()
            state = ReaderState(web.url, web.scrollY, web.height, web.contentHeight,
                (web.contentHeight * web.scale - web.height).toInt().coerceAtLeast(0),
                web.canGoBack(), history.size, history.currentIndex)
        }
        return requireNotNull(state)
    }

    private fun await(scenario: ActivityScenario<MainActivity>, message: String, seconds: Long,
        condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds)
        while (System.nanoTime() < deadline) {
            if (condition()) return
            Thread.sleep(250)
        }
        fail("$message; ${diagnostics(scenario)}; observations=$observations")
    }

    private fun diagnostics(scenario: ActivityScenario<MainActivity>): String {
        var native = ""
        scenario.onActivity {
            native = "Android=${Build.VERSION.RELEASE}/API${Build.VERSION.SDK_INT}; " +
                HttpsFixture.describe(it.findViewById(R.id.reader_webview)) +
                "; errorPanel=${it.findViewById<View>(R.id.error_panel).visibility}"
        }
        val page = js(scenario, """(function(){return {
            url:location.href,ready:document.readyState,viewport:[innerWidth,innerHeight],
            scroll:[scrollX,scrollY],scrollHeight:document.documentElement.scrollHeight,
            title:document.title,bodyTextLength:document.body?document.body.innerText.length:0
        };})()""".trimIndent())
        return "$native; nativeScroll=${nativeState(scenario)}; page=$page"
    }

    private fun waitForVisual(scenario: ActivityScenario<MainActivity>) {
        val done = CountDownLatch(1)
        scenario.onActivity { it.findViewById<WebView>(R.id.reader_webview)
            .postVisualStateCallback(17L, object : WebView.VisualStateCallback() {
                override fun onComplete(requestId: Long) { done.countDown() }
            }) }
        check(done.await(10, TimeUnit.SECONDS)) { "WebView did not present the article; ${diagnostics(scenario)}" }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        Thread.sleep(250)
    }

    private fun js(scenario: ActivityScenario<MainActivity>, script: String): String {
        val result = AtomicReference<String>()
        val done = CountDownLatch(1)
        scenario.onActivity { it.findViewById<WebView>(R.id.reader_webview).evaluateJavascript(script) {
            result.set(it)
            done.countDown()
        } }
        check(done.await(10, TimeUnit.SECONDS)) { "JavaScript timeout: $script" }
        return result.get()
    }

    private fun q(value: String): String = JSONObject.quote(value)
    private data class ReaderState(val url: String?, val scrollY: Int, val height: Int,
        val contentHeight: Int, val maxScroll: Int, val canGoBack: Boolean,
        val historySize: Int, val historyIndex: Int)
}
