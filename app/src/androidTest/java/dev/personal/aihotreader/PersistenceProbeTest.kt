package dev.personal.aihotreader

import android.webkit.WebView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Run with persistencePhase=seed, then force-stop/upgrade the signed target APK, then verify. */
@RunWith(AndroidJUnit4::class)
class PersistenceProbeTest {
    @Test fun retainedAcrossProcessRestartAndSignedUpgrade() {
        val phase = InstrumentationRegistry.getArguments().getString("persistencePhase")
        assumeTrue("Explicit persistence probe only", phase == "seed" || phase == "verify" || phase == "cleanup")
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.findViewById<WebView>(R.id.reader_webview).apply {
                    stopLoading()
                    HttpsFixture.install(this, "https://aihot.news/__persistence_probe__",
                        "<!doctype html><title>Persistence probe</title><script>window.probeReady=true;</script>")
                    loadUrl("https://aihot.news/__persistence_probe__")
                }
            }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
            while (evaluate(scenario, "window.probeReady === true") != "true" && System.nanoTime() < deadline) {
                Thread.sleep(100)
            }
            var context = ""
            scenario.onActivity { context = HttpsFixture.describe(it.findViewById(R.id.reader_webview)) }
            assertEquals("Persistence HTTPS fixture failed; phase=$phase; $context",
                "true", evaluate(scenario, "window.probeReady === true"))
            when (phase) {
                "seed" -> {
                    assertEquals("\"retained-v1\"", evaluate(scenario,
                        "localStorage.setItem('__aihot_upgrade_probe__','retained-v1'); localStorage.getItem('__aihot_upgrade_probe__');"))
                    // Chromium persists localStorage asynchronously; allow the background commit to finish.
                    Thread.sleep(2000)
                }
                "verify" -> assertEquals("\"retained-v1\"", evaluate(scenario,
                    "localStorage.getItem('__aihot_upgrade_probe__');"))
                "cleanup" -> evaluate(scenario, "localStorage.removeItem('__aihot_upgrade_probe__');")
            }
        }
    }

    private fun evaluate(scenario: ActivityScenario<MainActivity>, code: String): String {
        val value = AtomicReference<String>()
        val latch = CountDownLatch(1)
        scenario.onActivity { activity ->
            activity.findViewById<WebView>(R.id.reader_webview).evaluateJavascript(code) {
                value.set(it)
                latch.countDown()
            }
        }
        check(latch.await(10, TimeUnit.SECONDS)) { "WebView did not respond" }
        return value.get()
    }
}
