package io.github.ethanbird.senseime.brain.runtime

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import io.github.ethanbird.senseime.brain.api.AgentBrowserAction
import io.github.ethanbird.senseime.brain.api.AgentToolArguments
import java.util.LinkedHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import org.json.JSONTokener

/** One process-local WebView pool shared by Agent tools and the Agent Hub browser tab. */
class AgentBrowserRuntime private constructor(context: Context) {
    private val applicationContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val tabs = LinkedHashMap<String, BrowserTab>(8, 0.75f, true)

    fun executeForTool(
        sessionId: String,
        arguments: AgentToolArguments.BrowserUse,
    ): String {
        check(Looper.myLooper() != Looper.getMainLooper()) {
            "Browser tool execution must run off the WebView thread"
        }
        val tab = onMain { obtainTab(sessionId) }
        return tab.executionLock.withLock {
            when (arguments.action) {
                AgentBrowserAction.NAVIGATE -> {
                    navigate(tab, checkNotNull(arguments.url))
                    actionResult(arguments.action, snapshot(tab, arguments.maxChars))
                }
                AgentBrowserAction.SNAPSHOT ->
                    actionResult(arguments.action, snapshot(tab, arguments.maxChars))
                AgentBrowserAction.CLICK -> {
                    val ref = checkNotNull(arguments.ref)
                    val clicked = evaluateBoolean(
                        tab,
                        "(() => { const e=document.querySelector(" +
                            "'[data-sense-ref=\"$ref\"]'); " +
                            "if(!e)return false; e.scrollIntoView({block:'center'}); " +
                            "e.click(); return true; })()",
                    )
                    if (!clicked) error("Browser element ref is stale")
                    waitForDomSettle()
                    actionResult(arguments.action, snapshot(tab, arguments.maxChars))
                }
                AgentBrowserAction.TYPE -> {
                    val text = checkNotNull(arguments.text)
                    val script = buildString {
                        append("(() => { const e=document.querySelector('[data-sense-ref=\"")
                        append(checkNotNull(arguments.ref))
                        append("\"]'); if(!e)return false; e.focus(); const v=")
                        append(org.json.JSONObject.quote(text))
                        append("; const setter=Object.getOwnPropertyDescriptor(")
                        append("e instanceof HTMLTextAreaElement ? HTMLTextAreaElement.prototype : ")
                        append("HTMLInputElement.prototype,'value')?.set; ")
                        append("if(setter)setter.call(e,v);else e.value=v;")
                        append("e.dispatchEvent(new Event('input',{bubbles:true}));")
                        append("e.dispatchEvent(new Event('change',{bubbles:true}));")
                        if (arguments.submit) {
                            append("const f=e.closest('form'); if(f){")
                            append("if(f.requestSubmit)f.requestSubmit();else f.submit();}")
                            append("else e.dispatchEvent(new KeyboardEvent('keydown',")
                            append("{key:'Enter',code:'Enter',bubbles:true}));")
                        }
                        append("return true; })()")
                    }
                    if (!evaluateBoolean(tab, script)) error("Browser element ref is stale")
                    waitForDomSettle()
                    actionResult(arguments.action, snapshot(tab, arguments.maxChars))
                }
                AgentBrowserAction.BACK -> {
                    navigateAction(tab) {
                        if (tab.webView.canGoBack()) {
                            tab.webView.goBack()
                            true
                        } else {
                            false
                        }
                    }
                    actionResult(arguments.action, snapshot(tab, arguments.maxChars))
                }
                AgentBrowserAction.FORWARD -> {
                    navigateAction(tab) {
                        if (tab.webView.canGoForward()) {
                            tab.webView.goForward()
                            true
                        } else {
                            false
                        }
                    }
                    actionResult(arguments.action, snapshot(tab, arguments.maxChars))
                }
                AgentBrowserAction.RELOAD -> {
                    navigateAction(tab) {
                        tab.webView.reload()
                        true
                    }
                    actionResult(arguments.action, snapshot(tab, arguments.maxChars))
                }
            }
        }
    }

