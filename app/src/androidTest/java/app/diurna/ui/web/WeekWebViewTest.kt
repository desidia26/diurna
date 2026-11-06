package app.diurna.ui.web

import android.content.Intent
import android.webkit.WebView
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.diurna.MainActivity
import app.diurna.debug.Store
import app.diurna.ui.WeekUi
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class WeekWebViewTest {
    @Test fun bundledSvelteLoadsAndQueriesNativeWeek() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val ready = CountDownLatch(1)
        val queried = CountDownLatch(1)
        val failures = AtomicInteger()
        var view: WebView? = null
        val date = LocalDate.of(2026, 11, 2)
        try {
            instrumentation.runOnMainSync {
                val bridge = WeekBridge({ queried.countDown(); WeekUi(date, date, emptyList()) }, Store(), { ready.countDown() })
                view = createWeekWebView(activity, bridge) { failures.incrementAndGet() }
                activity.setContentView(view)
                view!!.loadUrl(WeekBridge.URL)
            }
            assertTrue("Svelte ready", ready.await(20, TimeUnit.SECONDS))
            assertTrue("week.get", queried.await(20, TimeUnit.SECONDS))
            val checked = CountDownLatch(1)
            var body = ""
            instrumentation.runOnMainSync {
                view!!.evaluateJavascript("document.body.innerText") { body = it; checked.countDown() }
            }
            assertTrue(checked.await(5, TimeUnit.SECONDS))
            assertTrue(body, body.contains("2026-11-02"))
            assertEquals(0, failures.get())
        } finally { instrumentation.runOnMainSync { view?.destroy(); activity.finish() } }
    }

    @Test fun remoteOriginNeverReceivesInjectedBridge() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        var view: WebView? = null
        val checked = CountDownLatch(1)
        var result = ""
        try {
            instrumentation.runOnMainSync {
                view = createWeekWebView(activity, WeekBridge({ error("must not query") }, Store())) {}
                activity.setContentView(view)
                view!!.webViewClient = object : android.webkit.WebViewClient() {
                    override fun onPageFinished(v: WebView, url: String) {
                        v.evaluateJavascript("typeof window.diurna") { result = it; checked.countDown() }
                    }
                }
                view!!.loadDataWithBaseURL("https://untrusted.invalid/", "<html><body>Untrusted</body></html>", "text/html", "UTF-8", null)
            }
            assertTrue(checked.await(20, TimeUnit.SECONDS))
            assertEquals("\"undefined\"", result)
        } finally { instrumentation.runOnMainSync { view?.destroy(); activity.finish() } }
    }
}
