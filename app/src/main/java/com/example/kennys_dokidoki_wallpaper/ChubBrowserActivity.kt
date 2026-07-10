package com.example.kennys_dokidoki_wallpaper

// Force rebuild and reinstall to resolve preview issues

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
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
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

class ChubBrowserActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar
    private lateinit var btnImport: Button
    private lateinit var tvTitle: TextView
    private lateinit var btnWebBack: TextView
    private lateinit var btnWebForward: TextView

    private var currentChubPath: String? = null // Format: "username/character-name"
    private var popupDialog: android.app.Dialog? = null

    companion object {
        private const val TAG = "ChubBrowserActivity"
        // Pattern to match: chub.ai/characters/username/character-name
        private val CHUB_CHARACTER_PATTERN = Pattern.compile(
            "https?://(?:www\\.)?chub\\.ai/characters/([^/\\?#]+)/([^/\\?#]+)",
            Pattern.CASE_INSENSITIVE
        )
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Load DataManager and TagManager data first so that
        // the backup and validation processes have accurate data in memory!
        DataManager.loadData(this)
        TagManager.loadTags(this)
        BackupManager.initializeLastKnownCounts(this)

        // 1. Create Layout programmatically
        val mainLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.parseColor("#121212")) // Dark background matching the app theme
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
            setOnClickListener { webView.loadUrl("https://www.chub.ai/") }
            contentDescription = "ホーム"
        }
        toolbar.addView(btnHome)

        // Title View
        tvTitle = TextView(this).apply {
            text = "Chub.ai インポート"
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

        // Import Overlay Button
        btnImport = Button(this).apply {
            text = "🎭 このキャラクターをインポートするわ☆"
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            setBackgroundColor(Color.parseColor("#E91E63")) // Cute hot pink accent color
            setPadding(dpToPx(24), dpToPx(16), dpToPx(24), dpToPx(16))
            gravity = Gravity.CENTER
            visibility = View.GONE // Hidden initially until character page is detected
            
            // Layout params at the bottom center of the screen
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
        webView.loadUrl("https://www.chub.ai/")
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
            // Set User Agent to avoid loading issue on some pages
            userAgentString = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
            // Enable multiple windows for Google OAuth support
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = true
        }

        // Enable cookies and third-party cookies for Google/cross-site login support
        android.webkit.CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }

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
                val context = this@ChubBrowserActivity
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

                // Enable cookies for the popup WebView too
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
                            tvTitle.text = "Chub.ai 検索・インポート"
                        }
                    } else if (!title.contains("chub.ai", ignoreCase = true)) {
                        tvTitle.text = title
                    } else {
                        tvTitle.text = "Chub.ai 検索・インポート"
                    }
                } else {
                    tvTitle.text = "Chub.ai 検索・インポート"
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
            currentChubPath = null
            return
        }

        val matcher = CHUB_CHARACTER_PATTERN.matcher(url)
        if (matcher.find()) {
            val username = matcher.group(1) ?: ""
            val charName = matcher.group(2) ?: ""
            if (username.isNotEmpty() && charName.isNotEmpty()) {
                currentChubPath = "$username/$charName"
                btnImport.visibility = View.VISIBLE
                return
            }
        }

        btnImport.visibility = View.GONE
        currentChubPath = null
    }

    private fun startImportFlow() {
        val path = currentChubPath ?: return
        val parts = path.split("/")
        if (parts.size < 2) return
        val username = parts[0]
        val charName = parts[1]

        val progressDialog = ProgressDialog(this).apply {
            setMessage("🎭 Chub.aiからキャラクターデータを読み込んでいるわ☆\n少し待っててね...")
            setCancelable(false)
            show()
        }

        CoroutineScope(Dispatchers.Main).launch {
            val isValidPng = { bytes: ByteArray? ->
                bytes != null && bytes.size >= 8 &&
                bytes[0] == 0x89.toByte() &&
                bytes[1] == 0x50.toByte() &&
                bytes[2] == 0x4E.toByte() &&
                bytes[3] == 0x47.toByte()
            }

            // Build candidates - Direct static CDN is prioritized as it is fast and not blocked by Cloudflare browser challenge.
            val urls = listOf(
                "https://assets.chub.ai/avatars/${username}/${charName}/chara_card_v2.png",
                "https://api.chub.ai/api/characters/download?format=png&full_path=${username}/${charName}",
                "https://chub.ai/api/characters/download?format=png&full_path=${username}/${charName}",
                "https://assets.chub.ai/avatars/${username}/${charName}/avatar.png",
                "https://avatars.charhub.org/avatars/${username}/${charName}/chara_card_v2.png",
                "https://avatars.charhub.org/avatars/${username}/${charName}/avatar.png"
            )

            var downloadedBytes: ByteArray? = null
            var successUrl: String? = null

            for (url in urls) {
                Log.d(TAG, "Trying download from: $url")
                val bytes = downloadImageBytes(url)
                if (bytes != null && bytes.size > 100 && isValidPng(bytes)) {
                    downloadedBytes = bytes
                    successUrl = url
                    break
                }
            }

            progressDialog.dismiss()

            if (downloadedBytes == null) {
                AlertDialog.Builder(this@ChubBrowserActivity, R.style.Theme_Kennys_dokidoki_wallpaper)
                    .setTitle("ダウンロード失敗")
                    .setMessage("キャラクター画像のダウンロードに失敗したわ。\n接続状態や、このカードがChub.ai上で削除されていないか確認してね。")
                    .setPositiveButton("OK", null)
                    .show()
                return@launch
            }

            // Let's parse PNG
            val inputStream = ByteArrayInputStream(downloadedBytes)
            var json = TavernCardParser.parsePng(inputStream)

            if (json == null) {
                // FALLBACK: Try fetching JSON character definition directly from Chub API
                Log.d(TAG, "PNG parsing returned null, trying direct API fallback...")
                val fallbackUrls = listOf(
                    "https://api.chub.ai/api/characters/download?format=json&full_path=${username}/${charName}",
                    "https://chub.ai/api/characters/download?format=json&full_path=${username}/${charName}"
                )
                for (fallbackUrl in fallbackUrls) {
                    val apiFallbackBytes = downloadImageBytes(fallbackUrl)
                    if (apiFallbackBytes != null) {
                        try {
                            val apiFallbackJsonStr = String(apiFallbackBytes, Charsets.UTF_8).trim()
                            if (apiFallbackJsonStr.startsWith("{")) {
                                val fallbackJson = JSONObject(apiFallbackJsonStr)
                                // Verify if it contains character details
                                if (fallbackJson.has("character") || fallbackJson.has("data") || fallbackJson.has("name") || fallbackJson.has("char_name")) {
                                    json = fallbackJson
                                    Log.d(TAG, "Direct API fallback succeeded from $fallbackUrl!")
                                    break
                                }
                            } else {
                                Log.w(TAG, "API fallback returned HTML or non-JSON content from $fallbackUrl: ${apiFallbackJsonStr.take(100)}")
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Error parsing API fallback JSON from $fallbackUrl", e)
                        }
                    }
                }
            }

            if (json == null) {
                // Succeeded in downloading image, but no Tavern metadata found
                AlertDialog.Builder(this@ChubBrowserActivity, R.style.Theme_Kennys_dokidoki_wallpaper)
                    .setTitle("キャラクター情報なし")
                    .setMessage("キャラクターの定義情報（Tavern/Chub仕様）が見つからなかったわ。通常の画像としてアルバムに追加する？")
                    .setPositiveButton("通常の画像として追加") { _, _ ->
                        saveAsNormalImage(downloadedBytes)
                    }
                    .setNegativeButton("キャンセル", null)
                    .show()
                return@launch
            }

            // Succeeded in finding metadata!
            try {
                val charData = TavernCardParser.extractCharacterData(json)

                // ここで編集ダイアログを表示するわよ！
                CharacterImportEditor.showImportEditorDialog(this@ChubBrowserActivity, charData) { editedCharData ->
                    val formattedText = TavernCardParser.formatDescriptionText(editedCharData)

                    // Save PNG bytes locally
                    val fileName = "chub_char_${editedCharData.name.replace("\\s+".toRegex(), "_")}_${System.currentTimeMillis()}.png"
                    val file = File(filesDir, fileName)
                    file.writeBytes(downloadedBytes)
                    val localUri = Uri.fromFile(file)

                    val newEntry = ImageEntry(
                        uri = localUri,
                        description = formattedText
                    )
                    
                    // 「chub」タグを自動付与して、タグが存在しない場合は「その他」に自動生成するわ♡
                    TagManager.addTag(this@ChubBrowserActivity, "chub")
                    newEntry.tags.add("chub")

                    if (DataManager.allImages.none { it.uri.toString() == localUri.toString() }) {
                        DataManager.allImages.add(newEntry)
                        DataManager.saveData(this@ChubBrowserActivity)

                        AlertDialog.Builder(this@ChubBrowserActivity, R.style.Theme_Kennys_dokidoki_wallpaper)
                            .setTitle("🎭 インポート大成功！")
                            .setMessage("『${editedCharData.name}』のインポートが完了したわよ！\n\n最初のセリフ:\n${editedCharData.firstMes.ifEmpty { "（未設定）" }}\n\n今すぐこのキャラクターとおしゃべり(チャット)してみる？")
                            .setPositiveButton("チャットを開始") { _, _ ->
                                val intent = Intent(this@ChubBrowserActivity, ChatOverlayActivity::class.java).apply {
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
                        Toast.makeText(this@ChubBrowserActivity, "このキャラクターは既に登録されているわよ。", Toast.LENGTH_SHORT).show()
                    }
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error processing imported data", e)
                Toast.makeText(this@ChubBrowserActivity, "処理中にエラーが発生したわ: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun saveAsNormalImage(bytes: ByteArray) {
        try {
            val fileName = "chub_image_${System.currentTimeMillis()}.png"
            val file = File(filesDir, fileName)
            file.writeBytes(bytes)
            val localUri = Uri.fromFile(file)

            if (DataManager.allImages.none { it.uri.toString() == localUri.toString() }) {
                DataManager.allImages.add(ImageEntry(localUri))
                DataManager.saveData(this)
                Toast.makeText(this, "通常の画像として追加したわよ！", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "この画像は既にアルバムに登録されているわ。", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error saving normal image", e)
            Toast.makeText(this, "画像の保存に失敗しました: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private suspend fun downloadImageBytes(urlString: String): ByteArray? = withContext(Dispatchers.IO) {
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
                connection.instanceFollowRedirects = false // manual redirection handling is safer across domains/protocols

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
                Log.e(TAG, "Exception during download of $currentUrl", e)
                break
            }
        }
        result
    }

    override fun onDestroy() {
        popupDialog?.dismiss()
        popupDialog = null
        super.onDestroy()
    }

    // Utility to convert dp to px
    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }
}
