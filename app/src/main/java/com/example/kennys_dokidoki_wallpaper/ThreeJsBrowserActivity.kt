package com.example.kennys_dokidoki_wallpaper

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.json.JSONArray
import org.json.JSONTokener
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

enum class DevToolsTab {
    NETWORK,
    CONSOLE
}

enum class DockVisibility {
    EXPANDED,
    COLLAPSED
}

private enum class ChipState {
    ACTIVE,
    INACTIVE
}

class ThreeJsBrowserActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var urlEdit: EditText
    private lateinit var loadProgress: ProgressBar
    private lateinit var btnDevToolsToggle: TextView
    private lateinit var dockPanel: LinearLayout
    private lateinit var tabNetwork: TextView
    private lateinit var tabConsole: TextView
    private lateinit var paneNetwork: LinearLayout
    private lateinit var paneConsole: LinearLayout
    private lateinit var chipCandidates: TextView
    private lateinit var chipAll: TextView
    private lateinit var chipScripts: TextView
    private lateinit var txtEngineBadge: TextView
    private lateinit var txtNetworkEmpty: TextView
    private lateinit var networkRecycler: RecyclerView
    private lateinit var consoleScroll: ScrollView
    private lateinit var txtConsoleLog: TextView
    private lateinit var editConsoleEval: EditText

    private lateinit var inspectorCard: LinearLayout
    private lateinit var inspectorTitle: TextView
    private lateinit var inspectorUrl: TextView
    private lateinit var inspectorDiagnosis: TextView
    private lateinit var inspectorBodyPreview: TextView
    private lateinit var editImportName: EditText
    private lateinit var btnImportJson: TextView

    private lateinit var networkAdapter: NetworkEntryAdapter
    private val networkMap = LinkedHashMap<String, CapturedNetworkItem>()
    private val probedUrls = HashSet<String>()
    private val consoleLines = ArrayDeque<String>()
    private val probeExecutor = Executors.newFixedThreadPool(2)

    private var activeTab = DevToolsTab.NETWORK
    private var dockVisibility = DockVisibility.EXPANDED
    private var activeFilter = NetworkFilterKind.ALL
    private var userAgentString = ""
    private var currentInspectedKey: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = BAR_BG_COLOR
        window.navigationBarColor = DOCK_BG_COLOR
        setContentView(R.layout.activity_threejs_browser)

        bindViews()
        setupToolbarControls()
        setupDevToolsControls()
        setupInspectorControls()
        configureWebView()
        setupBackNavigation()

        val initialUrl = intent?.getStringExtra(EXTRA_INITIAL_URL)
            ?.takeIf { it.isNotBlank() }
            ?: ThreeJsInspectorPolicy.DEFAULT_BROWSER_URL
        urlEdit.setText(initialUrl)
        webView.loadUrl(initialUrl)
    }

    override fun onDestroy() {
        probeExecutor.shutdownNow()
        runCatching {
            CookieManager.getInstance().flush()
            webView.stopLoading()
            webView.removeJavascriptInterface(JS_DEVTOOLS_BRIDGE)
            (webView.parent as? ViewGroup)?.removeView(webView)
            webView.destroy()
        }
        super.onDestroy()
    }

    private fun bindViews() {
        webView = findViewById(R.id.threejs_discovery_webview)
        urlEdit = findViewById(R.id.edit_browser_url)
        loadProgress = findViewById(R.id.browser_load_progress)
        btnDevToolsToggle = findViewById(R.id.btn_browser_devtools_toggle)
        dockPanel = findViewById(R.id.devtools_dock_panel)
        tabNetwork = findViewById(R.id.tab_devtools_network)
        tabConsole = findViewById(R.id.tab_devtools_console)
        paneNetwork = findViewById(R.id.pane_devtools_network)
        paneConsole = findViewById(R.id.pane_devtools_console)
        chipCandidates = findViewById(R.id.chip_filter_candidates)
        chipAll = findViewById(R.id.chip_filter_all)
        chipScripts = findViewById(R.id.chip_filter_scripts)
        txtEngineBadge = findViewById(R.id.txt_three_engine_badge)
        txtNetworkEmpty = findViewById(R.id.txt_network_empty_hint)
        networkRecycler = findViewById(R.id.recycler_devtools_network)
        consoleScroll = findViewById(R.id.scroll_devtools_console)
        txtConsoleLog = findViewById(R.id.txt_devtools_console_log)
        editConsoleEval = findViewById(R.id.edit_console_eval)

        inspectorCard = findViewById(R.id.inspector_overlay_card)
        inspectorTitle = findViewById(R.id.inspector_title)
        inspectorUrl = findViewById(R.id.inspector_url)
        inspectorDiagnosis = findViewById(R.id.inspector_diagnosis)
        inspectorBodyPreview = findViewById(R.id.inspector_body_preview)
        editImportName = findViewById(R.id.edit_inspector_import_name)
        btnImportJson = findViewById(R.id.btn_inspector_import_json)
    }

    private fun setupToolbarControls() {
        val btnClose = findViewById<ImageButton>(R.id.btn_browser_close)
        val btnBack = findViewById<ImageButton>(R.id.btn_browser_back)
        val btnForward = findViewById<ImageButton>(R.id.btn_browser_forward)
        val btnReload = findViewById<ImageButton>(R.id.btn_browser_reload)

        stylePill(urlEdit, "#1B1B24", "#2E2E3D")
        stylePill(btnDevToolsToggle, "#16261E", "#2C5940")

        btnClose.setOnClickListener { finish() }
        btnBack.setOnClickListener {
            if (webView.canGoBack()) {
                webView.goBack()
            }
        }
        btnForward.setOnClickListener {
            if (webView.canGoForward()) {
                webView.goForward()
            }
        }
        btnReload.setOnClickListener { webView.reload() }

        urlEdit.setOnEditorActionListener { _, actionId, event ->
            val isEnter = event != null &&
                event.keyCode == KeyEvent.KEYCODE_ENTER &&
                event.action == KeyEvent.ACTION_DOWN
            if (actionId == EditorInfo.IME_ACTION_GO ||
                actionId == EditorInfo.IME_ACTION_DONE ||
                isEnter
            ) {
                navigateFromOmnibox()
                true
            } else {
                false
            }
        }

        btnDevToolsToggle.setOnClickListener {
            val next = if (dockVisibility == DockVisibility.EXPANDED) {
                DockVisibility.COLLAPSED
            } else {
                DockVisibility.EXPANDED
            }
            applyDockVisibility(next)
        }
    }

    private fun setupDevToolsControls() {
        val btnScanPage = findViewById<TextView>(R.id.btn_devtools_scan_page)
        val btnClear = findViewById<TextView>(R.id.btn_devtools_clear)
        val btnConsoleRun = findViewById<TextView>(R.id.btn_console_run)

        stylePill(btnScanPage, "#162032", "#2C456B")
        stylePill(btnClear, "#1C1C26", "#2E2E3E")
        stylePill(btnConsoleRun, "#162032", "#2C456B")

        networkAdapter = NetworkEntryAdapter { item -> openFileInspector(item) }
        networkRecycler.layoutManager = LinearLayoutManager(this)
        networkRecycler.adapter = networkAdapter

        tabNetwork.setOnClickListener { switchDevToolsTab(DevToolsTab.NETWORK) }
        tabConsole.setOnClickListener { switchDevToolsTab(DevToolsTab.CONSOLE) }

        chipCandidates.setOnClickListener { applyNetworkFilter(NetworkFilterKind.THREE_CANDIDATES) }
        chipAll.setOnClickListener { applyNetworkFilter(NetworkFilterKind.ALL) }
        chipScripts.setOnClickListener { applyNetworkFilter(NetworkFilterKind.SCRIPTS) }

        btnScanPage.setOnClickListener { scanDomForJsonLinks() }
        btnClear.setOnClickListener { clearDevToolsRecords() }

        btnConsoleRun.setOnClickListener { executeConsoleInput() }
        editConsoleEval.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND || actionId == EditorInfo.IME_ACTION_DONE) {
                executeConsoleInput()
                true
            } else {
                false
            }
        }

        switchDevToolsTab(DevToolsTab.NETWORK)
        applyNetworkFilter(NetworkFilterKind.ALL)
    }

    private fun setupInspectorControls() {
        val btnInspectorClose = findViewById<TextView>(R.id.btn_inspector_close)
        stylePill(btnInspectorClose, "#1B1B26", "#323244")
        stylePill(editImportName, "#181924", "#2E3044")
        stylePill(btnImportJson, "#4ADE80", "#4ADE80")

        btnInspectorClose.setOnClickListener { closeFileInspector() }
        btnImportJson.setOnClickListener { importCurrentlyInspectedJson() }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView() {
        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(webView, true)

        webView.setBackgroundColor(Color.parseColor("#111116"))
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            loadsImagesAutomatically = true
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            useWideViewPort = true
            loadWithOverviewMode = true
            builtInZoomControls = true
            displayZoomControls = false
            setSupportMultipleWindows(false)
            userAgentString = this.userAgentString
        }
        this.userAgentString = webView.settings.userAgentString

        webView.addJavascriptInterface(DevToolsJsBridge(), JS_DEVTOOLS_BRIDGE)

        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? {
                val reqUrl = request.url?.toString().orEmpty()
                val method = request.method ?: "GET"
                val accept = request.requestHeaders?.get("Accept")
                if (reqUrl.startsWith("http://") || reqUrl.startsWith("https://")) {
                    runOnUiThread {
                        recordSubresourceRequest(reqUrl, method, accept)
                    }
                }
                return null
            }

            override fun onPageStarted(
                view: WebView,
                url: String?,
                favicon: android.graphics.Bitmap?
            ) {
                super.onPageStarted(view, url, favicon)
                if (!url.isNullOrBlank() && !urlEdit.hasFocus()) {
                    urlEdit.setText(url)
                }
                txtEngineBadge.text = ""
                injectDevToolsHook(view)
            }

            override fun onPageFinished(view: WebView, url: String?) {
                super.onPageFinished(view, url)
                if (!url.isNullOrBlank() && !urlEdit.hasFocus()) {
                    urlEdit.setText(url)
                }
                injectDevToolsHook(view)
                probeDirectJsonNavigation(url)
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                if (newProgress in 1..99) {
                    loadProgress.visibility = View.VISIBLE
                    loadProgress.progress = newProgress
                } else {
                    loadProgress.visibility = View.GONE
                }
            }

            override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                if (consoleMessage != null) {
                    val lvl = consoleMessage.messageLevel()?.name ?: "LOG"
                    val msg = consoleMessage.message().orEmpty()
                    val line = consoleMessage.lineNumber()
                    appendConsoleLine("[$lvl :$line] $msg")
                }
                return true
            }
        }

        webView.setDownloadListener { url, _, _, mimeType, _ ->
            if (!url.isNullOrBlank()) {
                recordSubresourceRequest(url, "GET", mimeType)
                val item = networkMap[normalizeKey(url)]
                if (item != null) {
                    openFileInspector(item)
                }
            }
        }
    }

    private fun setupBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (inspectorCard.visibility == View.VISIBLE) {
                    closeFileInspector()
                    return
                }
                if (webView.canGoBack()) {
                    webView.goBack()
                    return
                }
                finish()
            }
        })
    }

    private fun navigateFromOmnibox() {
        val resolved = ThreeJsInspectorPolicy.resolveBrowserInput(urlEdit.text?.toString())
        urlEdit.setText(resolved)
        urlEdit.clearFocus()
        hideKeyboard(urlEdit)
        webView.loadUrl(resolved)
    }

    private fun hideKeyboard(target: View) {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(target.windowToken, 0)
    }

    private fun applyDockVisibility(state: DockVisibility) {
        dockVisibility = state
        dockPanel.visibility = if (state == DockVisibility.EXPANDED) {
            View.VISIBLE
        } else {
            View.GONE
        }
        refreshDevToolsBadgeCount()
    }

    private fun switchDevToolsTab(tab: DevToolsTab) {
        activeTab = tab
        if (tab == DevToolsTab.NETWORK) {
            paneNetwork.visibility = View.VISIBLE
            paneConsole.visibility = View.GONE
            stylePill(tabNetwork, "#24283B", "#4B5578")
            tabNetwork.setTextColor(Color.WHITE)
            tabConsole.background = null
            tabConsole.setTextColor(Color.parseColor("#888898"))
        } else {
            paneNetwork.visibility = View.GONE
            paneConsole.visibility = View.VISIBLE
            stylePill(tabConsole, "#24283B", "#4B5578")
            tabConsole.setTextColor(Color.WHITE)
            tabNetwork.background = null
            tabNetwork.setTextColor(Color.parseColor("#888898"))
        }
    }

    private fun applyNetworkFilter(filter: NetworkFilterKind) {
        activeFilter = filter
        updateFilterChipStyle(
            chipCandidates,
            if (filter == NetworkFilterKind.THREE_CANDIDATES) ChipState.ACTIVE else ChipState.INACTIVE,
            "#8CE0A8"
        )
        updateFilterChipStyle(
            chipAll,
            if (filter == NetworkFilterKind.ALL) ChipState.ACTIVE else ChipState.INACTIVE,
            "#E2E8F0"
        )
        updateFilterChipStyle(
            chipScripts,
            if (filter == NetworkFilterKind.SCRIPTS) ChipState.ACTIVE else ChipState.INACTIVE,
            "#93C5FD"
        )
        refreshNetworkListUi()
    }

    private fun updateFilterChipStyle(chip: TextView, state: ChipState, activeHex: String) {
        if (state == ChipState.ACTIVE) {
            stylePill(chip, "#202636", activeHex)
            chip.setTextColor(Color.parseColor(activeHex))
        } else {
            stylePill(chip, "#15151E", "#282836")
            chip.setTextColor(Color.parseColor("#8E8EA0"))
        }
    }

    private fun clearDevToolsRecords() {
        if (activeTab == DevToolsTab.NETWORK) {
            networkMap.clear()
            probedUrls.clear()
            refreshNetworkListUi()
        } else {
            consoleLines.clear()
            txtConsoleLog.text = ""
        }
    }

    private fun injectDevToolsHook(view: WebView) {
        view.evaluateJavascript(DEVTOOLS_HOOK_SCRIPT, null)
    }

    private fun probeDirectJsonNavigation(url: String?) {
        if (url.isNullOrBlank()) {
            return
        }
        if (!ThreeJsInspectorPolicy.isLikelyJsonRequest(url, null)) {
            return
        }
        val key = normalizeKey(url)
        recordSubresourceRequest(url, "GET", "application/json")
        probeResourceInBackground(key, url)
    }

    private fun scanDomForJsonLinks() {
        webView.evaluateJavascript(DOM_JSON_SCAN_SCRIPT) { rawResult ->
            val decoded = decodeJsString(rawResult)
            if (decoded.isBlank()) {
                return@evaluateJavascript
            }
            val foundCount = ingestScannedJsonUrls(decoded)
            if (foundCount > 0) {
                Toast.makeText(
                    this,
                    "${foundCount}件の JSON / リソース候補を検出しました",
                    Toast.LENGTH_SHORT
                ).show()
            } else {
                Toast.makeText(
                    this,
                    "このページ内のリンクに新しい .json は見つかりませんでした",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun ingestScannedJsonUrls(jsonArrayText: String): Int {
        val arr = runCatching { JSONArray(jsonArrayText) }.getOrNull() ?: return 0
        var added = 0
        for (i in 0 until arr.length()) {
            val candidateUrl = arr.optString(i).trim()
            if (!candidateUrl.startsWith("http://") && !candidateUrl.startsWith("https://")) {
                continue
            }
            val key = normalizeKey(candidateUrl)
            val existed = networkMap.containsKey(key)
            recordSubresourceRequest(candidateUrl, "SCAN", "application/json")
            if (!existed) {
                added++
            }
        }
        return added
    }

    private fun executeConsoleInput() {
        val expr = editConsoleEval.text?.toString()?.trim().orEmpty()
        if (expr.isEmpty()) {
            return
        }
        editConsoleEval.setText("")
        appendConsoleLine("> $expr")
        webView.evaluateJavascript(
            "(function(){ try { var r = eval(${quoteForJs(expr)}); return typeof r === 'object' ? JSON.stringify(r) : String(r); } catch(e) { return 'Error: ' + e.message; } })();"
        ) { result ->
            appendConsoleLine("<- ${decodeJsString(result)}")
        }
    }

    private fun appendConsoleLine(line: String) {
        if (consoleLines.size >= MAX_CONSOLE_LINES) {
            consoleLines.removeFirst()
        }
        consoleLines.addLast(line)
        txtConsoleLog.text = consoleLines.joinToString("\n")
        consoleScroll.post { consoleScroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun recordSubresourceRequest(
        url: String,
        method: String,
        mimeHint: String?
    ) {
        if (isFinishing || isDestroyed) {
            return
        }
        val key = normalizeKey(url)
        val existing = networkMap[key]
        if (existing == null) {
            if (networkMap.size >= MAX_NETWORK_ENTRIES) {
                val oldestKey = networkMap.keys.firstOrNull()
                if (oldestKey != null) {
                    networkMap.remove(oldestKey)
                }
            }
            val initialBadge = ThreeJsInspectorPolicy.classifyNetworkItem(url, mimeHint, null)
            networkMap[key] = CapturedNetworkItem(
                key = key,
                url = url,
                method = method,
                statusCode = 0,
                mimeType = mimeHint.orEmpty(),
                bodyText = null,
                sizeBytes = 0,
                inspection = null,
                badge = initialBadge
            )
            refreshNetworkListUi()
        }

        if (ThreeJsInspectorPolicy.isLikelyJsonRequest(url, mimeHint)) {
            probeResourceInBackground(key, url)
        }
    }

    private fun recordCapturedBody(
        url: String,
        method: String,
        statusCode: Int,
        mimeType: String,
        bodyText: String
    ) {
        if (isFinishing || isDestroyed || url.isBlank()) {
            return
        }
        val key = normalizeKey(url)
        probedUrls.add(key)
        val inspection = ThreeJsInspectorPolicy.inspectJsonText(bodyText, url)
        val badge = ThreeJsInspectorPolicy.classifyNetworkItem(url, mimeType, inspection)
        val updated = CapturedNetworkItem(
            key = key,
            url = url,
            method = method.ifBlank { "GET" },
            statusCode = statusCode,
            mimeType = mimeType,
            bodyText = bodyText,
            sizeBytes = bodyText.toByteArray(Charsets.UTF_8).size,
            inspection = inspection,
            badge = badge
        )
        networkMap[key] = updated
        refreshNetworkListUi()

        if (currentInspectedKey == key && inspectorCard.visibility == View.VISIBLE) {
            populateInspectorUi(updated)
        }
    }

    private fun probeResourceInBackground(key: String, targetUrl: String) {
        if (!probedUrls.add(key)) {
            return
        }
        val cookie = runCatching { CookieManager.getInstance().getCookie(targetUrl) }.getOrNull()
        val ua = userAgentString
        val referer = runCatching { webView.url }.getOrNull()

        probeExecutor.execute {
            val fetched = downloadTextResource(targetUrl, ua, cookie, referer) ?: return@execute
            runOnUiThread {
                if (!isFinishing && !isDestroyed) {
                    recordCapturedBody(
                        url = targetUrl,
                        method = "GET",
                        statusCode = fetched.statusCode,
                        mimeType = fetched.mimeType,
                        bodyText = fetched.bodyText
                    )
                }
            }
        }
    }

    private fun openFileInspector(item: CapturedNetworkItem) {
        currentInspectedKey = item.key
        inspectorCard.visibility = View.VISIBLE
        populateInspectorUi(item)

        if (item.bodyText == null) {
            inspectorDiagnosis.text = "ファイルを取得して解析しています..."
            inspectorDiagnosis.setTextColor(Color.parseColor("#93C5FD"))
            inspectorBodyPreview.text = "Loading ${item.url} ..."
            probedUrls.remove(item.key)
            probeResourceInBackground(item.key, item.url)
        }
    }

    private fun populateInspectorUi(item: CapturedNetworkItem) {
        val shortName = item.url.substringBefore('?').substringAfterLast('/').ifBlank { item.url }
        inspectorTitle.text = shortName
        val sizeKb = (item.sizeBytes / 1024).coerceAtLeast(if (item.sizeBytes > 0) 1 else 0)
        val metaLine = buildString {
            append(item.url)
            if (item.mimeType.isNotBlank()) {
                append("\nMIME: ").append(item.mimeType)
            }
            if (item.sizeBytes > 0) {
                append(" · ").append(sizeKb).append(" KB")
            }
            if (item.statusCode > 0) {
                append(" · HTTP ").append(item.statusCode)
            }
        }
        inspectorUrl.text = metaLine

        val inspection = item.inspection
        val body = item.bodyText
        if (body == null) {
            setImportControlsEnabled(
                inspection = null,
                suggestedName = ThreeJsInspectorPolicy.suggestTitleFromUrl(item.url)
            )
            return
        }

        if (inspection != null && inspection.kind.isThreeJsPlayable()) {
            inspectorDiagnosis.text = "★ ${inspection.summaryText}"
            inspectorDiagnosis.setTextColor(Color.parseColor("#8CE0A8"))
        } else if (inspection != null && inspection.kind == ThreeJsJsonKind.GENERIC_JSON) {
            inspectorDiagnosis.text = "JSON: ${inspection.summaryText}"
            inspectorDiagnosis.setTextColor(Color.parseColor("#FCD34D"))
        } else {
            val mimeDesc = item.mimeType.ifBlank { "text/plain" }
            inspectorDiagnosis.text = "非 JSON ファイル ($mimeDesc)"
            inspectorDiagnosis.setTextColor(Color.parseColor("#94A3B8"))
        }

        inspectorBodyPreview.text = ThreeJsInspectorPolicy.formatInspectorPreview(body)
        setImportControlsEnabled(
            inspection = inspection,
            suggestedName = inspection?.suggestedTitle
                ?.ifEmpty { ThreeJsInspectorPolicy.suggestTitleFromUrl(item.url) }
                .orEmpty()
        )
    }

    private fun setImportControlsEnabled(
        inspection: JsonInspectionResult?,
        suggestedName: String
    ) {
        val canImport = inspection != null && inspection.kind.isValidJson()
        editImportName.isEnabled = canImport
        btnImportJson.isEnabled = canImport
        btnImportJson.alpha = if (canImport) 1.0f else 0.35f
        if (canImport && editImportName.text.isNullOrBlank()) {
            editImportName.setText(suggestedName)
        }
    }

    private fun closeFileInspector() {
        currentInspectedKey = null
        inspectorCard.visibility = View.GONE
        hideKeyboard(editImportName)
    }

    private fun importCurrentlyInspectedJson() {
        val key = currentInspectedKey ?: return
        val item = networkMap[key] ?: return
        val rawBody = item.bodyText ?: return
        val preferredName = editImportName.text?.toString().orEmpty()

        val result = ThreeJsWorkStore.addUserWork(this, preferredName, rawBody)
        result.onSuccess { added ->
            notifyWallpaperChanged()
            Toast.makeText(
                this,
                "「${added.title}」を three.js 管理へインポートしました",
                Toast.LENGTH_LONG
            ).show()
            closeFileInspector()
        }.onFailure { err ->
            Toast.makeText(
                this,
                err.message ?: "JSON のインポートに失敗しました",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun notifyWallpaperChanged() {
        val intent = Intent(ACTION_WALLPAPER_CHANGED).apply {
            setPackage(packageName)
        }
        sendBroadcast(intent)
    }

    private fun refreshNetworkListUi() {
        val allItems = networkMap.values.toList()
        val sorted = allItems.sortedWith(
            compareBy<CapturedNetworkItem> { it.badge.priorityRank() }
                .thenBy { it.url }
        )
        val filtered = sorted.filter { item ->
            ThreeJsInspectorPolicy.matchesFilter(item.badge, activeFilter)
        }

        networkAdapter.submitItems(filtered)
        txtNetworkEmpty.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
        refreshDevToolsBadgeCount()
    }

    private fun refreshDevToolsBadgeCount() {
        val candidateCount = networkMap.values.count { item ->
            ThreeJsInspectorPolicy.matchesFilter(item.badge, NetworkFilterKind.THREE_CANDIDATES)
        }
        val arrow = if (dockVisibility == DockVisibility.EXPANDED) "▼" else "▲"
        btnDevToolsToggle.text = "DevTools ($candidateCount) $arrow"
        chipCandidates.text = "★ Three.js / JSON ($candidateCount)"
        chipAll.text = "すべて (${networkMap.size})"
    }

    private fun downloadTextResource(
        targetUrl: String,
        userAgent: String,
        cookie: String?,
        referer: String?
    ): DownloadedResource? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(targetUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = HTTP_TIMEOUT_MS
                readTimeout = HTTP_TIMEOUT_MS
                instanceFollowRedirects = true
                if (userAgent.isNotBlank()) {
                    setRequestProperty("User-Agent", userAgent)
                }
                if (!cookie.isNullOrBlank()) {
                    setRequestProperty("Cookie", cookie)
                }
                if (!referer.isNullOrBlank()) {
                    setRequestProperty("Referer", referer)
                }
                setRequestProperty("Accept", "application/json, text/plain, */*")
            }
            val code = conn.responseCode
            val contentType = conn.contentType.orEmpty()
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            if (stream == null) {
                return null
            }
            val bytes = stream.use { input -> readBoundedBytes(input, MAX_INSPECT_BYTES) }
            val text = bytes.toString(Charsets.UTF_8)
            DownloadedResource(statusCode = code, mimeType = contentType, bodyText = text)
        } catch (_: Throwable) {
            null
        } finally {
            conn?.disconnect()
        }
    }

    private fun readBoundedBytes(
        input: java.io.InputStream,
        maxBytes: Int
    ): ByteArray {
        val buffer = ByteArray(8192)
        val out = ByteArrayOutputStream()
        var total = 0
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) {
                break
            }
            val allowed = (maxBytes - total).coerceAtMost(read)
            if (allowed > 0) {
                out.write(buffer, 0, allowed)
                total += allowed
            }
            if (total >= maxBytes) {
                break
            }
        }
        return out.toByteArray()
    }

    private fun normalizeKey(url: String): String {
        return url.substringBefore('#').trim()
    }

    private fun stylePill(view: View, fillHex: String, strokeHex: String) {
        val density = resources.displayMetrics.density
        view.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 6f * density
            setColor(Color.parseColor(fillHex))
            setStroke((1f * density).toInt().coerceAtLeast(1), Color.parseColor(strokeHex))
        }
    }

    private fun decodeJsString(rawJs: String?): String {
        if (rawJs.isNullOrBlank() || rawJs == "null" || rawJs == "undefined") {
            return ""
        }
        return runCatching { JSONTokener(rawJs).nextValue()?.toString().orEmpty() }
            .getOrDefault(rawJs)
    }

    private fun quoteForJs(raw: String): String {
        val escaped = raw
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "")
        return "\"$escaped\""
    }

    private inner class DevToolsJsBridge {
        @JavascriptInterface
        fun onJsResponseCaptured(
            url: String?,
            method: String?,
            statusCode: Int,
            contentType: String?,
            bodyText: String?
        ) {
            val safeUrl = url?.trim().orEmpty()
            val safeBody = bodyText.orEmpty()
            if (safeUrl.isEmpty() || safeBody.isEmpty()) {
                return
            }
            runOnUiThread {
                recordCapturedBody(
                    url = safeUrl,
                    method = method.orEmpty(),
                    statusCode = statusCode,
                    mimeType = contentType.orEmpty(),
                    bodyText = safeBody
                )
            }
        }

        @JavascriptInterface
        fun onThreeDetected(revision: String?) {
            val rev = revision?.trim().orEmpty()
            if (rev.isEmpty()) {
                return
            }
            runOnUiThread {
                txtEngineBadge.text = "THREE r$rev"
            }
        }
    }

    private inner class NetworkEntryAdapter(
        private val onItemClick: (CapturedNetworkItem) -> Unit
    ) : RecyclerView.Adapter<NetworkEntryAdapter.RowHolder>() {

        private val rows = mutableListOf<CapturedNetworkItem>()

        fun submitItems(newItems: List<CapturedNetworkItem>) {
            rows.clear()
            rows.addAll(newItems)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RowHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_threejs_network_entry, parent, false)
            return RowHolder(view)
        }

        override fun getItemCount(): Int {
            return rows.size
        }

        override fun onBindViewHolder(holder: RowHolder, position: Int) {
            val item = rows[position]
            bindBadge(holder.badge, item.badge)

            val leaf = item.url.substringBefore('?').substringAfterLast('/').ifBlank { item.url }
            holder.name.text = leaf

            val summary = item.inspection?.summaryText
                ?.takeIf { item.badge != NetworkResourceBadge.OTHER && item.badge != NetworkResourceBadge.SCRIPT }
                ?: item.url
            holder.sub.text = summary

            holder.inspectHint.text = if (item.inspection?.kind?.isThreeJsPlayable() == true) {
                "Import ›"
            } else {
                "確認 ›"
            }
            holder.inspectHint.setTextColor(
                if (item.inspection?.kind?.isThreeJsPlayable() == true) {
                    Color.parseColor("#8CE0A8")
                } else {
                    Color.parseColor("#9AA4B8")
                }
            )
            holder.itemView.setOnClickListener { onItemClick(item) }
        }

        private fun bindBadge(badgeView: TextView, badge: NetworkResourceBadge) {
            val (label, bgHex, fgHex) = when (badge) {
                NetworkResourceBadge.THREE_APP -> Triple("★ APP.JSON", "#133323", "#4ADE80")
                NetworkResourceBadge.THREE_3D -> Triple("◆ 3D.JSON", "#122B3B", "#38BDF8")
                NetworkResourceBadge.JSON -> Triple("JSON", "#332910", "#FBBF24")
                NetworkResourceBadge.SCRIPT -> Triple("JS", "#1E2235", "#93C5FD")
                NetworkResourceBadge.OTHER -> Triple("FILE", "#1D1D26", "#94A3B8")
            }
            badgeView.text = label
            badgeView.setTextColor(Color.parseColor(fgHex))
            stylePill(badgeView, bgHex, fgHex)
        }

        inner class RowHolder(view: View) : RecyclerView.ViewHolder(view) {
            val badge: TextView = view.findViewById(R.id.net_entry_badge)
            val name: TextView = view.findViewById(R.id.net_entry_name)
            val sub: TextView = view.findViewById(R.id.net_entry_sub)
            val inspectHint: TextView = view.findViewById(R.id.net_entry_inspect_hint)
        }
    }

    private data class CapturedNetworkItem(
        val key: String,
        val url: String,
        val method: String,
        val statusCode: Int,
        val mimeType: String,
        val bodyText: String?,
        val sizeBytes: Int,
        val inspection: JsonInspectionResult?,
        val badge: NetworkResourceBadge
    )

    private data class DownloadedResource(
        val statusCode: Int,
        val mimeType: String,
        val bodyText: String
    )

    companion object {
        const val EXTRA_INITIAL_URL = "extra_initial_url"
        private const val ACTION_WALLPAPER_CHANGED =
            "com.example.kennys_dokidoki_wallpaper.ACTION_WALLPAPER_CHANGED"
        private const val JS_DEVTOOLS_BRIDGE = "AndroidDevToolsBridge"
        private const val BAR_BG_COLOR = 0xFF121218.toInt()
        private const val DOCK_BG_COLOR = 0xFF0E0E14.toInt()
        private const val MAX_NETWORK_ENTRIES = 250
        private const val MAX_CONSOLE_LINES = 200
        private const val HTTP_TIMEOUT_MS = 12_000
        private const val MAX_INSPECT_BYTES = 12 * 1024 * 1024

        private val DEVTOOLS_HOOK_SCRIPT = """
            (function() {
              function installOnWindow(win) {
                try {
                  if (!win || win.__threeDevToolsInstalled) return;
                  win.__threeDevToolsInstalled = true;
                  var bridge = window.AndroidDevToolsBridge;
                  if (!bridge) return;

                  function looksCapturable(url, ct) {
                    var u = String(url || '').split('?')[0].toLowerCase();
                    var c = String(ct || '').toLowerCase();
                    return u.endsWith('.json') || c.indexOf('json') !== -1 || c.indexOf('text/plain') !== -1;
                  }

                  if (typeof win.fetch === 'function') {
                    var origFetch = win.fetch;
                    win.fetch = function(input, init) {
                      var reqUrl = (typeof input === 'string') ? input : (input && input.url ? input.url : '');
                      var method = (init && init.method) ? init.method : 'GET';
                      return origFetch.apply(this, arguments).then(function(res) {
                        try {
                          var fullUrl = res.url || new URL(reqUrl, win.location.href).href;
                          var ct = (res.headers && res.headers.get('content-type')) || '';
                          if (looksCapturable(fullUrl, ct)) {
                            res.clone().text().then(function(txt) {
                              if (txt && txt.length <= 12582912) {
                                bridge.onJsResponseCaptured(fullUrl, method, res.status || 200, ct, txt);
                              }
                            }).catch(function(){});
                          }
                        } catch (_e) {}
                        return res;
                      });
                    };
                  }

                  if (win.XMLHttpRequest && win.XMLHttpRequest.prototype) {
                    var origOpen = win.XMLHttpRequest.prototype.open;
                    var origSend = win.XMLHttpRequest.prototype.send;
                    win.XMLHttpRequest.prototype.open = function(method, url) {
                      this.__dtMethod = method || 'GET';
                      try {
                        this.__dtUrl = new URL(String(url || ''), win.location.href).href;
                      } catch (_e) {
                        this.__dtUrl = String(url || '');
                      }
                      return origOpen.apply(this, arguments);
                    };
                    win.XMLHttpRequest.prototype.send = function() {
                      this.addEventListener('load', function() {
                        try {
                          var ct = this.getResponseHeader('content-type') || '';
                          var rType = this.responseType || '';
                          if ((rType === '' || rType === 'text' || rType === 'json') &&
                              looksCapturable(this.__dtUrl, ct)) {
                            var txt = '';
                            if (rType === 'json' && this.response) {
                              txt = JSON.stringify(this.response);
                            } else if (typeof this.responseText === 'string') {
                              txt = this.responseText;
                            }
                            if (txt && txt.length <= 12582912) {
                              bridge.onJsResponseCaptured(this.__dtUrl, this.__dtMethod || 'GET', this.status || 200, ct, txt);
                            }
                          }
                        } catch (_e) {}
                      });
                      return origSend.apply(this, arguments);
                    };
                  }

                  setTimeout(function() {
                    try {
                      if (win.THREE && win.THREE.REVISION) {
                        bridge.onThreeDetected(String(win.THREE.REVISION));
                      }
                    } catch (_e) {}
                  }, 600);
                } catch (_err) {}
              }

              installOnWindow(window);
              try {
                var frames = document.querySelectorAll('iframe');
                for (var i = 0; i < frames.length; i++) {
                  try {
                    if (frames[i].contentWindow) installOnWindow(frames[i].contentWindow);
                  } catch (_fe) {}
                }
              } catch (_e2) {}
            })();
        """.trimIndent()

        private val DOM_JSON_SCAN_SCRIPT = """
            (function() {
              var found = [];
              var seen = {};
              function pushUrl(raw, base) {
                if (!raw) return;
                try {
                  var abs = new URL(raw, base || window.location.href).href;
                  var clean = abs.split('#')[0].split('?')[0].toLowerCase();
                  if (clean.endsWith('.json') && !seen[abs]) {
                    seen[abs] = true;
                    found.push(abs);
                  }
                } catch (_e) {}
              }
              function scanDoc(doc, win) {
                try {
                  var nodes = doc.querySelectorAll('a[href], link[href], script[src], [data-src], [data-url]');
                  for (var i = 0; i < nodes.length; i++) {
                    var el = nodes[i];
                    pushUrl(el.getAttribute('href') || el.getAttribute('src') || el.getAttribute('data-src') || el.getAttribute('data-url'), doc.baseURI);
                  }
                  if (win && win.performance && typeof win.performance.getEntriesByType === 'function') {
                    var entries = win.performance.getEntriesByType('resource');
                    for (var j = 0; j < entries.length; j++) {
                      pushUrl(entries[j].name, doc.baseURI);
                    }
                  }
                  var scripts = doc.querySelectorAll('script:not([src])');
                  var regex = /["']([^"'\s]+\.json(?:\?[^"'\s]*)?)["']/gi;
                  for (var k = 0; k < scripts.length; k++) {
                    var code = scripts[k].textContent || '';
                    if (code.length > 300000) continue;
                    var m;
                    while ((m = regex.exec(code)) !== null) {
                      pushUrl(m[1], doc.baseURI);
                    }
                  }
                } catch (_e) {}
              }
              scanDoc(document, window);
              try {
                var iframes = document.querySelectorAll('iframe');
                for (var f = 0; f < iframes.length; f++) {
                  try {
                    if (iframes[f].contentDocument) {
                      scanDoc(iframes[f].contentDocument, iframes[f].contentWindow);
                    }
                  } catch (_ie) {}
                }
              } catch (_e2) {}
              return JSON.stringify(found);
            })();
        """.trimIndent()
    }
}
