package com.example.kennys_dokidoki_wallpaper

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.ProgressDialog
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.regex.Pattern

class JanitorBrowserActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar
    private lateinit var btnImport: Button
    private lateinit var tvTitle: TextView
    private lateinit var btnWebBack: TextView
    private lateinit var btnWebForward: TextView

    private var currentCharacterId: String? = null // Janitor AI Character UUID
    private var popupDialog: android.app.Dialog? = null
    private var progressDialog: ProgressDialog? = null

    companion object {
        private const val TAG = "JanitorBrowserActivity"
        // Pattern to match: janitorai.com/characters/UUID_slug or janitorai.com/characters/UUID
        private val JANITOR_CHARACTER_PATTERN = Pattern.compile(
            "https?://(?:www\\.)?janitorai\\.com/characters/([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})",
            Pattern.CASE_INSENSITIVE
        )
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Load DataManager and TagManager data first
        DataManager.loadData(this)
        TagManager.loadTags(this)
        BackupManager.initializeLastKnownCounts(this)

        // 1. Create Layout programmatically (cute theme to match Noah-chan!)
        val mainLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.parseColor("#131314"))
        }

        // Top Toolbar
        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dpToPx(8), dpToPx(8), dpToPx(8), dpToPx(8))
            setBackgroundColor(Color.parseColor("#1F1F1F"))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        // Close / Back button
        val btnClose = TextView(this).apply {
            text = "❌"
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setPadding(dpToPx(8), dpToPx(8), dpToPx(8), dpToPx(8))
            layoutParams = LinearLayout.LayoutParams(dpToPx(40), dpToPx(40))
            setOnClickListener { finish() }
            contentDescription = "閉じる"
        }
        toolbar.addView(btnClose)

        // WebView Back button
        btnWebBack = TextView(this).apply {
            text = "◀"
            gravity = Gravity.CENTER
            setTextColor(Color.GRAY) // Disabled initially
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setPadding(dpToPx(8), dpToPx(8), dpToPx(8), dpToPx(8))
            layoutParams = LinearLayout.LayoutParams(dpToPx(40), dpToPx(40))
            isEnabled = false
            setOnClickListener { if (webView.canGoBack()) webView.goBack() }
            contentDescription = "戻る"
        }
        toolbar.addView(btnWebBack)

        // WebView Forward button
        btnWebForward = TextView(this).apply {
            text = "▶"
            gravity = Gravity.CENTER
            setTextColor(Color.GRAY) // Disabled initially
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setPadding(dpToPx(8), dpToPx(8), dpToPx(8), dpToPx(8))
            layoutParams = LinearLayout.LayoutParams(dpToPx(40), dpToPx(40))
            isEnabled = false
            setOnClickListener { if (webView.canGoForward()) webView.goForward() }
            contentDescription = "進む"
        }
        toolbar.addView(btnWebForward)

        // Home button
        val btnHome = TextView(this).apply {
            text = "🏠"
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setPadding(dpToPx(8), dpToPx(8), dpToPx(8), dpToPx(8))
            layoutParams = LinearLayout.LayoutParams(dpToPx(40), dpToPx(40))
            setOnClickListener { webView.loadUrl("https://janitorai.com/") }
            contentDescription = "ホーム"
        }
        toolbar.addView(btnHome)

        // Title View
        tvTitle = TextView(this).apply {
            text = "Janitor AI インポート"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER_VERTICAL
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(dpToPx(8), 0, dpToPx(8), 0)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f)
        }
        toolbar.addView(tvTitle)

        // Refresh button
        val btnRefresh = TextView(this).apply {
            text = "🔄"
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setPadding(dpToPx(8), dpToPx(8), dpToPx(8), dpToPx(8))
            layoutParams = LinearLayout.LayoutParams(dpToPx(40), dpToPx(40))
            setOnClickListener { webView.reload() }
            contentDescription = "更新"
        }
        toolbar.addView(btnRefresh)

        mainLayout.addView(toolbar)

        // Horizontal Progress Bar
        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dpToPx(4)
            )
        }
        mainLayout.addView(progressBar)

        // FrameLayout for WebView and floating Import Button
        val frameLayout = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1.0f
            )
        }

        // WebView
        webView = WebView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        frameLayout.addView(webView)

        // Import Overlay Button (super adorable!)
        btnImport = Button(this).apply {
            text = "💞 この神キャラをお迎えするもん！✨"
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            setBackgroundColor(Color.parseColor("#C58AF9")) // Playful cute pink!
            setPadding(dpToPx(24), dpToPx(16), dpToPx(24), dpToPx(16))
            gravity = Gravity.CENTER
            visibility = View.GONE // Hidden initially until character page is detected
            
            val params = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                bottomMargin = dpToPx(24)
            }
            layoutParams = params
            setOnClickListener {
                startImportFlow()
            }
        }
        frameLayout.addView(btnImport)

        mainLayout.addView(frameLayout)
        setContentView(mainLayout)

        // 2. Configure WebView
        configureWebView()

        // 3. Load initial page
        webView.loadUrl("https://janitorai.com/")
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            useWideViewPort = true
            loadWithOverviewMode = true
            cacheMode = WebSettings.LOAD_DEFAULT
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            userAgentString = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = true
        }

        // Enable cookies and third-party cookies for login
        android.webkit.CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }

        webView.addJavascriptInterface(JanitorJSInterface(), "AndroidJanitor")

        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                progressBar.visibility = View.VISIBLE
                progressBar.progress = 0
                checkUrlAndToggleImport(url)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                progressBar.visibility = View.GONE
                updateNavigationButtons()
                checkUrlAndToggleImport(url)
            }

            override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                super.doUpdateVisitedHistory(view, url, isReload)
                updateNavigationButtons()
                checkUrlAndToggleImport(url)
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                super.onProgressChanged(view, newProgress)
                progressBar.progress = newProgress
                if (newProgress >= 100) {
                    progressBar.visibility = View.GONE
                } else {
                    progressBar.visibility = View.VISIBLE
                }
            }

            override fun onCreateWindow(
                view: WebView?,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: android.os.Message?
            ): Boolean {
                val context = this@JanitorBrowserActivity
                val newWebView = WebView(context).apply {
                    settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        databaseEnabled = true
                        useWideViewPort = true
                        loadWithOverviewMode = true
                        userAgentString = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
                    }
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView?, request: android.webkit.WebResourceRequest?): Boolean {
                            return false
                        }
                    }
                    webChromeClient = object : WebChromeClient() {
                        override fun onCloseWindow(window: WebView?) {
                            popupDialog?.dismiss()
                            popupDialog = null
                        }
                    }
                }

                android.webkit.CookieManager.getInstance().apply {
                    setAcceptCookie(true)
                    setAcceptThirdPartyCookies(newWebView, true)
                }

                val dialog = android.app.Dialog(context, android.R.style.Theme_Black_NoTitleBar_Fullscreen).apply {
                    setContentView(newWebView)
                    setOnDismissListener {
                        newWebView.destroy()
                    }
                }
                dialog.show()
                popupDialog = dialog

                val transport = resultMsg?.obj as? WebView.WebViewTransport
                if (transport != null) {
                    transport.webView = newWebView
                    resultMsg.sendToTarget()
                    return true
                }
                return false
            }

            override fun onCloseWindow(window: WebView?) {
                super.onCloseWindow(window)
                popupDialog?.dismiss()
                popupDialog = null
            }

            override fun onReceivedTitle(view: WebView?, title: String?) {
                super.onReceivedTitle(view, title)
                if (!title.isNullOrBlank()) {
                    if (title.startsWith("http://") || title.startsWith("https://") || title.contains("/") || title.contains("accounts.google")) {
                        if (title.contains("google", ignoreCase = true)) {
                            tvTitle.text = "Google ログイン"
                        } else {
                            tvTitle.text = "Janitor AI 検索・インポート"
                        }
                    } else if (!title.contains("janitor", ignoreCase = true)) {
                        tvTitle.text = title
                    } else {
                        tvTitle.text = "Janitor AI 検索・インポート"
                    }
                } else {
                    tvTitle.text = "Janitor AI 検索・インポート"
                }
            }
        }
    }

    private fun updateNavigationButtons() {
        if (webView.canGoBack()) {
            btnWebBack.setTextColor(Color.WHITE)
            btnWebBack.isEnabled = true
        } else {
            btnWebBack.setTextColor(Color.GRAY)
            btnWebBack.isEnabled = false
        }

        if (webView.canGoForward()) {
            btnWebForward.setTextColor(Color.WHITE)
            btnWebForward.isEnabled = true
        } else {
            btnWebForward.setTextColor(Color.GRAY)
            btnWebForward.isEnabled = false
        }
    }

    private fun checkUrlAndToggleImport(url: String?) {
        if (url == null) {
            btnImport.visibility = View.GONE
            currentCharacterId = null
            return
        }

        val matcher = JANITOR_CHARACTER_PATTERN.matcher(url)
        if (matcher.find()) {
            val uuid = matcher.group(1) ?: ""
            if (uuid.isNotEmpty()) {
                currentCharacterId = uuid
                btnImport.visibility = View.VISIBLE
                return
            }
        }

        btnImport.visibility = View.GONE
        currentCharacterId = null
    }

    private fun startImportFlow() {
        val characterId = currentCharacterId ?: return

        progressDialog = ProgressDialog(this).apply {
            setMessage("💞 Janitor AIから神キャラのデータを召喚中だもん！\n少しだけ待っててね〜♡")
            setCancelable(false)
            show()
        }

        val js = """
            (async function() {
                try {
                    const charId = '$characterId';
                    
                    // Helper to deeply search an object for our character data
                    function deepSearchProps(obj, id, depth = 0) {
                        if (depth > 12) return null;
                        if (!obj || typeof obj !== 'object') return null;
                        
                        // If this object represents our character
                        if (obj.name && (obj.id === id || (obj.id && obj.id.toLowerCase() === id.toLowerCase()) || obj.avatar || obj.description || obj.personality)) {
                            // Verify it has some defining character traits to be considered a public definition
                            if (obj.first_mes || obj.first_message || obj.personality || obj.description || obj.scenario) {
                                return {
                                    name: obj.name,
                                    avatar: obj.avatar || '',
                                    description: obj.description || '',
                                    personality: obj.personality || '',
                                    first_mes: obj.first_mes || obj.first_message || '',
                                    scenario: obj.scenario || '',
                                    system_prompt: obj.system_prompt || '',
                                    is_definition_private: obj.is_definition_private || obj.definition_private || false,
                                    source: 'Memory State (Public)'
                                };
                            }
                        }

                        if (Array.isArray(obj)) {
                            for (const item of obj) {
                                const found = deepSearchProps(item, id, depth + 1);
                                if (found) return found;
                            }
                        } else {
                            for (const key in obj) {
                                if (Object.prototype.hasOwnProperty.call(obj, key)) {
                                    if (key.startsWith('_') || key === 'window' || key === 'document' || key === 'next' || key === 'router') continue;
                                    try {
                                        const found = deepSearchProps(obj[key], id, depth + 1);
                                        if (found) return found;
                                    } catch(e) {}
                                }
                            }
                        }
                        return null;
                    }

                    // Helper to search for ANY character object even with minimal data
                    function deepSearchPrivate(obj, id, depth = 0) {
                        if (depth > 12) return null;
                        if (!obj || typeof obj !== 'object') return null;
                        
                        if (obj.name && (obj.id === id || (obj.id && obj.id.toLowerCase() === id.toLowerCase()) || obj.avatar)) {
                            return {
                                name: obj.name,
                                avatar: obj.avatar || '',
                                description: obj.description || '',
                                personality: obj.personality || '',
                                first_mes: obj.first_mes || obj.first_message || '',
                                scenario: obj.scenario || '',
                                system_prompt: obj.system_prompt || '',
                                is_definition_private: true,
                                source: 'Memory State (Private)'
                            };
                        }

                        if (Array.isArray(obj)) {
                            for (const item of obj) {
                                const found = deepSearchPrivate(item, id, depth + 1);
                                if (found) return found;
                            }
                        } else {
                            for (const key in obj) {
                                if (Object.prototype.hasOwnProperty.call(obj, key)) {
                                    if (key.startsWith('_') || key === 'window' || key === 'document' || key === 'next' || key === 'router') continue;
                                    try {
                                        const found = deepSearchPrivate(obj[key], id, depth + 1);
                                        if (found) return found;
                                    } catch(e) {}
                                }
                            }
                        }
                        return null;
                    }

                    let characterInfo = null;
                    let logs = [];

                    // 1. Try window.__NEXT_DATA__
                    try {
                        if (window.__NEXT_DATA__) {
                            logs.push('Found __NEXT_DATA__');
                            characterInfo = deepSearchProps(window.__NEXT_DATA__, charId);
                            if (!characterInfo) {
                                characterInfo = deepSearchPrivate(window.__NEXT_DATA__, charId);
                            }
                        }
                    } catch(e) {
                        logs.push('Error checking __NEXT_DATA__: ' + e.message);
                    }

                    // 2. Try parsing __NEXT_DATA__ script tag directly
                    if (!characterInfo) {
                        try {
                            const nextDataScript = document.getElementById('__NEXT_DATA__');
                            if (nextDataScript) {
                                logs.push('Found __NEXT_DATA__ script tag');
                                const data = JSON.parse(nextDataScript.textContent);
                                characterInfo = deepSearchProps(data, charId);
                                if (!characterInfo) {
                                    characterInfo = deepSearchPrivate(data, charId);
                                }
                            }
                        } catch(e) {
                            logs.push('Error checking __NEXT_DATA__ tag: ' + e.message);
                        }
                    }

                    // 3. Try traversing document scripts
                    if (!characterInfo) {
                        try {
                            const scripts = document.querySelectorAll('script');
                            logs.push('Checking ' + scripts.length + ' script elements...');
                            for (const script of scripts) {
                                if (script.type === 'application/json' || script.textContent.includes('definition_private') || script.textContent.includes('first_mes')) {
                                    try {
                                        const data = JSON.parse(script.textContent);
                                        const found = deepSearchProps(data, charId) || deepSearchPrivate(data, charId);
                                        if (found) {
                                            characterInfo = found;
                                            logs.push('Found match in JSON script element');
                                            break;
                                        }
                                    } catch(e) {}
                                }
                            }
                        } catch(e) {
                            logs.push('Error checking scripts: ' + e.message);
                        }
                    }

                    // 4. Try global window search
                    if (!characterInfo) {
                        try {
                            logs.push('Checking global window scope keys...');
                            for (const key in window) {
                                if (key.startsWith('__') || key === 'next' || key === 'webpackChunk_N_E' || key === 'window' || key === 'document') continue;
                                try {
                                    const val = window[key];
                                    if (val && typeof val === 'object') {
                                        const found = deepSearchProps(val, charId) || deepSearchPrivate(val, charId);
                                        if (found) {
                                            characterInfo = found;
                                            logs.push('Found match in window.' + key);
                                            break;
                                        }
                                    }
                                } catch(e) {}
                            }
                        } catch(e) {}
                    }

                    // 5. Try fetch endpoints as a fallback
                    if (!characterInfo) {
                        const apiEndpoints = [
                            '/api/chat/characters/' + charId,
                            '/api/characters/' + charId,
                            '/api/character/' + charId,
                            '/api/chat/character/' + charId,
                            '/api/c/' + charId,
                            'https://janitorai.com/api/chat/characters/' + charId,
                            'https://janitorai.com/api/characters/' + charId
                        ];
                        
                        logs.push('Probing API endpoints...');
                        for (const endpoint of apiEndpoints) {
                            try {
                                const res = await fetch(endpoint);
                                logs.push(endpoint + ' -> HTTP ' + res.status);
                                if (res.ok) {
                                    const text = await res.text();
                                    const parsed = JSON.parse(text);
                                    const characterJson = parsed.character || parsed.data || parsed;
                                    if (characterJson && characterJson.name) {
                                        characterInfo = {
                                            name: characterJson.name,
                                            avatar: characterJson.avatar || '',
                                            description: characterJson.description || '',
                                            personality: characterJson.personality || '',
                                            first_mes: characterJson.first_mes || characterJson.first_message || '',
                                            scenario: characterJson.scenario || '',
                                            system_prompt: characterJson.system_prompt || '',
                                            is_definition_private: characterJson.is_definition_private || characterJson.definition_private || false,
                                            source: 'API Fetch (' + endpoint + ')'
                                        };
                                        logs.push('Successfully fetched from API');
                                        break;
                                    }
                                }
                            } catch(e) {
                                logs.push(endpoint + ' -> Error: ' + e.message);
                            }
                        }
                    }

                    // 6. Last resort fallback: Scraping the page DOM directly!
                    if (!characterInfo) {
                        logs.push('Attempting DOM scraping as last resort...');
                        try {
                            const nameEl = document.querySelector('h1, h2, .char-name, [class*="CharacterName"], [class*="name"]');
                            const name = nameEl ? nameEl.textContent.trim() : '';
                            if (name) {
                                const imgEl = document.querySelector('img[src*="character-avatars"], img[src*="user-assets"], [class*="Avatar"] img, [class*="avatar"] img');
                                const avatar = imgEl ? imgEl.src : '';
                                characterInfo = {
                                    name: name,
                                    avatar: avatar,
                                    description: '',
                                    personality: '',
                                    first_mes: '',
                                    scenario: '',
                                    system_prompt: '',
                                    is_definition_private: true,
                                    source: 'DOM Scrape'
                                };
                                logs.push('Successfully scraped basic DOM info');
                            }
                        } catch(e) {
                            logs.push('DOM Scrape failed: ' + e.message);
                        }
                    }

                    if (!characterInfo) {
                        throw new Error('Could not retrieve character data. Diagnostics:\n' + logs.join('\n'));
                    }

                    // Download avatar to pass as base64
                    let base64Avatar = '';
                    let avatarUrl = characterInfo.avatar || '';
                    if (avatarUrl) {
                        if (!avatarUrl.startsWith('http://') && !avatarUrl.startsWith('https://')) {
                            avatarUrl = 'https://user-assets.janitorai.com/character-avatars/' + charId + '/' + avatarUrl;
                        }
                        logs.push('Downloading avatar from ' + avatarUrl);
                        try {
                            const imgResponse = await fetch(avatarUrl);
                            if (imgResponse.ok) {
                                const blob = await imgResponse.blob();
                                base64Avatar = await new Promise((resolve, reject) => {
                                    const reader = new FileReader();
                                    reader.onloadend = () => resolve(reader.result.split(',')[1]);
                                    reader.onerror = () => reject(new Error('FileReader failed'));
                                    reader.readAsDataURL(blob);
                                });
                                logs.push('Avatar download succeeded');
                            } else {
                                logs.push('Avatar download HTTP ' + imgResponse.status);
                            }
                        } catch (imgError) {
                            logs.push('Avatar download error: ' + imgError.message);
                        }
                    }

                    // Pass back the result
                    const resultJson = JSON.stringify({
                        character: characterInfo,
                        logs: logs
                    });
                    
                    window.AndroidJanitor.onImportSuccess(resultJson, base64Avatar);
                } catch (e) {
                    window.AndroidJanitor.onImportError(e.message);
                }
            })();
        """.trimIndent()

        webView.evaluateJavascript(js, null)
    }

    private fun handleImportSuccess(jsonString: String, base64Avatar: String) {
        progressDialog?.dismiss()
        progressDialog = null

        try {
            val rootJson = JSONObject(jsonString)
            val characterJson = rootJson.optJSONObject("character") 
                ?: rootJson.optJSONObject("data") 
                ?: rootJson

            val isPrivate = characterJson.optBoolean("is_definition_private", false) ||
                    characterJson.optBoolean("definition_private", false) ||
                    characterJson.optBoolean("is_private_definition", false) ||
                    characterJson.optString("is_definition_private", "").equals("true", ignoreCase = true) ||
                    characterJson.optString("definition_private", "").equals("true", ignoreCase = true) ||
                    (characterJson.optString("description", "").trim().isEmpty() && 
                     characterJson.optString("personality", "").trim().isEmpty() &&
                     characterJson.optString("scenario", "").trim().isEmpty())

            val name = characterJson.optString("name", "Unknown Name")

            val avatarBytes = if (base64Avatar.isNotEmpty()) {
                try {
                    android.util.Base64.decode(base64Avatar, android.util.Base64.DEFAULT)
                } catch (e: Exception) {
                    null
                }
            } else {
                null
            }

            if (isPrivate) {
                AlertDialog.Builder(this@JanitorBrowserActivity, R.style.Theme_Kennys_dokidoki_wallpaper)
                    .setTitle("⚠️ キャラ情報が非公開だもん！")
                    .setMessage("ねえねえ、ケニーちゃん！『$name』ちゃんの定義情報は【非公開(Private)】になってるみたいだよ〜！😭\n\nインポートしても、説明やセリフの設定は空っぽになっちゃうかも…💦\nそれでも、名前と画像だけでもお迎え(インポート)しちゃう？🥺💞")
                    .setNegativeButton("やめとく", null)
                    .setPositiveButton("お迎えする！") { _, _ ->
                        val finalBytes = avatarBytes ?: createDummyPng()
                        proceedToEditor(characterJson, finalBytes)
                    }
                    .show()
            } else {
                val finalBytes = avatarBytes ?: createDummyPng()
                proceedToEditor(characterJson, finalBytes)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing character details", e)
            AlertDialog.Builder(this@JanitorBrowserActivity, R.style.Theme_Kennys_dokidoki_wallpaper)
                .setTitle("パース失敗…💦")
                .setMessage("お迎えしたデータ形式の読み込みに失敗しちゃった。エラー: ${e.message}")
                .setPositiveButton("OK", null)
                .show()
        }
    }

    private fun handleImportError(errorMessage: String) {
        progressDialog?.dismiss()
        progressDialog = null

        Log.e(TAG, "Import error: $errorMessage")
        AlertDialog.Builder(this@JanitorBrowserActivity, R.style.Theme_Kennys_dokidoki_wallpaper)
            .setTitle("うぅ…召喚失敗…💦")
            .setMessage("キャラクター情報の召喚に失敗しちゃったもん。😭\n\nエラー詳細:\n$errorMessage\n\n💡ログイン状態や、キャラクターページが完全に読み込まれているか確認してね。")
            .setPositiveButton("OK", null)
            .show()
    }

    private fun proceedToEditor(characterJson: JSONObject, avatarBytes: ByteArray) {
        try {
            val name = characterJson.optString("name", "Unknown Name")
            val description = characterJson.optString("description", "")
            val personality = characterJson.optString("personality", "")
            val firstMes = characterJson.optString("first_mes", characterJson.optString("first_message", ""))
            val scenario = characterJson.optString("scenario", "")
            val systemPrompt = characterJson.optString("system_prompt", "")

            val charData = CharacterData(name, description, personality, firstMes, scenario, systemPrompt)

            CharacterImportEditor.showImportEditorDialog(this@JanitorBrowserActivity, charData) { editedCharData ->
                val formattedText = TavernCardParser.formatDescriptionText(editedCharData)

                // Save PNG bytes locally
                val fileName = "janitor_char_${editedCharData.name.replace("\\s+".toRegex(), "_")}_${System.currentTimeMillis()}.png"
                val file = File(filesDir, fileName)
                file.writeBytes(avatarBytes)
                val localUri = Uri.fromFile(file)

                val newEntry = ImageEntry(
                    uri = localUri,
                    description = formattedText
                )
                
                // Add tag "janitor"
                TagManager.addTag(this@JanitorBrowserActivity, "janitor")
                newEntry.tags.add("janitor")

                if (DataManager.allImages.none { it.uri.toString() == localUri.toString() }) {
                    DataManager.allImages.add(newEntry)
                    DataManager.saveData(this@JanitorBrowserActivity)

                    AlertDialog.Builder(this@JanitorBrowserActivity, R.style.Theme_Kennys_dokidoki_wallpaper)
                        .setTitle("💞 インポート大成功だよ〜！")
                        .setMessage("きゃーー！！『${editedCharData.name}』ちゃんのインポートが完了したよ〜！尊い、尊すぎるよケニーちゃん！😭👼✨\n\n最初のセリフ:\n${editedCharData.firstMes.ifEmpty { "（未設定）" }}\n\nさっそくこのキャラとおしゃべり(チャット)して、ドキドキを体験しちゃう？ねえ、チャットしよ！🥺💞")
                        .setPositiveButton("チャットを開始！") { _, _ ->
                            val intent = Intent(this@JanitorBrowserActivity, ChatOverlayActivity::class.java).apply {
                                putExtra("IMAGE_URI", localUri.toString())
                            }
                            startActivity(intent)
                            finish()
                        }
                        .setNegativeButton("ブラウザを閉じる") { _, _ ->
                            finish()
                        }
                        .setNeutralButton("ブラウザを続ける", null)
                        .show()
                } else {
                    Toast.makeText(this@JanitorBrowserActivity, "このキャラクターは既に登録されているわよ。", Toast.LENGTH_SHORT).show()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error preparing editor dialog", e)
            Toast.makeText(this, "ダイアログ準備中にエラーが発生したもん: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    inner class JanitorJSInterface {
        @android.webkit.JavascriptInterface
        fun onImportSuccess(jsonString: String, base64Avatar: String) {
            runOnUiThread {
                handleImportSuccess(jsonString, base64Avatar)
            }
        }

        @android.webkit.JavascriptInterface
        fun onImportError(errorMessage: String) {
            runOnUiThread {
                handleImportError(errorMessage)
            }
        }
    }

    private suspend fun downloadBytesWithGet(urlString: String): ByteArray? = withContext(Dispatchers.IO) {
        var currentUrl = urlString
        var redirectCount = 0
        val maxRedirects = 5
        var result: ByteArray? = null

        while (redirectCount < maxRedirects) {
            try {
                val url = URL(currentUrl)
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")
                connection.connectTimeout = 15000
                connection.readTimeout = 15000
                connection.instanceFollowRedirects = false

                val responseCode = connection.responseCode
                Log.d(TAG, "Download URL: $currentUrl, Response Code: $responseCode")

                if (responseCode == HttpURLConnection.HTTP_OK) {
                    result = connection.inputStream.use { it.readBytes() }
                    break
                } else if (responseCode == HttpURLConnection.HTTP_MOVED_TEMP ||
                           responseCode == HttpURLConnection.HTTP_MOVED_PERM ||
                           responseCode == 307 || responseCode == 308) {
                     val newUrl = connection.getHeaderField("Location")
                     if (newUrl != null) {
                         currentUrl = if (newUrl.startsWith("http")) {
                             newUrl
                         } else {
                             val baseUri = java.net.URI(currentUrl)
                             baseUri.resolve(newUrl).toString()
                         }
                         redirectCount++
                     } else {
                         break
                     }
                } else {
                     Log.e(TAG, "HTTP response error $responseCode for url: $currentUrl")
                     break
                }
            } catch (e: Exception) {
                Log.e(TAG, "Exception downloading $currentUrl", e)
                break
            }
        }
        result
    }

    private fun createDummyPng(): ByteArray {
        // A minimal valid transparent 1x1 PNG bytes fallback
        return byteArrayOf(
            0x89.toByte(), 0x50.toByte(), 0x4E.toByte(), 0x47.toByte(), 0x0D.toByte(), 0x0A.toByte(), 0x1A.toByte(), 0x0A.toByte(),
            0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x0D.toByte(), 0x49.toByte(), 0x48.toByte(), 0x44.toByte(), 0x52.toByte(),
            0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x01.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x01.toByte(),
            0x08.toByte(), 0x06.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x1F.toByte(), 0x15.toByte(), 0xC4.toByte(),
            0x89.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x0D.toByte(), 0x49.toByte(), 0x44.toByte(), 0x41.toByte(),
            0x54.toByte(), 0x78.toByte(), 0xDA.toByte(), 0x63.toByte(), 0x60.toByte(), 0x18.toByte(), 0x05.toByte(), 0xA3.toByte(),
            0x60.toByte(), 0x14.toByte(), 0x8C.toByte(), 0x82.toByte(), 0x51.toByte(), 0x0D.toByte(), 0x00.toByte(), 0x00.toByte(),
            0x02.toByte(), 0x07.toByte(), 0x01.toByte(), 0x02.toByte(), 0x95.toByte(), 0x01.toByte(), 0xDD.toByte(), 0x0C.toByte(),
            0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x49.toByte(), 0x45.toByte(), 0x4E.toByte(), 0x44.toByte(),
            0xAE.toByte(), 0x42.toByte(), 0x60.toByte(), 0x82.toByte()
        )
    }

    override fun onDestroy() {
        popupDialog?.dismiss()
        popupDialog = null
        super.onDestroy()
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }
}
