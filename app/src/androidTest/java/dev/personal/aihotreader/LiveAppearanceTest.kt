package dev.personal.aihotreader

import android.graphics.Bitmap
import android.graphics.Color
import android.view.View
import android.webkit.WebView
import androidx.core.view.WindowCompat
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
import kotlin.math.abs

/** Actual site's theme controls, without changing its storage or injecting style. */
@RunWith(AndroidJUnit4::class)
class LiveAppearanceTest {
    private val instrument get() = InstrumentationRegistry.getInstrumentation()
    private fun js(s: ActivityScenario<MainActivity>, code: String): String {
        val result=AtomicReference<String>();val done=CountDownLatch(1)
        s.onActivity { it.findViewById<WebView>(R.id.reader_webview).evaluateJavascript(code) { r->result.set(r);done.countDown() } }
        check(done.await(10,TimeUnit.SECONDS));return result.get()
    }
    private fun await(label: String, block: ()->Boolean) {
        val end=System.nanoTime()+TimeUnit.SECONDS.toNanos(50)
        while(System.nanoTime()<end){if(block())return;Thread.sleep(200)}
        fail(label)
    }
    private fun element(selector: String)="Array.from(document.querySelectorAll(${JSONObject.quote(selector)})).find(e=>{var r=e.getBoundingClientRect();return r.width>0&&r.height>0;})"
    private fun paint(s: ActivityScenario<MainActivity>) {
        val done=CountDownLatch(1)
        s.onActivity { it.findViewById<WebView>(R.id.reader_webview).postVisualStateCallback(7L,object:WebView.VisualStateCallback(){override fun onComplete(requestId:Long){done.countDown()}}) }
        check(done.await(10,TimeUnit.SECONDS));instrument.waitForIdleSync();Thread.sleep(250)
    }
    private fun tap(s: ActivityScenario<MainActivity>, selector:String) {
        await("Missing visible control $selector") { js(s,"!!(${element(selector)})")=="true" }
        js(s,"(function(){var e=${element(selector)},r=e.getBoundingClientRect();if(r.top<55||r.bottom>innerHeight)e.scrollIntoView({block:'center',behavior:'instant'});})()")
        paint(s)
        val xy=JSONArray(js(s,"(function(){var r=(${element(selector)}).getBoundingClientRect();return [(r.left+r.right)/2/innerWidth,(r.top+r.bottom)/2/innerHeight];})()"))
        onView(withId(R.id.reader_webview)).perform(GeneralClickAction(Tap.SINGLE,{ v->
            val p=IntArray(2);v.getLocationOnScreen(p)
            floatArrayOf(p[0]+v.width*xy.getDouble(0).toFloat(),p[1]+v.height*xy.getDouble(1).toFloat())
        },Press.FINGER))
    }
    private fun screenshot(s:ActivityScenario<MainActivity>,name:String) {
        paint(s)
        val bitmap=instrument.uiAutomation.takeScreenshot() ?: error("No screenshot")
        val dir=File(instrument.targetContext.getExternalFilesDir(null),"validation/v101").also{it.mkdirs()}
        File(dir,name).outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
        bitmap.recycle()
    }
    private fun open(s:ActivityScenario<MainActivity>,url:String) {
        assertEquals(NavigationPolicy.Destination.INTERNAL,NavigationPolicy.classify(url))
        s.onActivity { it.findViewById<WebView>(R.id.reader_webview).loadUrl(url) }
        await("Page did not open: $url") {
            js(s,"location.href===${JSONObject.quote(url)} && document.readyState==='complete' && !!document.querySelector('#main')")=="true"
        }
        paint(s)
    }
    @Test fun liveThemesHaveContinuousSystemEdges() {
        ActivityScenario.launch(MainActivity::class.java).use { s->
            await("Homepage did not load") { js(s,"document.readyState==='complete' && !!document.querySelector('nav[aria-label=\"底部导航\"]')")=="true" }
            // Navigation gestures have separate LiveSite/LiveActions coverage. Open each
            // page explicitly here so this test isolates theme/edge rendering from route animations.
            open(s,"https://aihot.news/more")
            val selected="button[role=\"radio\"][aria-checked=\"true\"]"
            await("Theme settings missing") { js(s,"!!(${element(selected)})")=="true" }
            val original=JSONArray("["+js(s,"(${element(selected)}).title")+"]").getString(0)
            try {
                for ((label,theme) in listOf("深色" to "dark","浅色" to "light")) {
                    tap(s,"button[role=\"radio\"][title=\"$label\"]")
                    await("Site theme did not change to $theme") { js(s,"document.documentElement.dataset.theme==='$theme'")=="true" }
                    open(s,NavigationPolicy.HOME_URL)
                    await("Homepage theme not synchronized") {
                        var ready=false
                        s.onActivity {
                            val root=it.findViewById<View>(R.id.reader_root)
                            val bg=root.background as ReaderBackdrop
                            val expected=Color.parseColor(if(theme=="dark")"#13191c" else "#faf9f6")
                            // CSS color-mix/oklab goes through 8-bit canvas quantization.
                            // Permit at most 3/255 per channel, never a different theme or surface.
                            ready=abs(Color.red(bg.topColor)-Color.red(expected))<=3 &&
                                abs(Color.green(bg.topColor)-Color.green(expected))<=3 &&
                                abs(Color.blue(bg.topColor)-Color.blue(expected))<=3
                        };ready
                    }
                    s.onActivity {
                        val root=it.findViewById<View>(R.id.reader_root)
                        assertEquals(theme=="light",WindowCompat.getInsetsController(it.window,root).isAppearanceLightStatusBars)
                    }
                    screenshot(s,"home-$theme.png")
                    val article=JSONArray("["+js(s,"document.querySelector('#main a[href^=\"/items/\"]').href")+"]").getString(0)
                    open(s,article)
                    await("Article missing") { js(s,"!!document.querySelector('h1[data-page-title]')")=="true" }
                    Thread.sleep(1000) // Allow the bounded color sync and page transition to settle.
                    screenshot(s,"article-$theme.png")
                    open(s,"https://aihot.news/more")
                }
                tap(s,"button[role=\"radio\"][title=\"跟随系统\"]")
                var systemDark=false
                s.onActivity { systemDark=it.resources.configuration.uiMode and
                    android.content.res.Configuration.UI_MODE_NIGHT_MASK == android.content.res.Configuration.UI_MODE_NIGHT_YES }
                await("Follow-system theme not applied") {
                    js(s,"document.documentElement.dataset.theme==='${if(systemDark) "dark" else "light"}'")=="true"
                }
            } finally {
                if (js(s,"location.pathname !== '/more'")=="true") {
                    open(s,"https://aihot.news/more")
                }
                tap(s,"button[role=\"radio\"][title=\"$original\"]")
            }
        }
    }
}