    /** Attaches the exact Agent-owned WebView so manual takeover keeps cookies and DOM state. */
    fun attach(sessionId: String, container: ViewGroup): WebView {
        check(Looper.myLooper() == Looper.getMainLooper())
        val tab = obtainTab(sessionId)
        (tab.webView.parent as? ViewGroup)?.removeView(tab.webView)
        container.removeAllViews()
        container.addView(
            tab.webView,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        tab.attached = true
        return tab.webView
    }

    fun detach(sessionId: String, container: ViewGroup) {
        check(Looper.myLooper() == Looper.getMainLooper())
        val tab = tabs[sessionId] ?: return
        if (tab.webView.parent === container) container.removeView(tab.webView)
        tab.attached = false
    }

    fun currentUrl(sessionId: String): String = onMain {
        tabs[sessionId]?.webView?.url.orEmpty()
    }

    private fun navigate(tab: BrowserTab, url: String) {
        val latch = CountDownLatch(1)
        tab.navigation.set(latch)
        onMain { tab.webView.loadUrl(url) }
        latch.await(NAVIGATION_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        tab.navigation.compareAndSet(latch, null)
    }

    private fun navigateAction(tab: BrowserTab, action: () -> Boolean) {
        val latch = CountDownLatch(1)
        tab.navigation.set(latch)
        if (!onMain(action)) {
            tab.navigation.compareAndSet(latch, null)
            return
        }
        latch.await(NAVIGATION_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        tab.navigation.compareAndSet(latch, null)
    }

    private fun waitForDomSettle() {
        Thread.sleep(DOM_SETTLE_MS)
    }

    private fun snapshot(tab: BrowserTab, maxChars: Int): String {
        val bodyLimit = minOf(maxChars, MAX_COMPACT_BODY_CHARS)
        val script = """
            (() => {
              const clean = value => (value || '').replace(/\s+/g, ' ').trim();
              const nodes = Array.from(document.querySelectorAll(
                'a[href],button,input:not([type=hidden]),textarea,select,[role=button],[onclick]'
              )).filter(e => {
                const r=e.getBoundingClientRect();
                return r.width > 0 && r.height > 0;
              }).slice(0, 8);
              const elements = nodes.map((e, i) => {
                const ref=String(i + 1);
                e.setAttribute('data-sense-ref', ref);
                return {
                  ref:Number(ref), tag:e.tagName.toLowerCase(),
                  type:(e.getAttribute('type') || '').slice(0,24),
                  text:clean(e.innerText || e.getAttribute('aria-label') ||
                    e.getAttribute('placeholder') || e.getAttribute('title')).slice(0,64),
                  href:(e.href || '').slice(0,160)
                };
              });
              const bodyText=clean(document.body ? document.body.innerText : '');
              return JSON.stringify({
                url:location.href,
                title:(document.title || '').slice(0,256),
                text:bodyText.slice(0, $bodyLimit),
                truncated:bodyText.length > $bodyLimit,
                elements
              });
            })()
        """.trimIndent()
        return evaluateString(tab, script).ifBlank {
            "{\"url\":\"\",\"title\":\"\",\"text\":\"\",\"elements\":[]}"
        }
    }

    private fun evaluateBoolean(tab: BrowserTab, script: String): Boolean =
        evaluateRaw(tab, script) == "true"

    private fun evaluateString(tab: BrowserTab, script: String): String {
        val raw = evaluateRaw(tab, script)
        if (raw == "null" || raw.isBlank()) return ""
        return runCatching { JSONTokener(raw).nextValue() as? String }
            .getOrNull()
            ?: raw
    }

    private fun evaluateRaw(tab: BrowserTab, script: String): String {
        val latch = CountDownLatch(1)
        val result = AtomicReference("null")
        mainHandler.post {
            tab.webView.evaluateJavascript(script) { value ->
                result.set(value ?: "null")
                latch.countDown()
            }
        }
        check(latch.await(JAVASCRIPT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            "Browser JavaScript timed out"
        }
        return result.get()
    }

    private fun actionResult(action: AgentBrowserAction, page: String): String =
        "{\"action\":\"${action.wireValue}\",\"page\":$page}"

    @SuppressLint("SetJavaScriptEnabled")
    private fun obtainTab(sessionId: String): BrowserTab {
        check(Looper.myLooper() == Looper.getMainLooper())
        tabs[sessionId]?.let { return it }
        if (tabs.size >= MAX_TABS) {
            val victim = tabs.entries.firstOrNull { !it.value.attached }
            if (victim != null) {
                tabs.remove(victim.key)
                victim.value.webView.destroy()
            } else {
                error("Every browser tab is currently attached")
            }
        }
        val tab = BrowserTab(WebView(applicationContext))
        tab.webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            mediaPlaybackRequiresUserGesture = true
            setSupportMultipleWindows(false)
            userAgentString = "$userAgentString Sense-Agent/0.4.9"
        }
        CookieManager.getInstance().setAcceptCookie(true)
        tab.webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                tab.navigation.getAndSet(null)?.countDown()
                super.onPageFinished(view, url)
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?,
            ) {
                if (request?.isForMainFrame == true) {
                    tab.navigation.getAndSet(null)?.countDown()
                }
                super.onReceivedError(view, request, error)
            }
        }
        tabs[sessionId] = tab
        return tab
    }

    private fun <T> onMain(block: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        val latch = CountDownLatch(1)
        val result = AtomicReference<Result<T>?>(null)
        mainHandler.post {
            result.set(runCatching(block))
            latch.countDown()
        }
        check(latch.await(MAIN_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            "Browser main-thread operation timed out"
        }
        return checkNotNull(result.get()).getOrThrow()
    }

    private class BrowserTab(val webView: WebView) {
        val executionLock = ReentrantLock()
        val navigation = AtomicReference<CountDownLatch?>()
        var attached: Boolean = false
    }

    companion object {
        @Volatile
        private var instance: AgentBrowserRuntime? = null
        @Volatile
        private var suffixConfigured = false

        fun get(context: Context): AgentBrowserRuntime = instance ?: synchronized(this) {
            instance ?: run {
                configureDataDirectory()
                AgentBrowserRuntime(context).also { instance = it }
            }
        }

        private fun configureDataDirectory() {
            if (suffixConfigured) return
            val processName = Application.getProcessName()
            if (processName.contains(':')) {
                runCatching { WebView.setDataDirectorySuffix("sense-agent") }
            }
            suffixConfigured = true
        }

        private const val MAX_TABS = 3
        private const val MAIN_TIMEOUT_MS = 15_000L
        private const val NAVIGATION_TIMEOUT_MS = 12_000L
        private const val JAVASCRIPT_TIMEOUT_MS = 8_000L
        private const val DOM_SETTLE_MS = 650L
        private const val MAX_COMPACT_BODY_CHARS = 5_000
    }
}
