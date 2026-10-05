package dev.personal.aihotreader

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.os.Build
import android.view.KeyEvent
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
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Live-site acceptance: touch/key events, with no direct access to site localStorage. */
@RunWith(AndroidJUnit4::class)
class LiveActionsTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    // Instrumentation runs as the target UID. Keep expected IDs in native test-only preferences,
    // independent of WebView storage; verification must still find them in the live website UI.
    private val ledger: SharedPreferences
        get() = instrumentation.targetContext.getSharedPreferences("live_bookmark_acceptance", Context.MODE_PRIVATE)

    @Test fun searchUsesMobileInputAndSiteNavigation() {
        withScenario("live-search") { scenario ->
            home(scenario)
            val searchButton = "header[data-phone-bar] button[aria-label=\"搜索\"]"
            tapDom(scenario, searchButton)
            val input = "[role=\"dialog\"][aria-label=\"搜索\"] input[type=\"search\"][name=\"q\"]"
            await(scenario, "Mobile search input did not open") { exists(scenario, input) }
            tapDom(scenario, input)
            await(scenario, "Search input did not receive focus") {
                js(scenario, "document.activeElement && document.activeElement.matches(${q(input)})") == "true"
            }
            val existingLength = js(scenario, "document.activeElement.value.length").toInt()
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_MOVE_END)
            repeat(existingLength) { instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DEL) }
            val query = "OpenAI"
            instrumentation.sendStringSync(query)
            await(scenario, "Native text input did not reach the site's input handler") {
                js(scenario, "(${visibleElement(input)}).value === ${q(query)}") == "true" &&
                    exists(scenario, "[role=\"dialog\"][aria-label=\"搜索\"] button[aria-label=\"清空\"]")
            }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_ENTER)
            await(scenario, "Search submit did not produce matching results", 60) {
                searchResultReady(scenario, query)
            }
            assertNoError(scenario)
            capture(scenario, "live-search-results.png")

            home(scenario)
            assertEquals("Site Home must clear the search query", "\"\"", js(scenario, "location.search"))
        }
    }

    /** Run seed, force-stop or upgrade the signed target APK, verify, then cleanup. */
    @Test fun bookmarkSurvivesProcessRestartAndSignedUpgrade() {
        val phase = InstrumentationRegistry.getArguments().getString("bookmarkPhase")
        assumeTrue("Pass bookmarkPhase=seed|verify|cleanup", phase in setOf("seed", "verify", "cleanup"))
        withScenario("live-bookmark-$phase") { scenario ->
            when (phase) {
                "seed" -> seedBookmark(scenario)
                "verify" -> {
                    assertTrue("No seeded bookmark in the acceptance ledger", ledger.getBoolean("owned", false))
                    openStarred(scenario)
                    verifyBookmark(scenario)
                    assertOriginalBookmarksRemain(scenario)
                }
                "cleanup" -> cleanupBookmark(scenario)
            }
            capture(scenario, "live-bookmark-$phase.png")
        }
    }

    private fun seedBookmark(scenario: ActivityScenario<MainActivity>) {
        assertFalse("A test-owned bookmark already exists; verify/cleanup it before reseeding",
            ledger.getBoolean("owned", false))
        openStarred(scenario)
        val originals = savedIds(scenario)
        home(scenario)
        val candidate = JSONObject(js(scenario, """(function(){
            var original=${JSONArray(originals.toList())};
            var cards=document.querySelectorAll('#main article[data-item-id]');
            for(var i=0;i<cards.length;i++){
                var id=cards[i].getAttribute('data-item-id'), link=cards[i].querySelector('a[href^="/items/"]');
                if(link && original.indexOf(id)<0) return {id:id,url:link.href};
            }
            return {};
        })()""".trimIndent()))
        assertTrue("Homepage has no article outside the existing bookmark set", candidate.has("id"))
        val id = candidate.getString("id")
        val url = candidate.getString("url")
        assertEquals(NavigationPolicy.Destination.INTERNAL, NavigationPolicy.classify(url))
        tapDom(scenario, "#main article[data-item-id=${q(id)}] a[href^=\"/items/\"]")
        await(scenario, "Selected article did not open", 50) {
            js(scenario, "location.href === ${q(url)} && !!document.querySelector('h1[data-page-title]')") == "true"
        }
        // The phone reading toolbar sits outside the article body. Its bookmark button has
        // aria-pressed and visible text, but intentionally no aria-label attribute.
        val button = "nav[aria-label=\"阅读工具\"] button[aria-pressed]"
        js(scenario, "window.scrollTo(0,0);")
        await(scenario, "Phone reading-toolbar bookmark did not become usable") {
            js(scenario, """(function(){var button=${visibleElement(button)};
                if(!button)return false;var r=button.getBoundingClientRect();
                return r.top>=0 && r.bottom<=innerHeight+1;
            })()""".trimIndent()) == "true"
        }
        assertEquals("Selected article was already bookmarked; it must not be changed", "\"false\"",
            js(scenario, "(${visibleElement(button)}).getAttribute('aria-pressed')"))
        assertEquals("Unexpected reading-toolbar action", "\"收藏\"",
            js(scenario, "(${visibleElement(button)}).textContent.trim()"))
        val title = decodeString(js(scenario,
            "document.querySelector('h1[data-page-title]').textContent.replace(/\\s+/g,' ').trim()"))

        // Write provenance before touching so an interrupted run can clean up only this new item.
        assertTrue("Cannot save test-owned bookmark provenance", ledger.edit()
            .putString("id", id).putString("url", url).putString("title", title)
            .putStringSet("originalIds", originals).putBoolean("owned", true).commit())
        tapDom(scenario, "$button[aria-pressed=\"false\"]")
        await(scenario, "Native bookmark touch did not change the article state") {
            js(scenario, """(function(){var button=document.querySelector(${q(button)});
                return !!button && button.getAttribute('aria-pressed')==='true' &&
                  button.textContent.trim()==='已收藏';})()""".trimIndent()) == "true"
        }
        openStarred(scenario)
        verifyBookmark(scenario)
        assertOriginalBookmarksRemain(scenario)
        // Let Chromium flush its own storage to disk before the runner force-stops the target.
        Thread.sleep(2000)
    }

    private fun verifyBookmark(scenario: ActivityScenario<MainActivity>) {
        val id = requireNotNull(ledger.getString("id", null))
        val title = requireNotNull(ledger.getString("title", null))
        val card = "#main li[data-card-key=${q(id)}]"
        await(scenario, "Seeded article is absent from the live bookmarks page: $id", 40) {
            js(scenario, """(function(){var card=document.querySelector(${q(card)});
                var heading=card && card.querySelector('h2');
                return !!heading && heading.textContent.replace(/\s+/g,' ').trim() === ${q(title)};
            })()""".trimIndent()) == "true"
        }
        assertNoError(scenario)
    }

    private fun cleanupBookmark(scenario: ActivityScenario<MainActivity>) {
        if (!ledger.getBoolean("owned", false)) return
        val id = requireNotNull(ledger.getString("id", null))
        val originals = ledger.getStringSet("originalIds", emptySet())!!.toSet()
        assertFalse("Refusing to remove a bookmark that predates this test", originals.contains(id))
        openStarred(scenario)
        verifyBookmark(scenario)
        val card = "#main li[data-card-key=${q(id)}]"
        if (js(scenario, "!!document.querySelector(${q(card)})") == "true") {
            tapDom(scenario, "$card button[aria-label=\"取消收藏\"]")
            await(scenario, "Test-owned bookmark was not removed") {
                js(scenario, "!document.querySelector(${q(card)})") == "true"
            }
        }
        assertOriginalBookmarksRemain(scenario)
        assertTrue("Cannot clear the completed test ledger", ledger.edit().clear().commit())
    }

    private fun assertOriginalBookmarksRemain(scenario: ActivityScenario<MainActivity>) {
        val originals = ledger.getStringSet("originalIds", emptySet())!!.toSet()
        assertTrue("A bookmark that predates this test is missing", savedIds(scenario).containsAll(originals))
    }

    private fun savedIds(scenario: ActivityScenario<MainActivity>): Set<String> {
        val ids = JSONArray(js(scenario,
            "Array.prototype.map.call(document.querySelectorAll('#main li[data-card-key]'),function(el){return el.getAttribute('data-card-key');})"))
        return (0 until ids.length()).map { ids.getString(it) }.toSet()
    }

    private fun openStarred(scenario: ActivityScenario<MainActivity>) {
        home(scenario)
        tapDom(scenario, "nav[aria-label=\"底部导航\"] a[href=\"/more\"]")
        await(scenario, "Mobile My page did not open") {
            js(scenario, "location.pathname === '/more'") == "true" &&
                exists(scenario, "#main a[href=\"/starred\"]")
        }
        tapDom(scenario, "#main a[href=\"/starred\"]")
        await(scenario, "The live bookmarks page did not hydrate", 40) {
            js(scenario, """location.pathname === '/starred' &&
                !!document.querySelector('h1[data-page-title]') &&
                (!!document.querySelector('#main li[data-card-key]') ||
                document.querySelector('#main').innerText.indexOf('还没有收藏内容') >= 0)""".trimIndent()) == "true"
        }
        assertNoError(scenario)
    }

    private fun home(scenario: ActivityScenario<MainActivity>) {
        await(scenario, "Live document has not loaded", 60) {
            js(scenario, "document.readyState === 'complete' && typeof TextEncoderStream === 'function' && !!document.querySelector('#main')") == "true"
        }
        if (js(scenario, "location.pathname === '/' && location.search === ''") != "true") {
            val siteHome = "nav[aria-label=\"底部导航\"] a[href=\"/\"]"
            if (exists(scenario, siteHome)) {
                tapDom(scenario, siteHome)
            } else {
                // Article pages have a reading toolbar instead of the site tab bar. They were
                // opened from Home by this test, so Android Back must return to that real route.
                var canGoBack = false
                scenario.onActivity { canGoBack = it.findViewById<WebView>(R.id.reader_webview).canGoBack() }
                assertTrue("No site Home tab and no native history to return through; ${diagnostics(scenario)}", canGoBack)
                scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            }
        }
        await(scenario, "Live homepage is not ready for native interaction", 60) {
            js(scenario, """location.pathname === '/' && location.search === '' &&
                document.readyState === 'complete' && typeof TextEncoderStream === 'function' &&
                !!document.querySelector('#main article[data-item-id]') &&
                !!document.querySelector('nav[aria-label="底部导航"]') &&
                getComputedStyle(document.querySelector('nav[aria-label="底部导航"]')).position === 'fixed'""".trimIndent()) == "true"
        }
        assertNoError(scenario)
    }

    private fun searchResultReady(scenario: ActivityScenario<MainActivity>, query: String): Boolean =
        js(scenario, """(function(){
            var input=document.getElementById('site-search'), cards=document.querySelectorAll('#main article[data-item-id]');
            return location.pathname === '/all' && new URLSearchParams(location.search).get('q') === ${q(query)} &&
              document.title.indexOf('搜索') >= 0 && document.title.indexOf(${q(query)}) >= 0 &&
              !!input && input.value === ${q(query)} && cards.length > 0 &&
              Array.prototype.some.call(cards,function(card){return card.textContent.toLowerCase().indexOf(${q(query.lowercase())}) >= 0;});
        })()""".trimIndent()) == "true"

    private fun exists(scenario: ActivityScenario<MainActivity>, selector: String): Boolean =
        js(scenario, "!!(${visibleElement(selector)})") == "true"

    private fun visibleElement(selector: String): String =
        "Array.prototype.filter.call(document.querySelectorAll(${q(selector)}),function(el){" +
            "var r=el.getBoundingClientRect();if(r.width<=0 || r.height<=0)return false;" +
            "for(var p=el;p && p.nodeType===1;p=p.parentElement){var s=getComputedStyle(p);" +
            "if(s.visibility==='hidden' || s.display==='none' || Number(s.opacity)===0 || p.hasAttribute('inert'))return false;}" +
            "return getComputedStyle(el).pointerEvents!=='none';})[0]"

    private fun tapDom(scenario: ActivityScenario<MainActivity>, selector: String) {
        await(scenario, "No usable control: $selector") { exists(scenario, selector) }
        js(scenario, "(function(){var e=${visibleElement(selector)},r=e.getBoundingClientRect();if(r.top<55 || r.bottom>innerHeight)e.scrollIntoView({block:'center',inline:'nearest',behavior:'instant'});})()")
        waitForVisual(scenario)
        val point = JSONArray(js(scenario,
            "(function(){var r=(${visibleElement(selector)}).getBoundingClientRect();" +
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

    private fun assertNoError(scenario: ActivityScenario<MainActivity>) {
        scenario.onActivity {
            assertNotEquals("Main-document error panel is visible", View.VISIBLE,
                it.findViewById<View>(R.id.error_panel).visibility)
        }
    }

    private fun withScenario(name: String, block: (ActivityScenario<MainActivity>) -> Unit) {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            try { block(scenario) } finally {
                runCatching { File(outputDirectory(), "$name-state.txt").writeText(diagnostics(scenario)) }
                runCatching { capture(scenario, "$name-last-screen.png") }
            }
        }
    }

    private fun await(scenario: ActivityScenario<MainActivity>, message: String, seconds: Long = 25,
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
            native = "API=${Build.VERSION.SDK_INT}; " + HttpsFixture.describe(it.findViewById(R.id.reader_webview))
        }
        return native + "; document=" + js(scenario, """(function(){
            var tools=document.querySelector('nav[aria-label="阅读工具"]');
            var search=document.querySelector('[role="dialog"][aria-label="搜索"]');
            return {url:location.href,ready:document.readyState,
              title:document.title,textEncoderStream:typeof TextEncoderStream,
              navigatorShare:typeof navigator.share,clipboard:typeof navigator.clipboard,
              coarsePointer:matchMedia('(pointer: coarse)').matches,
              bookmarks:document.querySelectorAll('#main li[data-card-key]').length,
              cards:document.querySelectorAll('#main article[data-item-id]').length,
              buttons:Array.prototype.slice.call(document.querySelectorAll('button'),0,50).map(function(b){
                var r=b.getBoundingClientRect(),s=getComputedStyle(b);
                return {text:b.textContent.trim().slice(0,60),label:b.getAttribute('aria-label'),
                  pressed:b.getAttribute('aria-pressed'),rect:[r.x,r.y,r.width,r.height],
                  display:s.display,visibility:s.visibility,opacity:s.opacity};}),
              inputs:Array.prototype.map.call(document.querySelectorAll('input'),function(i){
                var r=i.getBoundingClientRect();return {id:i.id,name:i.name,type:i.type,
                  placeholder:i.placeholder,value:i.value,rect:[r.x,r.y,r.width,r.height]};}),
              readingTools:tools?tools.outerHTML.slice(0,3000):null,
              searchDialog:search?search.outerHTML.slice(0,2000):null};
        })()""".trimIndent())
    }

    private fun js(scenario: ActivityScenario<MainActivity>, script: String): String {
        val result = AtomicReference<String>()
        val latch = CountDownLatch(1)
        scenario.onActivity {
            it.findViewById<WebView>(R.id.reader_webview).evaluateJavascript(script) { value ->
                result.set(value)
                latch.countDown()
            }
        }
        check(latch.await(10, TimeUnit.SECONDS)) { "WebView did not respond: $script" }
        return result.get()
    }

    private fun q(value: String): String = JSONObject.quote(value)
    private fun decodeString(json: String): String = JSONArray("[$json]").getString(0)
    private fun outputDirectory(): File = File(instrumentation.targetContext.getExternalFilesDir(null), "validation")
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
        val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: return
        File(outputDirectory(), name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
