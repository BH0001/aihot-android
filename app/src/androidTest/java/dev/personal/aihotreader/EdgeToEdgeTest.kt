package dev.personal.aihotreader

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.view.KeyEvent
import android.view.View
import android.webkit.WebView
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.GeneralClickAction
import androidx.test.espresso.action.Press
import androidx.test.espresso.action.Tap
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.espresso.Espresso.pressBack
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.max

@RunWith(AndroidJUnit4::class)
class EdgeToEdgeTest {
    private fun js(s: ActivityScenario<MainActivity>, expression: String): String {
        val result = AtomicReference<String>()
        val done = CountDownLatch(1)
        s.onActivity { it.findViewById<WebView>(R.id.reader_webview).evaluateJavascript(expression) { v -> result.set(v); done.countDown() } }
        check(done.await(10, TimeUnit.SECONDS))
        return result.get()
    }
    private fun await(label: String, block: () -> Boolean) {
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        while (System.nanoTime() < until) { if (block()) return; Thread.sleep(150) }
        fail(label)
    }
    private fun fixture(s: ActivityScenario<MainActivity>) {
        s.onActivity { a -> a.findViewById<WebView>(R.id.reader_webview).apply {
            stopLoading()
            val url = "https://aihot.news/__edge_test__"
            HttpsFixture.install(this, url, """<!doctype html><meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">
                <style>:root{--bg:#faf9f6}body{margin:0;background:var(--bg);font:20px sans-serif}
                header{position:fixed;top:0;left:0;right:0;height:55px;background:#ff5353}
                footer{position:fixed;bottom:0;left:0;right:0;height:48px;background:#fff;padding-bottom:env(safe-area-inset-bottom)}
                main{padding:80px 20px}button,input{font-size:20px;padding:14px;display:block;margin-bottom:25px}
                [data-theme=dark]{--bg:#13191c;color:white}[data-theme=dark] header{background:#13191c}
                [data-theme=dark] footer{background:#1a2226}</style>
                <header>Header</header><main><button id="theme" onclick="document.documentElement.dataset.theme=document.documentElement.dataset.theme==='dark'?'light':'dark'">Toggle theme</button>
                <input id="query" placeholder="Search"><p>Edge acceptance</p></main><footer>Navigation</footer>""".trimIndent())
            loadUrl(url)
        } }
        await("Fixture did not render") { js(s, "!!document.getElementById('theme')") == "true" }
        await("Initial header colour did not reach status bar") { top(s) == Color.rgb(255,83,83) }
    }
    private fun top(s: ActivityScenario<MainActivity>): Int {
        var c=0
        s.onActivity { c=(it.findViewById<View>(R.id.reader_root).background as ReaderBackdrop).topColor }
        return c
    }
    private fun tap(s: ActivityScenario<MainActivity>, id: String) {
        val xy=JSONArray(js(s,"(function(){var r=document.getElementById('$id').getBoundingClientRect();return [(r.left+r.right)/2/innerWidth,(r.top+r.bottom)/2/innerHeight];})()"))
        onView(withId(R.id.reader_webview)).perform(GeneralClickAction(Tap.SINGLE,{ v ->
            val origin=IntArray(2);v.getLocationOnScreen(origin)
            floatArrayOf(origin[0]+v.width*xy.getDouble(0).toFloat(),origin[1]+v.height*xy.getDouble(1).toFloat())
        },Press.FINGER))
    }
    @Test fun pageThemeControlsBarColorsAndIcons() {
        ActivityScenario.launch(MainActivity::class.java).use { s ->
            fixture(s)
            tap(s,"theme")
            await("Dark web theme not synchronized") { top(s)==Color.rgb(19,25,28) }
            s.onActivity {
                val root=it.findViewById<View>(R.id.reader_root)
                assertEquals(Color.rgb(26,34,38),(root.background as ReaderBackdrop).bottomColor)
                assertFalse(WindowCompat.getInsetsController(it.window,root).isAppearanceLightStatusBars)
            }
            tap(s,"theme")
            await("Light web theme not synchronized") { top(s)==Color.rgb(255,83,83) }
            s.onActivity {
                val root=it.findViewById<View>(R.id.reader_root)
                assertEquals(Color.WHITE,(root.background as ReaderBackdrop).bottomColor)
            }
        }
    }
    @Test fun nativeSafeAreaIsAppliedOnce() {
        ActivityScenario.launch(MainActivity::class.java).use { s ->
            fixture(s)
            s.onActivity {
                val root=it.findViewById<View>(R.id.reader_root)
                val insets=ViewCompat.getRootWindowInsets(root)!!
                val safe=insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
                val ime=insets.getInsets(WindowInsetsCompat.Type.ime())
                assertEquals(safe.top,root.paddingTop)
                assertEquals(safe.left,root.paddingLeft)
                assertEquals(safe.right,root.paddingRight)
                assertEquals(max(safe.bottom,ime.bottom),root.paddingBottom)
                val web=it.findViewById<WebView>(R.id.reader_webview)
                assertEquals(root.height-root.paddingTop-root.paddingBottom,web.height)
                assertEquals(root.width-root.paddingLeft-root.paddingRight,web.width)
            }
            assertEquals("\"0px\"",js(s,"getComputedStyle(document.querySelector('footer')).paddingBottom"))
        }
    }

