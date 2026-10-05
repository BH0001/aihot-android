package dev.personal.aihotreader

import android.graphics.Bitmap
import android.os.Build
import android.view.View
import android.webkit.WebView
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.GeneralClickAction
import androidx.test.espresso.action.Press
import androidx.test.espresso.action.Tap
import androidx.test.espresso.matcher.ViewMatchers.withId
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

/** Run explicitly against the live service. SSR text alone is never a compatibility pass. */
@RunWith(AndroidJUnit4::class)
class LiveSiteTest {
    @Test fun liveHomepageAndArticleCanBeRead() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            try {
                await(scenario, "Homepage has no article links", 60) {
                    js(scenario, "!!document.querySelector('#main a[href^=\"/items/\"]')") == "true"
                }
                scenario.onActivity {
                    assertNotEquals("Main-frame error visible", View.VISIBLE,
                        it.findViewById<View>(R.id.error_panel).visibility)
                }
                capture(scenario, "homepage.png")

                // These are used by the current site's JS/CSS, not a production version gate.
                // A legacy engine can paint SSR news while failing to hydrate or apply Tailwind styles.
                assertJsTrue(scenario, "Missing TextEncoderStream required by the site",
                    "typeof TextEncoderStream === 'function'")
                assertEquals("Site JavaScript syntax is unsupported; ${diagnostics(scenario)}", "1",
                    js(scenario, "(function(){return ({value:1})?.value ?? 0;})()"))
                assertJsTrue(scenario, "Site color syntax is unsupported",
                    "typeof CSS !== 'undefined' && CSS.supports('color','oklch(50% 0.1 180)')")
                await(scenario, "Mobile CSS layout did not become usable", 20) {
                    js(scenario, mobileLayoutCheck) == "true"
                }
                await(scenario, "Site resources did not finish loading before interaction", 30) {
                    js(scenario, "document.readyState === 'complete'") == "true"
                }

                // Native touches must change actual React state and restore it. A plain SSR link
                // can navigate without hydration, so an article URL change alone is insufficient.
                val collapseSelector = "button[aria-expanded=\"true\"][aria-label^=\"收起\"]"
                await(scenario, "No visible news-group collapse control", 20) {
                    js(scenario, "!!(${visibleElement(collapseSelector)})") == "true"
                }
                val label = decodeString(js(scenario,
                    "(${visibleElement(collapseSelector)}).getAttribute('aria-label')"))
                val date = label.removePrefix("收起")
                val expanded = "button[aria-label=${JSONObject.quote(label)}][aria-expanded=\"true\"]"
                val collapsed = "button[aria-label=${JSONObject.quote("展开$date")}][aria-expanded=\"false\"]"
                tapDom(scenario, expanded)
                await(scenario, "News group did not collapse: site hydration/event handling failed", 20) {
                    js(scenario, "!!(${visibleElement(collapsed)})") == "true"
                }
                tapDom(scenario, collapsed)
                await(scenario, "News group did not expand again", 20) {
                    js(scenario, "!!(${visibleElement(expanded)})") == "true"
                }

                val articleSelector = "#main a[href^=\"/items/\"]"
                val article = JSONObject(js(scenario,
                    "(function(){var a=${visibleElement(articleSelector)};return {url:a.href,title:a.textContent.trim()};})()"))
                val url = article.getString("url")
                val title = article.getString("title")
                assertEquals(NavigationPolicy.Destination.INTERNAL, NavigationPolicy.classify(url))
                val token = "live-${System.nanoTime()}"
                js(scenario, "window.__aihotLiveDocumentToken=${JSONObject.quote(token)};")
                tapDom(scenario, articleSelector)
                await(scenario, "Article did not render through the hydrated SPA route", 40) {
                    js(scenario, """(function(){
                        var title=document.querySelector('h1[data-page-title]');
                        return location.href === ${JSONObject.quote(url)} &&
                          window.__aihotLiveDocumentToken === ${JSONObject.quote(token)} &&
                          !!title && title.textContent.trim() === ${JSONObject.quote(title)} &&
                          document.body.innerText.length > 300;
                    })()""".trimIndent()) == "true"
                }
                capture(scenario, "article.png")
                await(scenario, "Article route did not reach native WebView history", 20) {
                    var ready = false
                    scenario.onActivity {
                        val web = it.findViewById<WebView>(R.id.reader_webview)
                        ready = web.url == url && web.canGoBack()
                    }
                    ready
                }
                scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
                await(scenario, "System back did not restore the hydrated homepage", 30) {
                    js(scenario, """location.pathname === '/' &&
                        window.__aihotLiveDocumentToken === ${JSONObject.quote(token)} &&
                        !!document.querySelector('#main a[href^="/items/"]')""".trimIndent()) == "true"
                }
                await(scenario, "Homepage layout did not render after returning", 30) {
                    js(scenario, mobileLayoutCheck) == "true"
                }
            } finally {
                val state = runCatching { diagnostics(scenario) }.getOrElse { "Diagnostics unavailable: $it" }
                runCatching { File(validationDirectory(), "live-site-state.txt").writeText(state) }
                runCatching { capture(scenario, "last-screen.png") }
            }
        }
    }

    private val mobileLayoutCheck = """(function(){
        var nav=document.querySelector('nav[aria-label="底部导航"]');
        var header=document.querySelector('header[data-phone-bar]');
        var desktop=document.querySelector('nav[aria-label="主导航"]');
        if(!nav || !header || !desktop || !nav.firstElementChild) return false;
        var rect=nav.getBoundingClientRect(), grid=nav.firstElementChild;
        return innerWidth < 1024 && getComputedStyle(nav).position === 'fixed' &&
          getComputedStyle(header).position === 'sticky' &&
          getComputedStyle(grid).display === 'grid' && grid.querySelectorAll('a').length === 5 &&
          getComputedStyle(grid).gridTemplateColumns.trim().split(/\s+/).length === 5 &&
          rect.width >= innerWidth-2 && rect.height >= 40 && rect.height <= 100 &&
          Math.abs(rect.bottom-innerHeight) <= 3 && desktop.getBoundingClientRect().width === 0 &&
          document.documentElement.scrollWidth <= innerWidth+2;
    })()""".trimIndent()

    private fun visibleElement(selector: String): String =
        "Array.prototype.filter.call(document.querySelectorAll(${JSONObject.quote(selector)}),function(el){" +
            "var r=el.getBoundingClientRect();return r.width>0 && r.height>0 && " +
            "getComputedStyle(el).visibility!=='hidden';})[0]"

    private fun tapDom(scenario: ActivityScenario<MainActivity>, selector: String) {
        js(scenario, """(function(){
            var e=${visibleElement(selector)},r=e.getBoundingClientRect();
            var hit=document.elementFromPoint(r.left+r.width/2,r.top+r.height/2);
            if(r.top<55 || r.bottom>innerHeight-55 || !hit || !e.contains(hit))
                e.scrollIntoView({block:'center',inline:'nearest',behavior:'instant'});
        })()""".trimIndent())
        waitForVisual(scenario)
        assertJsTrue(scenario, "Control is obscured: $selector", """(function(){
            var e=${visibleElement(selector)},r=e.getBoundingClientRect();
            var hit=document.elementFromPoint(r.left+r.width/2,r.top+r.height/2);
            return !!hit && e.contains(hit);
        })()""".trimIndent())
        val point = JSONArray(js(scenario,
            "(function(){var el=${visibleElement(selector)};var r=el.getBoundingClientRect();" +
                "return [(r.left+r.width/2)/innerWidth,(r.top+r.height/2)/innerHeight];})()"))
        assertTrue("Control lies outside the viewport: $selector; ${diagnostics(scenario)}",
            point.getDouble(0) in 0.0..1.0 && point.getDouble(1) in 0.0..1.0)
        onView(withId(R.id.reader_webview)).perform(GeneralClickAction(Tap.SINGLE, { view ->
            val origin = IntArray(2)
            view.getLocationOnScreen(origin)
            floatArrayOf(origin[0] + (point.getDouble(0) * view.width).toFloat(),
                origin[1] + (point.getDouble(1) * view.height).toFloat())
        }, Press.FINGER))
    }

    private fun assertJsTrue(scenario: ActivityScenario<MainActivity>, message: String, expression: String) {
        assertEquals("$message; ${diagnostics(scenario)}", "true", js(scenario, expression))
    }

    private fun await(scenario: ActivityScenario<MainActivity>, message: String, seconds: Long,
        condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds)
        while (System.nanoTime() < deadline) {
            if (condition()) return
            Thread.sleep(200)
        }
        fail("$message; ${diagnostics(scenario)}")
    }

    private fun diagnostics(scenario: ActivityScenario<MainActivity>): String {
        var native = ""
        scenario.onActivity {
            native = "Android=${Build.VERSION.RELEASE}/API${Build.VERSION.SDK_INT}; " +
                HttpsFixture.describe(it.findViewById(R.id.reader_webview)) +
                "; errorPanel=${it.findViewById<View>(R.id.error_panel).visibility}"
        }
        val page = js(scenario, """(function(){
            var n=document.querySelector('nav[aria-label="底部导航"]');
            return {url:location.href,ready:document.readyState,title:document.title,
              viewport:[innerWidth,innerHeight],scrollWidth:document.documentElement.scrollWidth,
              textEncoderStream:typeof TextEncoderStream,bodyTextLength:document.body?document.body.innerText.length:0,
              navPosition:n?getComputedStyle(n).position:null,
              navRect:n?[n.getBoundingClientRect().width,n.getBoundingClientRect().height,n.getBoundingClientRect().bottom]:null};
        })()""".trimIndent())
        return "$native; page=$page"
    }

    private fun decodeString(json: String): String = JSONArray("[$json]").getString(0)

    private fun js(scenario: ActivityScenario<MainActivity>, script: String): String {
        val result = AtomicReference<String>()
        val latch = CountDownLatch(1)
        scenario.onActivity { activity ->
            activity.findViewById<WebView>(R.id.reader_webview).evaluateJavascript(script) {
                result.set(it)
                latch.countDown()
            }
        }
        check(latch.await(8, TimeUnit.SECONDS)) { "WebView JavaScript did not respond: $script" }
        return result.get()
    }

    private fun validationDirectory(): File =
        File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "validation")
            .also { it.mkdirs() }

    private fun waitForVisual(scenario: ActivityScenario<MainActivity>) {
        val painted = CountDownLatch(1)
        scenario.onActivity { it.findViewById<WebView>(R.id.reader_webview)
            .postVisualStateCallback(1L, object : WebView.VisualStateCallback() {
                override fun onComplete(requestId: Long) { painted.countDown() }
            }) }
        check(painted.await(10, TimeUnit.SECONDS)) { "WebView did not present the updated layout" }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        Thread.sleep(250) // Let the compositor present the ready frame before native input/capture.
    }

    private fun capture(scenario: ActivityScenario<MainActivity>, name: String) {
        waitForVisual(scenario)
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot() ?: return
        File(validationDirectory(), name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
