package dev.personal.aihotreader

import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.ParcelFileDescriptor
import android.os.Build
import android.view.View
import android.webkit.WebView
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.swipeDown
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Network toggles are limited to an emulator and always restored, including after assertion failures. */
@RunWith(AndroidJUnit4::class)
class OfflineInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val connectivity = instrumentation.targetContext.getSystemService(ConnectivityManager::class.java)
    private val body = "<!doctype html><meta name='viewport' content='width=device-width,initial-scale=1'><p>Retained reader content</p><script>window.offlineFixtureReady=true;</script>"

    @Test fun loadedContentAndRefreshSurviveRealNetworkLoss() {
        assumeTrue("Network tests only change an emulator", shell("getprop ro.kernel.qemu").trim() == "1")
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val url = "https://aihot.news/__offline_reading_probe__"
            scenario.onActivity { activity ->
                val web = activity.findViewById<WebView>(R.id.reader_webview)
                web.stopLoading()
                HttpsFixture.install(web, url, body)
                web.loadUrl(url)
            }
            await("Fixture did not load") { js(scenario, "window.offlineFixtureReady === true") == "true" }
            withNetworkDisabled {
                await("Offline banner did not appear") {
                    var visible = false
                    scenario.onActivity { visible = it.findViewById<View>(R.id.network_banner).visibility == View.VISIBLE }
                    visible
                }
                scenario.onActivity { activity ->
                    assertNotEquals(View.VISIBLE, activity.findViewById<View>(R.id.error_panel).visibility)
                }
                onView(withId(R.id.reader_refresh)).perform(swipeDown())
                assertTrue(js(scenario, "document.body.innerText").contains("Retained reader content"))
                scenario.onActivity { activity ->
                    assertEquals(url, activity.findViewById<WebView>(R.id.reader_webview).url)
                    assertNotEquals(View.VISIBLE, activity.findViewById<View>(R.id.error_panel).visibility)
                }
            }
        }
    }

    @Test fun offlineNavigationShowsNetworkErrorAndRetryKeepsOriginalAddress() {
        assumeTrue("Network tests only change an emulator", shell("getprop ro.kernel.qemu").trim() == "1")
        withNetworkDisabled { restoreNetwork ->
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                val target = "https://aihot.news/__offline_retry_probe__?run=" + System.nanoTime()
                scenario.onActivity { activity ->
                    activity.findViewById<WebView>(R.id.reader_webview).loadUrl(target)
                }
                await("Uncached offline navigation did not show a main-page error") {
                    var failed = false
                    scenario.onActivity { failed = it.findViewById<View>(R.id.error_panel).visibility == View.VISIBLE }
                    failed
                }
                scenario.onActivity { activity ->
                    assertEquals(activity.getString(R.string.error_offline),
                        activity.findViewById<TextView>(R.id.error_message).text.toString())
                }
                restoreNetwork()
                // The retry response is deterministic; the initial failure above was a real network failure.
                scenario.onActivity { activity ->
                    val web = activity.findViewById<WebView>(R.id.reader_webview)
                    HttpsFixture.install(web, target, body)
                    activity.findViewById<View>(R.id.action_retry).performClick()
                }
                await("Retry did not request the original address") {
                    js(scenario, "window.offlineFixtureReady === true") == "true"
                }
                scenario.onActivity { activity ->
                    assertEquals(target, activity.findViewById<WebView>(R.id.reader_webview).url)
                    assertNotEquals(View.VISIBLE, activity.findViewById<View>(R.id.error_panel).visibility)
                }
            }
        }
    }

    private fun withNetworkDisabled(block: (() -> Unit) -> Unit) {
        assumeTrue("Start with an online emulator", online())
        val wifi = shell("settings get global wifi_on").trim() != "0"
        val mobile = shell("settings get global mobile_data").trim() != "0"
        val restore = {
            shell("svc wifi " + if (wifi) "enable" else "disable")
            shell("svc data " + if (mobile) "enable" else "disable")
            await("Emulator network did not reconnect") { online() }
        }
        try {
            shell("svc wifi disable")
            shell("svc data disable")
            await("Emulator network did not disconnect") { !online() }
            block(restore)
        } finally {
            restore()
        }
    }

    private fun online(): Boolean = connectivity.activeNetwork?.let {
        connectivity.getNetworkCapabilities(it)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    } == true

    private fun shell(command: String): String {
        // This API 26 userdebug image does not grant CHANGE_WIFI_STATE to UID 2000.
        // Only emulator-only network toggles use its built-in root; the production app gets no new permission.
        val actual = if (Build.VERSION.SDK_INT == 26 && command.startsWith("svc ")) "su 0 $command" else command
        return ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(actual))
            .bufferedReader().use { it.readText() }
    }

    private fun js(scenario: ActivityScenario<MainActivity>, expression: String): String {
        val answer = AtomicReference<String>()
        val completed = CountDownLatch(1)
        scenario.onActivity { activity ->
            activity.findViewById<WebView>(R.id.reader_webview).evaluateJavascript(expression) {
                answer.set(it)
                completed.countDown()
            }
        }
        check(completed.await(10, TimeUnit.SECONDS)) { "WebView did not respond" }
        return answer.get()
    }

    private fun await(message: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(25)
        while (System.nanoTime() < deadline) {
            if (condition()) return
            Thread.sleep(100)
        }
        assertTrue(message, condition())
    }
}
