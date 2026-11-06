package app.diurna.ui.web

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.*
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import java.io.ByteArrayInputStream

/** Bundled content only. No legacy JS interface or network fallback. */
@SuppressLint("SetJavaScriptEnabled")
fun createWeekWebView(context: Context, bridge: WeekBridge, onFailure: () -> Unit): WebView {
    check(WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER))
    val loader = WebViewAssetLoader.Builder().addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context)).build()
    return WebView(context).apply {
        settings.javaScriptEnabled = true
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.domStorageEnabled = false
        settings.setGeolocationEnabled(false)
        settings.javaScriptCanOpenWindowsAutomatically = false
        settings.setSupportMultipleWindows(false)
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        settings.blockNetworkLoads = true
        CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
        setDownloadListener { _, _, _, _, _ -> }
        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
                if (WeekBridge.allowedAsset(request.url.toString()) && request.method == "GET" &&
                    (!request.isForMainFrame || request.url.toString() == WeekBridge.URL)) {
                    loader.shouldInterceptRequest(request.url)?.let { response ->
                        response.responseHeaders = mapOf("Content-Security-Policy" to
                            "default-src 'none'; script-src 'self'; style-src 'self'; connect-src 'none'; frame-src 'none'; object-src 'none'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'",
                            "X-Content-Type-Options" to "nosniff")
                        return response
                    }
                }
                return WebResourceResponse("text/plain", "UTF-8", 403, "Blocked", emptyMap(), ByteArrayInputStream(byteArrayOf()))
            }
            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) { onFailure() }
            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) { onFailure() }
            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean { onFailure(); return true }
        }
        WebViewCompat.addWebMessageListener(this, "diurna", setOf(WeekBridge.ORIGIN)) { _, message, origin, main, reply ->
            bridge.route(message.data ?: "", origin.toString(), main)?.let { reply.postMessage(it) }
        }
    }
}