    @Test fun themeChangesWithoutTouchStillSynchronize() {
        ActivityScenario.launch(MainActivity::class.java).use { s ->
            fixture(s)
            Thread.sleep(1_500) // All touch/navigation delayed probes have expired.
            js(s, "document.documentElement.dataset.theme='dark'")
            await("Script-only theme change did not update bars") { top(s)==Color.rgb(19,25,28) }
            s.onActivity { it.findViewById<WebView>(R.id.reader_webview).requestFocus() }
            js(s, "document.getElementById('theme').focus()")
            assertEquals("\"theme\"",js(s,"document.activeElement.id"))
            InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_ENTER)
            await("Keyboard event did not activate the focused web button") {
                js(s,"document.documentElement.dataset.theme==='light'")=="true"
            }
            await("Keyboard theme action did not update bars") { top(s)==Color.rgb(255,83,83) }
        }
    }

    @Test fun nativeMenuAndErrorFollowWebsiteTheme() {
        ActivityScenario.launch(MainActivity::class.java).use { s ->
            fixture(s)
            tap(s, "theme")
            await("Dark theme not ready") { top(s)==Color.rgb(19,25,28) }
            onView(withId(R.id.reader_webview)).perform(GeneralClickAction(Tap.LONG, { v ->
                val origin=IntArray(2);v.getLocationOnScreen(origin)
                floatArrayOf(origin[0]+v.width*0.5f, origin[1]+v.height*0.75f)
            }, Press.FINGER))
            onView(withText(R.string.home)).check { v, error ->
                if(error!=null) throw error
                assertTrue("Dark menu must keep text readable", androidx.core.graphics.ColorUtils.calculateLuminance((v as TextView).currentTextColor)>0.5)
            }
            val image=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            val dir=java.io.File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null),"validation/v102").also { it.mkdirs() }
            image?.let { b -> java.io.File(dir,"dark-menu.png").outputStream().use { b.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }; b.recycle() }
            pressBack()
            s.onActivity { a ->
                val web=a.findViewById<WebView>(R.id.reader_webview)
                val request=object: WebResourceRequest {
                    override fun getUrl()=Uri.parse(web.url!!)
                    override fun isForMainFrame()=true
                    override fun isRedirect()=false
                    override fun hasGesture()=false
                    override fun getMethod()="GET"
                    override fun getRequestHeaders()=emptyMap<String,String>()
                }
                web.webViewClient.onReceivedHttpError(web,request,WebResourceResponse("text/html","UTF-8",503,"Unavailable",emptyMap(),null))
                assertEquals(Color.rgb(26,34,38),(a.findViewById<View>(R.id.error_panel).background as ColorDrawable).color)
                assertEquals(Color.rgb(94,234,212),a.findViewById<ProgressBar>(R.id.loading_progress).progressTintList!!.defaultColor)
                assertEquals(Color.rgb(179,190,210),a.findViewById<TextView>(R.id.error_message).currentTextColor)
            }
        }
    }
    @Test fun keyboardCyclesRestoreViewportWithoutLosingFocus() {
        ActivityScenario.launch(MainActivity::class.java).use { s ->
            fixture(s)
            var initialHeight=0
            s.onActivity { initialHeight=it.findViewById<WebView>(R.id.reader_webview).height }
            repeat(3) {
                tap(s,"query")
                await("Keyboard did not resize safe content") {
                    var resized=false
                    s.onActivity { a -> resized=a.findViewById<WebView>(R.id.reader_webview).height<initialHeight-100 }
                    resized
                }
                assertEquals("\"query\"",js(s,"document.activeElement.id"))
                InstrumentationRegistry.getInstrumentation().sendStringSync("ai")
                InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                await("Keyboard left ghost padding") {
                    var height=0;s.onActivity { a -> height=a.findViewById<WebView>(R.id.reader_webview).height };height==initialHeight
                }
                assertEquals("true",js(s,"visualViewport.height >= innerHeight - 1"))
            }
            assertEquals("\"aiaiai\"",js(s,"document.getElementById('query').value"))
        }
    }
}
