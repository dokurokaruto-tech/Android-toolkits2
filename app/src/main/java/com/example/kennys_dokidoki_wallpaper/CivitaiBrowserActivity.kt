package com.example.kennys_dokidoki_wallpaper

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.google.android.material.progressindicator.LinearProgressIndicator
import java.util.regex.Pattern

// MD3 in-app browser. The import button appears on model pages only.
class CivitaiBrowserActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var progress: LinearProgressIndicator
    private lateinit var toolbar: MaterialToolbar
    private lateinit var fabImport: ExtendedFloatingActionButton

    private var currentModelId: Long? = null
    private var currentVersionId: Long? = null

    companion object {
        const val HOME_URL = "https://civitai.com/"
        private val MODEL_PATTERN = Pattern.compile(
            "https?://(?:www\\.)?civitai\\.com/models/(\\d+)",
            Pattern.CASE_INSENSITIVE
        )
        private val VERSION_PATTERN = Pattern.compile("[?&]modelVersionId=(\\d+)")
    }

    private val importLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            setResult(Activity.RESULT_OK)
            finish()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_civitai_browser)

        toolbar = findViewById(R.id.toolbar_civitai)
        progress = findViewById(R.id.progress_civitai)
        webView = findViewById(R.id.web_civitai)
        fabImport = findViewById(R.id.fab_import_model)

        toolbar.setNavigationOnClickListener { finish() }
        toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_web_back -> {
                    if (webView.canGoBack()) {
                        webView.goBack()
                    }
                    true
                }
                R.id.action_web_forward -> {
                    if (webView.canGoForward()) {
                        webView.goForward()
                    }
                    true
                }
                R.id.action_web_home -> {
                    webView.loadUrl(HOME_URL)
                    true
                }
                R.id.action_web_refresh -> {
                    webView.reload()
                    true
                }
                else -> false
            }
        }

        fabImport.setOnClickListener {
            val modelId = currentModelId ?: return@setOnClickListener
            val intent = Intent(this, CivitaiImportActivity::class.java).apply {
                putExtra(CivitaiImportActivity.EXTRA_MODEL_ID, modelId)
                currentVersionId?.let { putExtra(CivitaiImportActivity.EXTRA_VERSION_ID, it) }
            }
            importLauncher.launch(intent)
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) {
                    webView.goBack()
                } else {
                    finish()
                }
            }
        })

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            useWideViewPort = true
            loadWithOverviewMode = true
            cacheMode = WebSettings.LOAD_DEFAULT
            userAgentString = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
        }
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }
        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                progress.visibility = View.VISIBLE
                checkUrl(url)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                progress.visibility = View.GONE
                checkUrl(url)
            }

            override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                super.doUpdateVisitedHistory(view, url, isReload)
                checkUrl(url)
            }
        }
        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                super.onProgressChanged(view, newProgress)
                progress.setProgressCompat(newProgress, true)
                progress.visibility = if (newProgress >= 100) View.GONE else View.VISIBLE
            }

            override fun onReceivedTitle(view: WebView?, title: String?) {
                super.onReceivedTitle(view, title)
                if (!title.isNullOrBlank() && !title.startsWith("http")) {
                    toolbar.subtitle = title
                }
            }
        }

        webView.loadUrl(HOME_URL)
    }

    private fun checkUrl(url: String?) {
        if (url == null) {
            currentModelId = null
            currentVersionId = null
            fabImport.visibility = View.GONE
            return
        }
        val model = MODEL_PATTERN.matcher(url)
        if (!model.find()) {
            currentModelId = null
            currentVersionId = null
            fabImport.visibility = View.GONE
            return
        }
        currentModelId = model.group(1)?.toLongOrNull()
        val version = VERSION_PATTERN.matcher(url)
        currentVersionId = if (version.find()) version.group(1)?.toLongOrNull() else null
        fabImport.visibility = if (currentModelId != null) View.VISIBLE else View.GONE
    }

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }
}
