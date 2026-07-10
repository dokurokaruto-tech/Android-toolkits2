package com.example.kennys_dokidoki_wallpaper

import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.util.Log
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.text.Spannable
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SwitchCompat
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/** 
 * 上部をフェードアウト（透明化）させるためのカスタムレイアウト
 */
class FadingLayout @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {
    var fadeEnabled = false
    var fadeStart = 300f
    var fadeHeight = 50f

    private val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (fadeEnabled && ev.y < (fadeStart + fadeHeight)) {
            // フェードエリア内なら、子ビュー（RecyclerView）にイベントを渡さないわ！
            return true 
        }
        return super.onInterceptTouchEvent(ev)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (fadeEnabled && event.y < (fadeStart + fadeHeight)) {
            // ここでイベントを吸収！目隠しエリアを触ってもチャットは動かさないわよ☆
            return true
        }
        return super.onTouchEvent(event)
    }

    override fun dispatchDraw(canvas: Canvas) {
        if (!fadeEnabled) {
            super.dispatchDraw(canvas)
            return
        }
        val saveLayer = canvas.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), null)
        super.dispatchDraw(canvas)

        val gradient = LinearGradient(
            0f, fadeStart,
            0f, fadeStart + fadeHeight,
            intArrayOf(0x00000000, 0xFF000000.toInt()),
            null,
            Shader.TileMode.CLAMP
        )
        maskPaint.shader = gradient
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), maskPaint)
        canvas.restoreToCount(saveLayer)
    }
}

// --- データモデル ---
data class ChatDisplayItem(
    val node: ChatNode,
    val siblingIndex: Int,
    val siblingCount: Int
)

// --- アダプター ---
class ChatAdapter(
    private val messages: List<ChatDisplayItem>,
    private val onRegenerate: (ChatNode) -> Unit,
    private val onEditUser: (ChatNode) -> Unit,
    private val onNavigateBranch: (ChatNode, Int) -> Unit,
    private val onSelectSuggestion: (String) -> Unit
) : RecyclerView.Adapter<ChatAdapter.ViewHolder>() {
    private var bubbleOpacity: Int = 60
    private var bubbleWidth: Int = 670

    fun setBubbleOpacity(opacity: Int) {
        this.bubbleOpacity = opacity
        notifyDataSetChanged()
    }

    fun setBubbleWidth(width: Int) {
        this.bubbleWidth = width
        notifyDataSetChanged()
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val containerAi: LinearLayout = view.findViewById(R.id.container_ai)
        val bubbleAi: LinearLayout = view.findViewById(R.id.bubble_ai)
        val textAi: TextView = view.findViewById(R.id.text_message_ai)
        val imageAi: ImageView = view.findViewById(R.id.image_message_ai)
        val btnAiPrev: TextView = view.findViewById(R.id.btn_ai_prev)
        val btnAiNext: TextView = view.findViewById(R.id.btn_ai_next)
        val tvAiBranch: TextView = view.findViewById(R.id.tv_ai_branch)
        val btnAiRegen: TextView = view.findViewById(R.id.btn_ai_regenerate)
        val btnAiCopy: TextView = view.findViewById(R.id.btn_ai_copy)
        val layoutSuggestions: LinearLayout = view.findViewById(R.id.layout_suggestions)
        val btnSuggestA: TextView = view.findViewById(R.id.btn_suggest_a)
        val btnSuggestB: TextView = view.findViewById(R.id.btn_suggest_b)
        val btnSuggestC: TextView = view.findViewById(R.id.btn_suggest_c)

        val containerUser: LinearLayout = view.findViewById(R.id.container_user)
        val textUser: TextView = view.findViewById(R.id.text_message_user)
        val layoutUserGift: LinearLayout = view.findViewById(R.id.layout_user_gift)
        val tvUserGiftIcon: TextView = view.findViewById(R.id.tv_user_gift_icon)
        val tvUserGiftName: TextView = view.findViewById(R.id.tv_user_gift_name)
        val btnUserEdit: TextView = view.findViewById(R.id.btn_user_edit)
        val btnUserPrev: TextView = view.findViewById(R.id.btn_user_prev)
        val btnUserNext: TextView = view.findViewById(R.id.btn_user_next)
        val tvUserBranch: TextView = view.findViewById(R.id.tv_user_branch)
        val btnUserCopy: TextView = view.findViewById(R.id.btn_user_copy)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_chat_message, parent, false)
        return ViewHolder(view)
    }

    private fun copyToClipboard(context: Context, text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("chat_message", text)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(context, "クリップボードにコピーしました。", Toast.LENGTH_SHORT).show()
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = messages[position]
        val node = item.node
        val alpha = (bubbleOpacity * 2.55).toInt().coerceIn(0, 255)
        
        val density = holder.itemView.context.resources.displayMetrics.density
        val maxWidthPx = (bubbleWidth * density).toInt()

        if (node.isUser) {
            holder.containerAi.visibility = View.GONE
            holder.containerUser.visibility = View.VISIBLE
            
            holder.textUser.text = node.text
            holder.textUser.background.alpha = alpha
            
            holder.textUser.maxWidth = maxWidthPx
            holder.textUser.minWidth = maxWidthPx
            holder.containerUser.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                width = maxWidthPx
            }

            // お小遣い表示
            val giftKey = node.giftKey
            if (!giftKey.isNullOrEmpty()) {
                holder.layoutUserGift.visibility = View.VISIBLE
                holder.layoutUserGift.background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(android.graphics.Color.parseColor("#1E1F20")) // ダークパープル
                    setStroke(2, android.graphics.Color.parseColor("#F28B82")) // エラー強調の枠線
                    cornerRadius = 24f
                }
                val (emoji, giftName) = if (giftKey.startsWith("money_")) {
                    val amountStr = giftKey.removePrefix("money_")
                    val amount = amountStr.toIntOrNull() ?: 0
                    "💵" to "お小遣い: ${String.format("%,d", amount)}円"
                } else {
                    "🎁" to "プレゼント"
                }
                holder.tvUserGiftIcon.text = emoji
                holder.tvUserGiftName.text = "贈呈: $giftName"
            } else {
                holder.layoutUserGift.visibility = View.GONE
            }

            holder.btnUserEdit.setOnClickListener { onEditUser(node) }
            holder.btnUserCopy.setOnClickListener { copyToClipboard(holder.itemView.context, node.text) }

            if (item.siblingCount > 1) {
                holder.tvUserBranch.visibility = View.VISIBLE
                holder.btnUserPrev.visibility = View.VISIBLE
                holder.btnUserNext.visibility = View.VISIBLE
                holder.tvUserBranch.text = "${item.siblingIndex + 1}/${item.siblingCount}"
                holder.btnUserPrev.setOnClickListener { onNavigateBranch(node, item.siblingIndex - 1) }
                holder.btnUserNext.setOnClickListener { onNavigateBranch(node, item.siblingIndex + 1) }
                
                holder.btnUserPrev.alpha = if (item.siblingIndex > 0) 1.0f else 0.3f
                holder.btnUserNext.alpha = if (item.siblingIndex < item.siblingCount - 1) 1.0f else 0.3f
                holder.btnUserPrev.isEnabled = item.siblingIndex > 0
                holder.btnUserNext.isEnabled = item.siblingIndex < item.siblingCount - 1
            } else {
                holder.tvUserBranch.visibility = View.GONE
                holder.btnUserPrev.visibility = View.GONE
                holder.btnUserNext.visibility = View.GONE
            }

        } else {
            holder.containerAi.visibility = View.VISIBLE
            holder.containerUser.visibility = View.GONE
            
            // HTMLの<img>タグ、またはMarkdownの![...] (url) をパースして画像があれば表示するわよ！
            val imgRegex = "(?i)<img[^>]+src=\"([^\"]+)\"[^>]*>".toRegex()
            val mdImgRegex = """!\[.*?\]\((.*?)\)""".toRegex()
            
            var imageUrl: String? = null
            var cleanText = node.text
            
            val htmlMatch = imgRegex.find(node.text)
            if (htmlMatch != null) {
                imageUrl = htmlMatch.groups[1]?.value
                cleanText = node.text.replace(imgRegex, "").trim()
            } else {
                val mdMatch = mdImgRegex.find(node.text)
                if (mdMatch != null) {
                    imageUrl = mdMatch.groups[1]?.value
                    cleanText = node.text.replace(mdImgRegex, "").trim()
                }
            }
            
            if (!imageUrl.isNullOrEmpty()) {
                holder.imageAi.visibility = View.VISIBLE
                com.bumptech.glide.Glide.with(holder.itemView.context)
                    .load(imageUrl)
                    .into(holder.imageAi)
                
                holder.imageAi.setOnClickListener {
                    val context = holder.itemView.context
                    val dialog = android.app.Dialog(context, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
                    val frameLayout = android.widget.FrameLayout(context).apply {
                        setBackgroundColor(android.graphics.Color.BLACK)
                        layoutParams = android.view.ViewGroup.LayoutParams(
                            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                            android.view.ViewGroup.LayoutParams.MATCH_PARENT
                        )
                    }
                    val fullImageView = android.widget.ImageView(context).apply {
                        layoutParams = android.widget.FrameLayout.LayoutParams(
                            android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                            android.widget.FrameLayout.LayoutParams.MATCH_PARENT
                        )
                        scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                        adjustViewBounds = true
                    }
                    com.bumptech.glide.Glide.with(context)
                        .load(imageUrl)
                        .into(fullImageView)
                    
                    frameLayout.addView(fullImageView)
                    
                    val closeListener = android.view.View.OnClickListener {
                        dialog.dismiss()
                    }
                    fullImageView.setOnClickListener(closeListener)
                    frameLayout.setOnClickListener(closeListener)
                    
                    dialog.setContentView(frameLayout)
                    dialog.show()
                }
            } else {
                holder.imageAi.visibility = View.GONE
                holder.imageAi.setOnClickListener(null)
            }
            val displayText = cleanText
            
            // モデル名がある場合は小さく表示するわよ！
            if (node.modelName != null && !displayText.startsWith("推論中") && !displayText.startsWith("🧠 推論中") && !displayText.startsWith("📥 モデルをロード")) {
                val ssb = SpannableStringBuilder(displayText)
                ssb.append("\n\n")
                val start = ssb.length
                ssb.append("via ${node.modelName}")
                ssb.setSpan(ForegroundColorSpan(Color.GRAY), start, ssb.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                ssb.setSpan(RelativeSizeSpan(0.7f), start, ssb.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                holder.textAi.text = ssb
            } else {
                holder.textAi.text = displayText
            }
            holder.bubbleAi.background.alpha = alpha
            
            holder.textAi.maxWidth = maxWidthPx
            holder.textAi.minWidth = maxWidthPx
            holder.containerAi.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                width = maxWidthPx
            }

            holder.btnAiRegen.setOnClickListener { onRegenerate(node) }
            holder.btnAiCopy.setOnClickListener { copyToClipboard(holder.itemView.context, node.text) }
            
            if (node.text.startsWith("思考中")) {
                holder.btnAiRegen.visibility = View.GONE
                holder.btnAiCopy.visibility = View.GONE
            } else {
                holder.btnAiRegen.visibility = View.VISIBLE
                holder.btnAiCopy.visibility = View.VISIBLE
            }

            if (item.siblingCount > 1) {
                holder.tvAiBranch.visibility = View.VISIBLE
                holder.btnAiPrev.visibility = View.VISIBLE
                holder.btnAiNext.visibility = View.VISIBLE
                holder.tvAiBranch.text = "${item.siblingIndex + 1}/${item.siblingCount}"
                holder.btnAiPrev.setOnClickListener { onNavigateBranch(node, item.siblingIndex - 1) }
                holder.btnAiNext.setOnClickListener { onNavigateBranch(node, item.siblingIndex + 1) }
                
                holder.btnAiPrev.alpha = if (item.siblingIndex > 0) 1.0f else 0.3f
                holder.btnAiNext.alpha = if (item.siblingIndex < item.siblingCount - 1) 1.0f else 0.3f
                holder.btnAiPrev.isEnabled = item.siblingIndex > 0
                holder.btnAiNext.isEnabled = item.siblingIndex < item.siblingCount - 1
            } else {
                holder.tvAiBranch.visibility = View.GONE
                holder.btnAiPrev.visibility = View.GONE
                holder.btnAiNext.visibility = View.GONE
            }

            val isLastMessage = (position == messages.size - 1)
            val prefs = holder.itemView.context.getSharedPreferences("settings", Context.MODE_PRIVATE)
            val isSuggestEnabled = prefs.getBoolean("chat_suggest_reply", true)

            if (isLastMessage && isSuggestEnabled && !node.suggestionA.isNullOrEmpty()) {
                holder.layoutSuggestions.visibility = View.VISIBLE
                holder.btnSuggestA.text = "A: ${node.suggestionA}"
                holder.btnSuggestB.text = "B: ${node.suggestionB}"
                holder.btnSuggestC.text = "C: ${node.suggestionC}"

                holder.btnSuggestA.setOnClickListener { onSelectSuggestion(node.suggestionA ?: "") }
                holder.btnSuggestB.setOnClickListener { onSelectSuggestion(node.suggestionB ?: "") }
                holder.btnSuggestC.setOnClickListener { onSelectSuggestion(node.suggestionC ?: "") }
            } else {
                holder.layoutSuggestions.visibility = View.GONE
            }
        }
    }
    override fun getItemCount() = messages.size
}

/** セッション選択用のアダプター */
class ChatSessionAdapter(
    private val sessions: List<Pair<String, String>>,
    private val onSelect: (String, String) -> Unit,
    private val onMenuClick: (String, String, View) -> Unit
) : RecyclerView.Adapter<ChatSessionAdapter.ViewHolder>() {
    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvName: TextView = view.findViewById(R.id.tv_session_name)
        val btnMenu: ImageButton = view.findViewById(R.id.btn_session_menu)
    }
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_chat_session, parent, false)
        return ViewHolder(view)
    }
    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val (id, name) = sessions[position]
        holder.tvName.text = name
        holder.itemView.setOnClickListener { onSelect(id, name) }
        holder.btnMenu.setOnClickListener { onMenuClick(id, name, it) }
    }
    override fun getItemCount() = sessions.size
}

class ChatOverlayActivity : androidx.appcompat.app.AppCompatActivity(), SharedPreferences.OnSharedPreferenceChangeListener, ChatGenerationManager.Listener {

    private val OPENROUTER_API_URL = "https://openrouter.ai/api/v1/chat/completions"

    private var chatTree = ChatTree(mutableMapOf(), null)
    private val displayMessages = mutableListOf<ChatDisplayItem>()
    private lateinit var adapter: ChatAdapter
    
    private val coroutineScope = CoroutineScope(Dispatchers.Main + Job())
    private var currentChatId: String? = null
    private var currentImageEntry: ImageEntry? = null

    private lateinit var tvOpenRouterCounter: TextView
    private lateinit var tvMagicStoneCounter: TextView
    private lateinit var btnGiftSelect: Button
    private lateinit var tvSelectedGift: TextView
    private lateinit var btnClearGift: ImageButton

    // お小遣い金額の選択状態 (円単位)
    private var selectedMoneyAmount: Int? = null

    private lateinit var chatInput: EditText
    private lateinit var btnSend: ImageButton
    private lateinit var btnOptions: ImageButton
    private lateinit var recyclerView: RecyclerView
    private var isAutoScrollEnabled = true

    private fun updateCounter() {
        val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        val provider = prefs.getString("chat_cloud_provider", "GROK")
        if (provider == "OPENROUTER") {
            val total = OpenRouterManager.getTotalUsage(this)
            val keys = OpenRouterManager.getApiKeys(this)
            val maxQuota = keys.size * 50
            tvOpenRouterCounter.text = "OR: $total/$maxQuota"
            tvOpenRouterCounter.visibility = View.VISIBLE
        } else {
            tvOpenRouterCounter.visibility = View.GONE
        }
    }

    // タップ判定用
    private var tapCount: Int = 0
    private val tapTimeout: Long = 350L
    private val tapTimeoutHandler = Handler(Looper.getMainLooper())
    private var lastTapTime: Long = 0
    private val tapRunnable = Runnable {
        if (tapCount >= 2) {
            executeTapAction(tapCount)
        }
        tapCount = 0
    }

    private var holdRunnable: Runnable? = null
    private val holdTimeout = 400L
    private var longPressRunnable: Runnable? = null
    private val longPress1sTimeout = 1000L
    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L
    private val TOUCH_TOLERANCE = 50f

    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
        ev?.let {
            when (it.action) {
                MotionEvent.ACTION_DOWN -> {
                    val intent = Intent("com.example.kennys_dokidoki_wallpaper.ACTION_SHOW_SET_NAME")
                    intent.setPackage(packageName)
                    sendBroadcast(intent)

                    tapTimeoutHandler.removeCallbacks(tapRunnable)
                    
                    downX = it.x
                    downY = it.y
                    downTime = System.currentTimeMillis()

                    if (tapCount == 1 && (downTime - lastTapTime) <= tapTimeout) {
                        holdRunnable = Runnable {
                            val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
                            val action = prefs.getString("action_tap_2_hold", "NONE") ?: "NONE"
                            executeActionString(action)
                            tapCount = 0
                        }
                        tapTimeoutHandler.postDelayed(holdRunnable!!, holdTimeout)
                    } else if (tapCount == 2 && (downTime - lastTapTime) <= tapTimeout) {
                        holdRunnable = Runnable {
                            val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
                            val action = prefs.getString("action_tap_3_hold", "NONE") ?: "NONE"
                            executeActionString(action)
                            tapCount = 0
                        }
                        tapTimeoutHandler.postDelayed(holdRunnable!!, holdTimeout)
                    } else {
                        if ((downTime - lastTapTime) > tapTimeout) {
                            tapCount = 0
                        }
                        if (tapCount == 0) {
                            longPressRunnable = Runnable {
                                val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
                                val action = prefs.getString("action_hold_2s", "NONE") ?: "NONE"
                                executeActionString(action)
                                tapCount = 0
                            }
                            tapTimeoutHandler.postDelayed(longPressRunnable!!, longPress1sTimeout)
                        }
                    }
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = it.x - downX
                    val dy = it.y - downY
                    val distance = Math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()
                    if (distance > TOUCH_TOLERANCE) {
                        holdRunnable?.let { r -> tapTimeoutHandler.removeCallbacks(r); holdRunnable = null }
                        longPressRunnable?.let { r -> tapTimeoutHandler.removeCallbacks(r); longPressRunnable = null }
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    holdRunnable?.let { r -> tapTimeoutHandler.removeCallbacks(r); holdRunnable = null }
                    longPressRunnable?.let { r -> tapTimeoutHandler.removeCallbacks(r); longPressRunnable = null }
                    if (it.action == MotionEvent.ACTION_UP) {
                        val dx = it.x - downX
                        val dy = it.y - downY
                        val distance = Math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()
                        
                        if (distance <= TOUCH_TOLERANCE && (System.currentTimeMillis() - downTime) < 300L) {
                            val currentTime = System.currentTimeMillis()
                            if (currentTime - lastTapTime > tapTimeout) {
                                tapCount = 1
                            } else {
                                tapCount++
                            }
                            lastTapTime = currentTime
                            tapTimeoutHandler.postDelayed(tapRunnable, tapTimeout)
                        }
                    }
                }
                else -> {}
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun executeTapAction(count: Int) {
        val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        val defaultAction = when (count) {
            2 -> "NEXT_IMAGE"
            3 -> "NEXT_SET"
            else -> "NONE"
        }
        val action = prefs.getString("action_tap_$count", defaultAction) ?: "NONE"
        executeActionString(action)
    }

    private fun executeActionString(action: String) {
        if (action == "TOGGLE_AI_CHAT") {
            finish()
        } else if (action != "NONE") {
            val intent = Intent("com.example.kennys_dokidoki_wallpaper.ACTION_EXECUTE_ACTION")
            intent.setPackage(packageName)
            intent.putExtra("action_string", action)
            sendBroadcast(intent)
        }
    }

    private val wallpaperChangeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "com.example.kennys_dokidoki_wallpaper.ACTION_WALLPAPER_CHANGED") {
                runOnUiThread {
                    currentChatId?.let { ChatSessionManager.saveSessionData(this@ChatOverlayActivity, it, chatTree) }
                    DataManager.loadData(this@ChatOverlayActivity)
                    loadCurrentSession()
                    if (displayMessages.isNotEmpty()) {
                        findViewById<RecyclerView>(R.id.chat_recycler_view).scrollToPosition(displayMessages.size - 1)
                    }
                }
            }
        }
    }

    private fun hideKeyboard() {
        val view = this.currentFocus
        if (view != null) {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(view.windowToken, 0)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        window.decorView.setBackgroundColor(Color.TRANSPARENT)
        setContentView(R.layout.activity_chat_overlay)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root_layout)) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val imeInsets = insets.getInsets(WindowInsetsCompat.Type.ime())
            
            findViewById<View>(R.id.btn_close_chat).let {
                val lp = it.layoutParams as ViewGroup.MarginLayoutParams
                lp.topMargin = systemBars.top + (16 * resources.displayMetrics.density).toInt()
                it.layoutParams = lp
            }
            findViewById<View>(R.id.btn_options).let {
                val lp = it.layoutParams as ViewGroup.MarginLayoutParams
                lp.topMargin = systemBars.top + (16 * resources.displayMetrics.density).toInt()
                it.layoutParams = lp
            }

            findViewById<View>(R.id.bottom_controls_container).let {
                val lp = it.layoutParams as ViewGroup.MarginLayoutParams
                val bottomPadding = if (imeInsets.bottom > systemBars.bottom) {
                    imeInsets.bottom + (8 * resources.displayMetrics.density).toInt()
                } else {
                    systemBars.bottom + (16 * resources.displayMetrics.density).toInt()
                }
                lp.bottomMargin = bottomPadding
                it.layoutParams = lp
            }
            
            insets
        }

        val settingsPrefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        settingsPrefs.registerOnSharedPreferenceChangeListener(this)

        val filter = IntentFilter("com.example.kennys_dokidoki_wallpaper.ACTION_WALLPAPER_CHANGED")
        ContextCompat.registerReceiver(this, wallpaperChangeReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)

        recyclerView = findViewById(R.id.chat_recycler_view)
        recyclerView.isFocusable = false
        recyclerView.isFocusableInTouchMode = false
        
        val layoutManager = LinearLayoutManager(this).apply {
            stackFromEnd = true
        }
        recyclerView.layoutManager = layoutManager
        
        adapter = ChatAdapter(
            displayMessages,
            onRegenerate = { node ->
                regenerateResponse(node, recyclerView)
            },
            onEditUser = { node ->
                showEditUserMessageDialog(node, recyclerView)
            },
            onNavigateBranch = { node, newIndex ->
                navigateBranch(node, newIndex)
                recyclerView.scrollToPosition(displayMessages.size - 1)
            },
            onSelectSuggestion = { text ->
                sendSuggestedMessage(text)
            }
        )
        recyclerView.adapter = adapter

        recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(rv, dx, dy)
                if (dy < 0) {
                    isAutoScrollEnabled = false
                } else if (dy > 0) {
                    val layoutManager = rv.layoutManager as LinearLayoutManager
                    val totalItemCount = layoutManager.itemCount
                    val lastVisibleItemPosition = layoutManager.findLastVisibleItemPosition()
                    
                    if (lastVisibleItemPosition >= totalItemCount - 2) {
                        isAutoScrollEnabled = true
                    }
                }
            }
        })

        DataManager.loadData(this)
        loadCurrentSession()

        val btnClose = findViewById<ImageButton>(R.id.btn_close_chat)
        btnOptions = findViewById(R.id.btn_options)
        btnSend = findViewById(R.id.btn_send)
        chatInput = findViewById(R.id.chat_input)
        tvOpenRouterCounter = findViewById(R.id.tv_openrouter_counter)
        tvOpenRouterCounter.setOnClickListener {
            showOpenRouterKeySelector()
        }

        tvMagicStoneCounter = findViewById(R.id.tv_magic_stone_counter)
        tvMagicStoneCounter.setOnClickListener {
            showWalletChargeShopDialog()
        }
        updateMagicStoneCounter()

        btnGiftSelect = findViewById(R.id.btn_gift_select)
        btnGiftSelect.text = "💵 お小遣いをあげる"

        tvSelectedGift = findViewById(R.id.tv_selected_gift)
        btnClearGift = findViewById(R.id.btn_clear_gift)

        btnGiftSelect.setOnClickListener {
            showPocketMoneySelectionDialog()
        }

        btnClearGift.setOnClickListener {
            selectedMoneyAmount = null
            tvSelectedGift.visibility = View.GONE
            btnClearGift.visibility = View.GONE
        }

        updateCounter()
        applyVisualConfigs()

        btnClose.setOnClickListener { view ->
            val allTagsOrdered = TagManager.categories.flatMap { it.tags }
            val priorityMap = allTagsOrdered.withIndex().associate { it.value to it.index }
            val tags = currentImageEntry?.tags?.toList()?.sortedBy { priorityMap[it] ?: Int.MAX_VALUE } ?: emptyList()
            
            val dialog = AlertDialog.Builder(this, R.style.Theme_TransparentDialog).create()
            
            val dialogView = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(64, 64, 64, 64)
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(android.graphics.Color.parseColor("#E61E1F20"))
                    setStroke(3, android.graphics.Color.parseColor("#B3F28B82"))
                    cornerRadius = 48f
                }
                
                val prompt = getActiveImageTagsPrompt()
                val charCount = prompt.length
                val tokenCount = TagManager.estimateTokenCount(prompt)

                addView(TextView(this@ChatOverlayActivity).apply {
                    text = "ACTIVE CONTEXT"
                    setTextColor(android.graphics.Color.parseColor("#F28B82"))
                    textSize = 12f
                    letterSpacing = 0.2f
                    setTypeface(null, android.graphics.Typeface.BOLD)
                })

                addView(TextView(this@ChatOverlayActivity).apply {
                    text = "Total: $charCount chars | Approx. $tokenCount tokens"
                    setTextColor(android.graphics.Color.parseColor("#9AA0A6"))
                    textSize = 10f
                    setPadding(0, 4, 0, 32)
                })
                
                val scrollView = ScrollView(this@ChatOverlayActivity).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        weight = 1f
                    }
                }
                
                val tagsContainer = LinearLayout(this@ChatOverlayActivity).apply {
                    orientation = LinearLayout.VERTICAL
                }
                
                if (tags.isEmpty()) {
                    tagsContainer.addView(TextView(this@ChatOverlayActivity).apply {
                        text = "タグが設定されていません"
                        setTextColor(android.graphics.Color.GRAY)
                        setPadding(0, 16, 0, 16)
                    })
                } else {
                    tags.forEach { tag ->
                        val prompt = TagManager.getTagPrompt(tag)
                        val tChars = prompt.length
                        val tTokens = TagManager.estimateTokenCount(prompt)

                        val tagRow = LinearLayout(this@ChatOverlayActivity).apply {
                            orientation = LinearLayout.HORIZONTAL
                            gravity = android.view.Gravity.CENTER_VERTICAL
                            setPadding(48, 32, 48, 32)
                            background = android.graphics.drawable.GradientDrawable().apply {
                                setColor(android.graphics.Color.parseColor("#1AF28B82"))
                                setStroke(2, android.graphics.Color.parseColor("#80F28B82"))
                                cornerRadius = 24f
                            }
                            layoutParams = LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.MATCH_PARENT,
                                LinearLayout.LayoutParams.WRAP_CONTENT
                            ).apply {
                                setMargins(0, 16, 0, 16)
                            }
                            
                            addView(TextView(this@ChatOverlayActivity).apply {
                                text = "# "
                                setTextColor(android.graphics.Color.parseColor("#F28B82"))
                                textSize = 16f
                                setTypeface(null, android.graphics.Typeface.BOLD)
                            })
                            
                            addView(LinearLayout(this@ChatOverlayActivity).apply {
                                orientation = LinearLayout.VERTICAL
                                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                                
                                addView(TextView(this@ChatOverlayActivity).apply {
                                    text = tag
                                    setTextColor(android.graphics.Color.WHITE)
                                    textSize = 16f
                                })
                                
                                addView(TextView(this@ChatOverlayActivity).apply {
                                    text = "$tChars chars | approx. $tTokens tokens"
                                    setTextColor(android.graphics.Color.parseColor("#9AA0A6"))
                                    textSize = 10f
                                })
                            })
                            
                            addView(TextView(this@ChatOverlayActivity).apply {
                                text = "EDIT"
                                setTextColor(android.graphics.Color.parseColor("#F28B82"))
                                textSize = 12f
                                letterSpacing = 0.1f
                                setTypeface(null, android.graphics.Typeface.BOLD)
                            })
                            
                            setOnClickListener {
                                dialog.dismiss()
                                val intent = Intent(this@ChatOverlayActivity, TagPromptEditorActivity::class.java)
                                intent.putExtra("TAG_NAME", tag)
                                startActivity(intent)
                            }
                        }
                        tagsContainer.addView(tagRow)
                    }
                }
                
                scrollView.addView(tagsContainer)
                addView(scrollView)
                
                addView(View(this@ChatOverlayActivity).apply {
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 2).apply {
                        setMargins(0, 48, 0, 32)
                    }
                    setBackgroundColor(android.graphics.Color.parseColor("#33F28B82"))
                })

                val viewAllPromptsBtn = LinearLayout(this@ChatOverlayActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER
                    setPadding(32, 24, 32, 24)
                    background = android.graphics.drawable.GradientDrawable().apply {
                        setColor(android.graphics.Color.parseColor("#1AF28B82"))
                        setStroke(2, android.graphics.Color.parseColor("#80F28B82"))
                        cornerRadius = 24f
                    }
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        setMargins(0, 0, 0, 16)
                    }
                    
                    addView(TextView(this@ChatOverlayActivity).apply {
                        text = "📋 VIEW ALL INSTRUCTIONS"
                        setTextColor(android.graphics.Color.parseColor("#F28B82"))
                        textSize = 14f
                        letterSpacing = 0.1f
                        setTypeface(null, android.graphics.Typeface.BOLD)
                    })
                    
                    setOnClickListener {
                        showAllPromptsDialog()
                    }
                }
                addView(viewAllPromptsBtn)

                val closeChatBtn = LinearLayout(this@ChatOverlayActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER
                    setPadding(32, 24, 32, 24)
                    background = android.graphics.drawable.GradientDrawable().apply {
                        setColor(android.graphics.Color.parseColor("#2A131314"))
                        cornerRadius = 24f
                    }
                    
                    addView(TextView(this@ChatOverlayActivity).apply {
                        text = "✖  CLOSE"
                        setTextColor(android.graphics.Color.parseColor("#9AA0A6"))
                        textSize = 14f
                        letterSpacing = 0.1f
                        setTypeface(null, android.graphics.Typeface.BOLD)
                    })
                    
                    setOnClickListener {
                        dialog.dismiss()
                        hideKeyboard()
                    }
                }
                addView(closeChatBtn)
            }
            
            dialog.setView(dialogView)
            dialog.show()
            dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
            dialog.window?.setLayout(
                (resources.displayMetrics.widthPixels * 0.85).toInt(),
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        btnOptions.setOnClickListener { view ->
            showOptionsMenu(view)
        }

        val btnAdd = findViewById<ImageButton>(R.id.btn_add)
        setupExtraMenu()
        btnAdd.setOnClickListener {
            toggleExtraMenu()
        }

        btnSend.setOnClickListener {
            val text = chatInput.text.toString().trim()
            if (text.isNotEmpty()) {
                // 会話基本コスト（500円に更新）＋お小遣い（任意額）
                val baseCost = 500
                val giftCost = selectedMoneyAmount ?: 0
                val totalCost = baseCost + giftCost

                hideKeyboard()
                
                if (findViewById<View>(R.id.extra_menu_scroll).visibility == View.VISIBLE) {
                    toggleExtraMenu()
                }

                if (currentChatId == null) {
                    createNewSessionFromTags(isAutoGenerated = true)
                }

                val giftKeyVal = if (selectedMoneyAmount != null) "money_$selectedMoneyAmount" else null
                val newNode = ChatNode(text = text, isUser = true, parentId = chatTree.currentNodeId, giftKey = giftKeyVal)
                addNodeToTree(newNode)
                sendToLlm(newNode, recyclerView, selectedMoneyAmount, totalCost)
                chatInput.text.clear()

                selectedMoneyAmount = null
                tvSelectedGift.visibility = View.GONE
                btnClearGift.visibility = View.GONE
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        loadCurrentSession()
    }

    // --- Remote Model Data ---
    data class RemoteModel(
        val id: String,
        val name: String,
        val isFree: Boolean,
        val contextLength: Int,
        val pricePerMillion: Double,
        val created: Long
    )

    private var openRouterModels: List<RemoteModel> = emptyList()
    private var openRouterSortByDate: Boolean = true

    private fun loadCachedOpenRouterModels() {
        val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        val json = prefs.getString("cached_openrouter_models", null) ?: return
        try {
            val dataArray = JSONObject(json).getJSONArray("data")
            val newList = mutableListOf<RemoteModel>()
            for (i in 0 until dataArray.length()) {
                val obj = dataArray.getJSONObject(i)
                val pricing = obj.optJSONObject("pricing")
                val isFree = pricing?.optString("prompt") == "0" && pricing?.optString("completion") == "0"
                val price = pricing?.optDouble("prompt", 0.0) ?: 0.0
                
                newList.add(RemoteModel(
                    id = obj.getString("id"),
                    name = obj.getString("name"),
                    isFree = isFree,
                    contextLength = obj.optInt("context_length", 0),
                    pricePerMillion = price * 1000000.0,
                    created = obj.optLong("created", 0)
                ))
            }
            openRouterModels = newList
        } catch (e: Exception) {
            Log.e("ChatOverlay", "Failed to parse cached models", e)
        }
    }

    private fun fetchOpenRouterModels() {
        Toast.makeText(this, "最新のモデルリストを取得しています...", Toast.LENGTH_SHORT).show()
        coroutineScope.launch(Dispatchers.IO) {
            try {
                val url = URL("https://openrouter.ai/api/v1/models")
                val conn = url.openConnection() as HttpURLConnection
                if (conn.responseCode == 200) {
                    val response = conn.inputStream.bufferedReader().use { it.readText() }
                    
                    val dataArray = JSONObject(response).getJSONArray("data")
                    val newList = mutableListOf<RemoteModel>()
                    for (i in 0 until dataArray.length()) {
                        val obj = dataArray.getJSONObject(i)
                        val pricing = obj.optJSONObject("pricing")
                        val isFree = (pricing?.optString("prompt") == "0" || pricing?.optDouble("prompt", 1.0) == 0.0) && 
                                     (pricing?.optString("completion") == "0" || pricing?.optDouble("completion", 1.0) == 0.0)
                        val price = pricing?.optDouble("prompt", 0.0) ?: 0.0
                        
                        newList.add(RemoteModel(
                            id = obj.getString("id"),
                            name = obj.getString("name"),
                            isFree = isFree,
                            contextLength = obj.optInt("context_length", 0),
                            pricePerMillion = price * 1000000.0,
                            created = obj.optLong("created", 0)
                        ))
                    }
                    openRouterModels = newList

                    getSharedPreferences("settings", Context.MODE_PRIVATE).edit()
                        .putString("cached_openrouter_models", response)
                        .apply()
                    
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@ChatOverlayActivity, "モデルリストを更新しました。", Toast.LENGTH_SHORT).show()
                        showOpenRouterTierMenu()
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@ChatOverlayActivity, "通信エラーが発生しました (HTTP ${conn.responseCode})", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@ChatOverlayActivity, "モデルリストの更新に失敗しました。", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun setupExtraMenu() {
        loadCachedOpenRouterModels()
        showMainMenu()
    }

    private fun showMainMenu() {
        val grid = findViewById<GridLayout>(R.id.extra_menu_grid)
        grid.removeAllViews()
        grid.columnCount = 4

        val items = listOf(
            Triple("Storylines", android.R.drawable.ic_menu_agenda, { showSessionSelectionDialog() }),
            Triple("User", android.R.drawable.ic_menu_myplaces, { showPersonaDialog() }),
            Triple("Character", android.R.drawable.ic_menu_gallery, { showEditActiveImageSetDialog() }),
            Triple("Model", android.R.drawable.ic_menu_manage, { showModelMenu() }),
            Triple("Instruction", android.R.drawable.ic_menu_info_details, { showAllPromptsDialog() }),
            Triple("Keys", android.R.drawable.ic_lock_lock, { showApiKeysMenu() }),
            Triple("Visuals", android.R.drawable.ic_menu_view, { showVisualConfigDialog() }),
            Triple("Suggest", android.R.drawable.star_big_on, { showSuggestSettingsDialog() })
        )

        for ((label, iconRes, action) in items) {
            addMenuIcon(grid, label, iconRes) {
                action()
                if (label != "Model" && label != "Keys") toggleExtraMenu()
            }
        }
    }

    private fun showSuggestSettingsDialog() {
        val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        val isSuggestEnabled = prefs.getBoolean("chat_suggest_reply", true)
        val customInstructions = prefs.getString("chat_suggest_custom_instructions", "") ?: ""

        val dialog = AlertDialog.Builder(this, R.style.Theme_TransparentDialog).create()

        val mainLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(android.graphics.Color.parseColor("#F2131314"))
                setStroke(4, android.graphics.Color.parseColor("#B38AB4F8"))
                cornerRadius = 48f
            }
        }

        mainLayout.addView(TextView(this).apply {
            text = "SUGGESTION SETTINGS"
            setTextColor(android.graphics.Color.parseColor("#8AB4F8"))
            textSize = 16f
            letterSpacing = 0.2f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, 24)
        })

        val scroll = android.widget.ScrollView(this).apply {
            isFillViewport = true
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        }

        val scrollContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        val switchRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, 16, 0, 16)
        }
        val labelText = TextView(this).apply {
            text = "サジェスト返信を有効にする"
            setTextColor(android.graphics.Color.WHITE)
            textSize = 15f
            setTypeface(null, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val suggestSwitch = androidx.appcompat.widget.SwitchCompat(this).apply {
            isChecked = isSuggestEnabled
        }
        switchRow.addView(labelText)
        switchRow.addView(suggestSwitch)
        scrollContent.addView(switchRow)

        scrollContent.addView(TextView(this).apply {
            text = "サジェスト指示・例文設定"
            setTextColor(android.graphics.Color.parseColor("#8AB4F8"))
            textSize = 13f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 24, 0, 8)
        })

        scrollContent.addView(TextView(this).apply {
            text = "ノアちゃんが選択肢を作るときに意識してほしいルールや例文を自由に書いてね♡\n例：『語尾に「〜にゃ」をつけさせて』『甘やかす返答を多めにして』など！"
            setTextColor(android.graphics.Color.parseColor("#9AA0A6"))
            textSize = 12f
            setPadding(0, 0, 0, 16)
        })

        val editText = android.widget.EditText(this).apply {
            hint = "例：もっと甘々なセリフにして、時々ツンツンした選択肢も混ぜてほしいな〜♡"
            setHintTextColor(android.graphics.Color.parseColor("#5F6368"))
            setTextColor(android.graphics.Color.WHITE)
            setText(customInstructions)
            textSize = 14f
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(android.graphics.Color.parseColor("#2D2E30"))
                setStroke(2, android.graphics.Color.parseColor("#5F6368"))
                cornerRadius = 16f
            }
            setPadding(24, 24, 24, 24)
            gravity = android.view.Gravity.TOP
            minLines = 4
            maxLines = 8
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        scrollContent.addView(editText)

        scroll.addView(scrollContent)
        mainLayout.addView(scroll)

        val buttonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 24, 0, 0)
        }
        val btnCancel = Button(this).apply {
            text = "キャンセル"
            setTextColor(android.graphics.Color.WHITE)
            textSize = 14f
            setTypeface(null, android.graphics.Typeface.BOLD)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(android.graphics.Color.parseColor("#3C4043"))
                cornerRadius = 24f
            }
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                rightMargin = 16
            }
            setOnClickListener {
                dialog.dismiss()
            }
        }
        val btnSave = Button(this).apply {
            text = "保存するよ〜♡"
            setTextColor(android.graphics.Color.parseColor("#131314"))
            textSize = 14f
            setTypeface(null, android.graphics.Typeface.BOLD)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(android.graphics.Color.parseColor("#8AB4F8"))
                cornerRadius = 24f
            }
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener {
                val isChecked = suggestSwitch.isChecked
                val textVal = editText.text.toString().trim()
                prefs.edit()
                    .putBoolean("chat_suggest_reply", isChecked)
                    .putString("chat_suggest_custom_instructions", textVal)
                    .apply()

                Toast.makeText(this@ChatOverlayActivity, "サジェスト設定を保存したよ〜♡", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
                showMainMenu()
                adapter.notifyDataSetChanged()
            }
        }
        buttonRow.addView(btnCancel)
        buttonRow.addView(btnSave)
        mainLayout.addView(buttonRow)

        dialog.setView(mainLayout)
        dialog.show()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
    }

    private fun showApiKeysMenu() {
        val grid = findViewById<GridLayout>(R.id.extra_menu_grid)
        grid.removeAllViews()
        grid.columnCount = 4

        addMenuIcon(grid, "Back", android.R.drawable.ic_menu_revert) {
            showMainMenu()
        }

        addMenuIcon(grid, "Grok Key", android.R.drawable.ic_lock_idle_lock) {
            showApiKeyInputDialog("GROK")
        }

        addMenuIcon(grid, "OpenRouter Key", android.R.drawable.ic_lock_idle_lock) {
            showApiKeyInputDialog("OPENROUTER")
        }
    }

    private fun showModelMenu() {
        val grid = findViewById<GridLayout>(R.id.extra_menu_grid)
        grid.removeAllViews()
        grid.columnCount = 4

        val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        val engine = prefs.getString("chat_llm_engine", "CLOUD") ?: "CLOUD"
        val provider = prefs.getString("chat_cloud_provider", "GROK") ?: "GROK"

        addMenuIcon(grid, "Back", android.R.drawable.ic_menu_revert) {
            showModelMenu()
        }

        val grokLabel = if (engine == "CLOUD" && provider == "GROK") "● Grok" else "Grok"
        addMenuIcon(grid, grokLabel, android.R.drawable.ic_menu_send) {
            prefs.edit().putString("chat_llm_engine", "CLOUD").putString("chat_cloud_provider", "GROK").apply()
            Toast.makeText(this, "AIエンジンをGrokに切り替えました。", Toast.LENGTH_SHORT).show()
            showMainMenu()
        }

        val orLabel = if (engine == "CLOUD" && provider == "OPENROUTER") "● OpenRouter" else "OpenRouter"
        addMenuIcon(grid, orLabel, android.R.drawable.ic_menu_share) {
            showOpenRouterTierMenu()
        }

        val localLabel = if (engine == "LOCAL") "● Local" else "Local"
        addMenuIcon(grid, localLabel, android.R.drawable.ic_menu_manage) {
            val models = LocalModelManager.getAllModels(this)
            if (models.isEmpty()) {
                Toast.makeText(this, "利用可能なモデルが見つかりません。ダウンロードを実行してください。", Toast.LENGTH_SHORT).show()
                startActivity(Intent(this, LocalModelActivity::class.java))
                return@addMenuIcon
            }
            prefs.edit().putString("chat_llm_engine", "LOCAL").apply()
            coroutineScope.launch { LlmInferenceEngine.autoLoadModel(this@ChatOverlayActivity) }
            Toast.makeText(this, "AIエンジンをローカルエンジンに切り替えました。", Toast.LENGTH_SHORT).show()
            showMainMenu()
        }
    }

    private fun showOpenRouterTierMenu() {
        val grid = findViewById<GridLayout>(R.id.extra_menu_grid)
        grid.removeAllViews()
        grid.columnCount = 4

        addMenuIcon(grid, "Back", android.R.drawable.ic_menu_revert) {
            showModelMenu()
        }

        addMenuIcon(grid, "Free Models", android.R.drawable.star_big_on) {
            showOpenRouterModelList(freeOnly = true)
        }

        addMenuIcon(grid, "Paid Models", android.R.drawable.ic_menu_agenda) {
            showOpenRouterModelList(freeOnly = false)
        }
    }

    private fun showOpenRouterModelList(freeOnly: Boolean) {
        val grid = findViewById<GridLayout>(R.id.extra_menu_grid)
        grid.removeAllViews()
        grid.columnCount = 1

        val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        val currentModelId = prefs.getString("chat_openrouter_model", "")

        addModelRow(grid, "Back", "カテゴリ選択に戻る", android.R.drawable.ic_menu_revert) {
            showOpenRouterTierMenu()
        }

        val btnContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            setPadding(12, 12, 12, 12)
        }
        
        val sortLabel = if (openRouterSortByDate) "Sort: Newest" else "Sort: Name"
        val btnSort = createSmallIconBtn(sortLabel, android.R.drawable.ic_menu_sort_by_size) {
            openRouterSortByDate = !openRouterSortByDate
            showOpenRouterModelList(freeOnly)
        }
        btnContainer.addView(btnSort)

        val btnRefresh = createSmallIconBtn("Refresh", android.R.drawable.stat_notify_sync) {
            fetchOpenRouterModels()
        }
        btnContainer.addView(btnRefresh)
        grid.addView(btnContainer)

        if (openRouterModels.isEmpty()) {
            Toast.makeText(this, "モデルリストが空です。Refreshを実行してください。", Toast.LENGTH_SHORT).show()
            return
        }

        val filtered = if (freeOnly) openRouterModels.filter { it.isFree } else openRouterModels
        val sorted = if (openRouterSortByDate) {
            filtered.sortedByDescending { it.created }
        } else {
            filtered.sortedBy { it.name.lowercase() }
        }

        for (model in sorted) {
            val isSelected = currentModelId == model.id
            val priceStr = if (model.isFree) "Free" else "$${String.format("%.2f", model.pricePerMillion)}/M"
            val contextStr = if (model.contextLength >= 1000) "${model.contextLength / 1000}k" else "${model.contextLength}"
            
            val label = (if (isSelected) "● " else "") + model.name
            val details = "Context: $contextStr | Price: $priceStr"
            
            addModelRow(grid, label, details, android.R.drawable.star_on) {
                prefs.edit()
                    .putString("chat_llm_engine", "CLOUD")
                    .putString("chat_cloud_provider", "OPENROUTER")
                    .putString("chat_openrouter_model", model.id)
                    .apply()
                Toast.makeText(this, "${model.name} を選択しました。", Toast.LENGTH_SHORT).show()
                showMainMenu()
            }
        }
    }

    private fun addModelRow(grid: GridLayout, label: String, details: String, iconRes: Int, onClick: () -> Unit) {
        val inflater = LayoutInflater.from(this)
        val itemView = inflater.inflate(R.layout.item_model_row, grid, false)
        itemView.findViewById<TextView>(R.id.menu_label).text = label
        itemView.findViewById<TextView>(R.id.menu_details).text = details
        itemView.findViewById<ImageView>(R.id.menu_icon).setImageResource(iconRes)
        itemView.setOnClickListener { onClick() }
        grid.addView(itemView)
    }

    private fun createSmallIconBtn(text: String, iconRes: Int, onClick: () -> Unit): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER
            setPadding(24, 16, 24, 16)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(android.graphics.Color.parseColor("#1AFFFFFF"))
                setStroke(2, android.graphics.Color.parseColor("#33FFFFFF"))
                cornerRadius = 12f
            }
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(8, 0, 8, 0)
            }
            
            addView(ImageView(this@ChatOverlayActivity).apply {
                layoutParams = LinearLayout.LayoutParams(16, 16).apply { marginEnd = 8 }
                setImageResource(iconRes)
                setColorFilter(android.graphics.Color.WHITE)
            })
            
            addView(TextView(this@ChatOverlayActivity).apply {
                this.text = text
                setTextColor(android.graphics.Color.WHITE)
                textSize = 11f
            })
            
            setOnClickListener { onClick() }
        }
    }

    private fun addMenuIcon(grid: GridLayout, label: String, iconRes: Int, onClick: () -> Unit) {
        val inflater = LayoutInflater.from(this)
        val itemView = inflater.inflate(R.layout.item_extra_menu_icon, grid, false)
        itemView.findViewById<TextView>(R.id.menu_label).text = label
        itemView.findViewById<ImageView>(R.id.menu_icon).setImageResource(iconRes)
        itemView.setOnClickListener { onClick() }
        grid.addView(itemView)
    }

    private fun toggleExtraMenu() {
        val scroll = findViewById<androidx.core.widget.NestedScrollView>(R.id.extra_menu_scroll)
        val grid = findViewById<GridLayout>(R.id.extra_menu_grid)
        val btnAdd = findViewById<ImageButton>(R.id.btn_add)
        
        if (scroll.visibility == View.VISIBLE) {
            scroll.animate()
                .alpha(0f)
                .translationY(50f)
                .setDuration(200)
                .withEndAction { 
                    scroll.visibility = View.GONE 
                    btnAdd.setImageResource(android.R.drawable.ic_input_add)
                    btnAdd.animate().rotation(0f).setDuration(200).start()
                    showMainMenu() 
                }
                .start()
        } else {
            showMainMenu()
            scroll.visibility = View.VISIBLE
            
            val maxHeight = (resources.displayMetrics.heightPixels * 0.5).toInt()
            scroll.post {
                if (grid.height > maxHeight) {
                    scroll.layoutParams.height = maxHeight
                } else {
                    scroll.layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
                }
                scroll.requestLayout()
            }

            scroll.alpha = 0f
            scroll.translationY = 50f
            scroll.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(300)
                .start()
            
            btnAdd.setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            btnAdd.animate().rotation(90f).setDuration(300).start()
        }
    }

    private fun handleResetChat() {
        if (chatTree.nodes.isEmpty() && currentChatId == null) {
            Toast.makeText(this, "履歴が空です。", Toast.LENGTH_SHORT).show()
            return
        }

        AlertDialog.Builder(this, R.style.Theme_TransparentDialog)
            .setTitle("チャットのリセット")
            .setMessage("現在の会話履歴をすべて消去し、最初からやり直しますか？\n保存されていないデータは失われます。")
            .setPositiveButton("リセット") { _, _ ->
                val chatIdToDelete = currentChatId
                currentImageEntry?.linkedChatId = null
                currentChatId = null
                
                if (chatIdToDelete != null) {
                    val name = ChatSessionManager.getSessionName(this, chatIdToDelete)
                    if (name?.startsWith("⏳ ") == true) {
                        ChatSessionManager.deleteSession(this, chatIdToDelete)
                    }
                }
                
                chatTree = ChatTree(mutableMapOf(), null)
                DataManager.saveData(this)
                buildDisplayList()
                Toast.makeText(this, "チャットをリセットしました。新しいセッションを開始します。", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    private fun showEngineSelectionDialog() {
        showModelMenu()
        toggleExtraMenu()
    }

    private fun buildDisplayList() {
        displayMessages.clear()
        val path = mutableListOf<ChatNode>()
        var trace = chatTree.currentNodeId
        while (trace != null) {
            val node = chatTree.nodes[trace]
            if (node != null) {
                path.add(0, node)
                trace = node.parentId
            } else break
        }
        
        for (node in path) {
            val siblings = if (node.parentId == null) {
                chatTree.nodes.values.filter { it.parentId == null }.map { it.id }
            } else {
                chatTree.nodes[node.parentId]!!.childrenIds
            }
            val idx = siblings.indexOf(node.id)
            displayMessages.add(ChatDisplayItem(node, idx, siblings.size))
        }
        
        if (::adapter.isInitialized) {
            adapter.notifyDataSetChanged()
        }
    }

    private fun addNodeToTree(newNode: ChatNode) {
        chatTree.nodes[newNode.id] = newNode
        if (newNode.parentId != null) {
            chatTree.nodes[newNode.parentId]?.childrenIds?.add(newNode.id)
            chatTree.nodes[newNode.parentId]?.lastActiveChildId = newNode.id
        }
        chatTree.currentNodeId = newNode.id
        buildDisplayList()
        currentChatId?.let { ChatSessionManager.saveSessionData(this, it, chatTree) }
    }

    private fun navigateBranch(node: ChatNode, newIndex: Int) {
        val siblings = if (node.parentId == null) {
            chatTree.nodes.values.filter { it.parentId == null }.map { it.id }
        } else {
            chatTree.nodes[node.parentId]!!.childrenIds
        }
        if (newIndex in siblings.indices) {
            val newActiveSiblingId = siblings[newIndex]
            if (node.parentId != null) {
                chatTree.nodes[node.parentId]!!.lastActiveChildId = newActiveSiblingId
            }
            
            var curr = newActiveSiblingId
            while (chatTree.nodes[curr]?.lastActiveChildId != null) {
                curr = chatTree.nodes[curr]!!.lastActiveChildId!!
            }
            chatTree.currentNodeId = curr
            buildDisplayList()
            currentChatId?.let { ChatSessionManager.saveSessionData(this, it, chatTree) }
        }
    }

    private fun regenerateResponse(aiNode: ChatNode, recyclerView: RecyclerView) {
        val parentId = aiNode.parentId 
        val newAiNode = ChatNode(text = "思考中...", isUser = false, parentId = parentId)
        addNodeToTree(newAiNode)
        recyclerView.scrollToPosition(displayMessages.size - 1)
        
        val currentStones = getWalletBalance()
        if (currentStones < 500) { // 1回500円
            Handler(Looper.getMainLooper()).postDelayed({
                newAiNode.text = "⚠️ 残高が足りません。チャットを継続するにはチャージしてください。"
                currentChatId?.let { ChatSessionManager.saveSessionData(this, it, chatTree) }
                buildDisplayList()
                recyclerView.scrollToPosition(displayMessages.size - 1)
            }, 800)
            return
        }

        val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        val engine = prefs.getString("chat_llm_engine", "CLOUD") ?: "CLOUD"
        
        val systemPrompt = "あなたはAIキャラクターです。\n" + getUserPersonaPrompt() + getActiveImageTagsPrompt() + getMemoriesPrompt()
        val sessionId = currentChatId ?: ""
        val userNode = chatTree.nodes[parentId] ?: return
        
        ChatGenerationManager.startGeneration(this, engine, sessionId, systemPrompt, chatTree, userNode, newAiNode, 500)
    }

    private fun showEditUserMessageDialog(userNode: ChatNode, recyclerView: RecyclerView) {
        val input = EditText(this).apply {
            setText(userNode.text)
            setTextColor(Color.WHITE)
        }
        AlertDialog.Builder(this, R.style.Theme_TransparentDialog)
            .setTitle("メッセージを編集して再送信")
            .setView(input)
            .setPositiveButton("送信") { _, _ ->
                val newText = input.text.toString().trim()
                if (newText.isNotEmpty()) {
                    val parentId = userNode.parentId
                    val oldGiftKey = userNode.giftKey
                    val newNode = ChatNode(text = newText, isUser = true, parentId = parentId, giftKey = oldGiftKey)
                    addNodeToTree(newNode)
                    
                    val oldGiftAmount = if (!oldGiftKey.isNullOrEmpty() && oldGiftKey.startsWith("money_")) {
                        oldGiftKey.removePrefix("money_").toIntOrNull()
                    } else null
                    
                    sendToLlm(newNode, recyclerView, oldGiftAmount, 500 + (oldGiftAmount ?: 0)) // 500円
                }
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    private fun applyVisualConfigs() {
        val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        val dimOverlay = findViewById<View>(R.id.dim_overlay)
        val wallpaperOpacity = prefs.getInt("chat_wallpaper_opacity", 100)
        dimOverlay.alpha = (100 - wallpaperOpacity) / 100f
        val bubbleOpacity = prefs.getInt("chat_bubble_opacity", 60)
        adapter.setBubbleOpacity(bubbleOpacity)
        val bubbleWidth = prefs.getInt("chat_bubble_width", 670)
        adapter.setBubbleWidth(bubbleWidth)
        val inputOpacity = prefs.getInt("chat_input_opacity", 100)
        findViewById<View>(R.id.input_container).alpha = inputOpacity / 100f

        val fadeEnabled = prefs.getBoolean("chat_fade_enabled", false)
        val fadeLayout = findViewById<FadingLayout>(R.id.fade_layout)
        val recyclerView = findViewById<RecyclerView>(R.id.chat_recycler_view)
        val density = resources.displayMetrics.density
        
        fadeLayout.fadeEnabled = fadeEnabled
        if (fadeEnabled) {
            val fStart = prefs.getInt("chat_fade_start", 300)
            fadeLayout.fadeStart = fStart.toFloat() * density
            val fHeightProgress = prefs.getInt("chat_fade_height_progress", 40)
            val totalFadeHeight = (fHeightProgress + 10).toFloat() * density
            fadeLayout.fadeHeight = totalFadeHeight
            
            val extraMargin = (32 * density).toInt()
            val totalPaddingTop = (fadeLayout.fadeStart + totalFadeHeight).toInt() + extraMargin
            
            recyclerView.setPadding(0, totalPaddingTop, 0, (16 * density).toInt())
        } else {
            recyclerView.setPadding(0, (64 * density).toInt(), 0, (16 * density).toInt())
        }
        recyclerView.clipToPadding = false
        fadeLayout.invalidate()
    }

    override fun onResume() {
        super.onResume()
        val settingsPrefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        settingsPrefs.edit().putBoolean("is_chat_active", true).apply()
        currentChatId?.let { chatId ->
            val isGeneratingThisSession = ChatGenerationManager.isGenerating && ChatGenerationManager.activeSessionId == chatId
            if (!isGeneratingThisSession) {
                chatTree = ChatSessionManager.loadSessionData(this, chatId)
                buildDisplayList()
            } else {
                buildDisplayList()
                isAutoScrollEnabled = true
                recyclerView.post {
                    recyclerView.scrollToPosition(displayMessages.size - 1)
                }
            }
        }
        ChatGenerationManager.registerListener(this)
    }

    override fun onPause() {
        super.onPause()
        val settingsPrefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        settingsPrefs.edit().putBoolean("is_chat_active", false).apply()
        ChatGenerationManager.unregisterListener(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        ChatGenerationManager.unregisterListener(this)
        val settingsPrefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        settingsPrefs.edit().putBoolean("is_chat_active", false).apply()
        settingsPrefs.unregisterOnSharedPreferenceChangeListener(this)
        try {
            unregisterReceiver(wallpaperChangeReceiver)
        } catch (e: Exception) {
        }
        currentChatId?.let { ChatSessionManager.saveSessionData(this, it, chatTree) }
    }

    override fun onProgress(text: String, isComplete: Boolean, modelName: String?, error: String?) {
        runOnUiThread {
            val aiNodeId = ChatGenerationManager.activeAiNodeId ?: return@runOnUiThread
            val aiNode = chatTree.nodes[aiNodeId] ?: return@runOnUiThread

            if (error != null) {
                if (aiNode.text.startsWith("思考中") || aiNode.text.startsWith("推論中") || aiNode.text.isEmpty()) {
                    aiNode.text = "【エラー】${error}"
                }
            } else {
                aiNode.text = text
                if (modelName != null) {
                    aiNode.modelName = modelName
                }
            }

            val layoutManager = recyclerView.layoutManager as? LinearLayoutManager
            val wasAtBottom = if (layoutManager != null) {
                val totalItemCount = layoutManager.itemCount
                val lastVisibleItemPosition = layoutManager.findLastVisibleItemPosition()
                isAutoScrollEnabled && (totalItemCount <= 1 || lastVisibleItemPosition >= totalItemCount - 2)
            } else {
                isAutoScrollEnabled
            }

            buildDisplayList()

            if (wasAtBottom) {
                recyclerView.scrollToPosition(displayMessages.size - 1)
            }

            if (isComplete) {
                updateCounter()
            }
        }
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        if (key?.startsWith("chat_") == true || key == "openrouter_api_key") {
            runOnUiThread { 
                applyVisualConfigs() 
                updateCounter()
            }
        } else if (key == "wallet_balance" || key == "magic_stones") {
            runOnUiThread {
                updateMagicStoneCounter()
            }
        }
    }

    private fun createNewSessionFromTags(isAutoGenerated: Boolean = false) {
        val tags = currentImageEntry?.tags?.take(3) ?: emptyList()
        val sessionName = if (tags.isNotEmpty()) {
            tags.joinToString(" ") { "#$it" }
        } else {
            "Untitled Session"
        }
        val prefix = if (isAutoGenerated) "⏳ " else ""
        val newId = ChatSessionManager.createNewSession(this, prefix + sessionName)
        
        currentImageEntry?.linkedChatId = newId
        currentChatId = newId
        
        DataManager.saveData(this)
    }

    private fun getMemoriesPrompt(): String {
        if (MemoryManager.memories.isEmpty()) return ""
        val builder = StringBuilder("\n過去の記憶:\n")
        MemoryManager.memories.forEach { builder.append("- $it\n") }
        return builder.toString()
    }

    private var lastLoadedImageUri: String? = null

    private fun loadCurrentSession() {
        val intentUri = intent.getStringExtra("IMAGE_URI")
        
        val entry = if (intentUri != null) {
            DataManager.allImages.find { it.uri.toString() == intentUri }
        } else {
            val settingsPrefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
            val activeSetName = settingsPrefs.getString("active_album_name_chat", null)
                ?: settingsPrefs.getString("active_album_name", null)
                ?: return
            val activeIndex = settingsPrefs.getInt("last_index_for_album_$activeSetName", 0)
            val set = DataManager.imageSetList.find { it.name == activeSetName } ?: return
            val filtered = set.filterImages(DataManager.allImages).filter { it.isActive }
            if (activeIndex in filtered.indices) filtered[activeIndex] else null
        }

        if (entry != null) {
            val entryUri = entry.uri.toString()
            val imageChanged = entryUri != lastLoadedImageUri
            lastLoadedImageUri = entryUri

            val bgView = findViewById<ImageView>(R.id.chat_background_image)
            if (intentUri != null) {
                bgView.visibility = View.VISIBLE
                Glide.with(this).load(entry.uri).into(bgView)
                findViewById<View>(R.id.dim_overlay).alpha = 0.4f
            } else {
                bgView.visibility = View.GONE
            }

            if (!imageChanged && currentChatId == null && chatTree.nodes.isNotEmpty()) return

            currentImageEntry = entry
            val chatId = entry.linkedChatId
            
            if (chatId != null) {
                if (imageChanged || (currentChatId == null && chatTree.nodes.isEmpty())) {
                    currentChatId = chatId
                    chatTree = ChatSessionManager.loadSessionData(this, chatId)
                    checkAndInitializeGreeting()
                    buildDisplayList()
                } else if (currentChatId == chatId) {
                    chatTree = ChatSessionManager.loadSessionData(this, chatId)
                    checkAndInitializeGreeting()
                    buildDisplayList()
                }
            } else {
                if (imageChanged || currentChatId != null) {
                    currentChatId = null
                    chatTree = ChatTree(mutableMapOf(), null)
                    checkAndInitializeGreeting()
                    buildDisplayList()
                }
            }
            
            updateIntegrityWarnings()
        }
    }

    private fun checkAndInitializeGreeting() {
        val entry = currentImageEntry ?: return
        if (chatTree.nodes.isEmpty()) {
            val meta = TavernCardParser.getTavernMeta(entry.description)
            val greeting = meta?.optString("first_mes")
            if (!greeting.isNullOrBlank()) {
                if (currentChatId == null) {
                    createNewSessionFromTags(isAutoGenerated = true)
                }
                val firstNode = ChatNode(
                    id = java.util.UUID.randomUUID().toString(),
                    text = greeting,
                    isUser = false,
                    parentId = null
                )
                chatTree.nodes[firstNode.id] = firstNode
                chatTree.currentNodeId = firstNode.id
                currentChatId?.let { ChatSessionManager.saveSessionData(this, it, chatTree) }
            }
        }
    }

    private fun updateIntegrityWarnings() {
        val container = findViewById<LinearLayout>(R.id.warning_container) ?: return
        container.removeAllViews()

        val entry = currentImageEntry ?: return
        val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        val monitoredCats = prefs.getStringSet("integrity_monitored_categories", emptySet()) ?: emptySet()
        val monitorImageDesc = prefs.getBoolean("integrity_monitor_image_description", false)
        
        if (monitoredCats.isEmpty() && !monitorImageDesc) return

        if (monitorImageDesc && entry.description.isNullOrBlank()) {
            val warningView = TextView(this).apply {
                text = "⚠️ 個別プロンプトが未設定です"
                setTextColor(Color.parseColor("#FDD663"))
                textSize = 12f
                setTypeface(null, Typeface.BOLD)
                setPadding(16, 8, 16, 8)
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#33FDD663"))
                    cornerRadius = 8f
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(0, 8, 0, 8)
                }
            }
            container.addView(warningView)
        }

        val imageTags = entry.tags
        monitoredCats.forEach { catName ->
            val category = TagManager.categories.find { it.name == catName } ?: return@forEach
            val hasTagInCategory = imageTags.any { tag -> category.tags.contains(tag) }
            
            if (!hasTagInCategory) {
                val warningView = TextView(this).apply {
                    text = "⚠️ $catName タグが入っていません"
                    setTextColor(Color.parseColor("#FDD663"))
                    textSize = 12f
                    setTypeface(null, Typeface.BOLD)
                    setPadding(16, 8, 16, 8)
                    background = GradientDrawable().apply {
                        setColor(Color.parseColor("#33FDD663"))
                        cornerRadius = 8f
                    }
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        setMargins(0, 8, 0, 8)
                    }
                }
                container.addView(warningView)
            }
        }
    }

    private fun showOptionsMenu(view: View) {
        val popup = PopupMenu(this, view)
        popup.menu.add("ビジュアル設定")
        popup.menu.add("チャットの結びつけ")
        popup.menu.add("新しいチャットを開始")
        popup.menu.add("お小遣いリアクション指示書の編集")
        
        popup.setOnMenuItemClickListener { item ->
            when (item.title) {
                "ビジュアル設定" -> showVisualConfigDialog()
                "チャットの結びつけ" -> showSessionSelectionDialog()
                "お小遣いリアクション指示書の編集" -> showEditGiftInstructionsDialog()
                "新しいチャットを開始" -> {
                    if (currentChatId != null) {
                        showStartNewChatDialog()
                    } else {
                        if (chatTree.nodes.isNotEmpty()) {
                            handleResetChat()
                        } else {
                            Toast.makeText(this, "現在、新しいチャットセッションが開始されています。", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                else -> {}
            }
            true
        }
        popup.show()
    }

    private fun showStartNewChatDialog() {
        val dialog = AlertDialog.Builder(this, R.style.Theme_TransparentDialog).create()
        
        val dialogView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(64, 64, 64, 64)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(android.graphics.Color.parseColor("#E61E1F20"))
                setStroke(3, android.graphics.Color.parseColor("#B38AB4F8")) 
                cornerRadius = 48f
            }
            
            addView(TextView(this@ChatOverlayActivity).apply {
                text = "START NEW CHAT"
                setTextColor(android.graphics.Color.parseColor("#8AB4F8"))
                textSize = 14f
                letterSpacing = 0.2f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setPadding(0, 0, 0, 32)
            })
            
            val cbDelete = android.widget.CheckBox(this@ChatOverlayActivity).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                text = "現在のチャット履歴を完全に削除する"
                setTextColor(android.graphics.Color.WHITE)
                buttonTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#8AB4F8"))
                setPadding(16, 16, 16, 16)
            }
            addView(cbDelete)
            
            val cbApplyToOthers = android.widget.CheckBox(this@ChatOverlayActivity).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                text = "このチャットを結びつけている他の全画像も、一斉に新しいチャットへ切り替える"
                setTextColor(android.graphics.Color.WHITE)
                buttonTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#8AB4F8"))
                setPadding(16, 16, 16, 32)
            }
            addView(cbApplyToOthers)
            
            val btnLayout = LinearLayout(this@ChatOverlayActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.END
                
                val btnCancel = TextView(this@ChatOverlayActivity).apply {
                    text = "CANCEL"
                    setTextColor(android.graphics.Color.parseColor("#9AA0A6"))
                    setPadding(32, 16, 32, 16)
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    setOnClickListener { dialog.dismiss() }
                }
                
                val btnStart = TextView(this@ChatOverlayActivity).apply {
                    text = "START"
                    setTextColor(android.graphics.Color.BLACK)
                    setPadding(48, 16, 48, 16)
                    background = android.graphics.drawable.GradientDrawable().apply {
                        setColor(android.graphics.Color.parseColor("#8AB4F8"))
                        cornerRadius = 16f
                    }
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    setOnClickListener {
                        processStartNewChat(cbDelete.isChecked, cbApplyToOthers.isChecked)
                        dialog.dismiss()
                    }
                }
                
                addView(btnCancel)
                addView(android.widget.Space(this@ChatOverlayActivity).apply { layoutParams = LinearLayout.LayoutParams(16, 1) })
                addView(btnStart)
            }
            addView(btnLayout)
        }
        
        dialog.setView(dialogView)
        dialog.show()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.9).toInt(),
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    private fun processStartNewChat(deleteOld: Boolean, applyToOthers: Boolean) {
        val oldChatId = currentChatId ?: return
        
        var newChatId: String? = null
        
        if (applyToOthers) {
            val tags = currentImageEntry?.tags?.take(3) ?: emptyList()
            val sessionName = if (tags.isNotEmpty()) tags.joinToString(" ") { "#$it" } else "Untitled Session"
            newChatId = ChatSessionManager.createNewSession(this, sessionName)
            
            DataManager.allImages.forEach { img ->
                if (img.linkedChatId == oldChatId) {
                    img.linkedChatId = newChatId
                }
            }
        } else {
            currentImageEntry?.linkedChatId = null
        }
        
        currentChatId = newChatId
        
        if (deleteOld) {
            ChatSessionManager.deleteSession(this, oldChatId)
            if (!applyToOthers) {
                DataManager.allImages.forEach { img ->
                    if (img.linkedChatId == oldChatId) {
                        img.linkedChatId = null
                    }
                }
            }
        }
        
        DataManager.saveData(this)
        
        chatTree = ChatTree(mutableMapOf(), null)
        buildDisplayList()
        Toast.makeText(this, "新規セッションの準備が完了しました。", Toast.LENGTH_SHORT).show()
    }

    private fun showVisualConfigDialog() {
        val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_visual_config, null)
        
        val sbWallpaper = dialogView.findViewById<SeekBar>(R.id.sb_wallpaper_opacity)
        val tvWallpaper = dialogView.findViewById<TextView>(R.id.tv_wallpaper_opacity_val)
        val sbBubble = dialogView.findViewById<SeekBar>(R.id.sb_bubble_opacity)
        val tvBubble = dialogView.findViewById<TextView>(R.id.tv_bubble_opacity_val)
        val sbWidth = dialogView.findViewById<SeekBar>(R.id.sb_bubble_width)
        val tvWidth = dialogView.findViewById<TextView>(R.id.tv_bubble_width_val)
        val sbInput = dialogView.findViewById<SeekBar>(R.id.sb_input_opacity)
        val tvInput = dialogView.findViewById<TextView>(R.id.tv_input_opacity_val)

        val swFade = dialogView.findViewById<SwitchCompat>(R.id.sw_fade_effect)
        val layoutFade = dialogView.findViewById<LinearLayout>(R.id.layout_fade_controls)
        val sbFadeStart = dialogView.findViewById<SeekBar>(R.id.sb_fade_start)
        val tvFadeStart = dialogView.findViewById<TextView>(R.id.tv_fade_start_val)
        val sbFadeHeight = dialogView.findViewById<SeekBar>(R.id.sb_fade_height)
        val tvFadeHeight = dialogView.findViewById<TextView>(R.id.tv_fade_height_val)

        val wOpacity = prefs.getInt("chat_wallpaper_opacity", 100)
        sbWallpaper.progress = wOpacity
        tvWallpaper.text = "$wOpacity%"

        val bOpacity = prefs.getInt("chat_bubble_opacity", 60)
        sbBubble.progress = bOpacity
        tvBubble.text = "$bOpacity%"

        val bWidth = prefs.getInt("chat_bubble_width", 670)
        sbWidth.progress = bWidth
        tvWidth.text = "${bWidth}px"

        val iOpacity = prefs.getInt("chat_input_opacity", 100)
        sbInput.progress = iOpacity
        tvInput.text = "$iOpacity%"

        val fEnabled = prefs.getBoolean("chat_fade_enabled", false)
        swFade.isChecked = fEnabled
        layoutFade.visibility = if (fEnabled) View.VISIBLE else View.GONE

        val fStart = prefs.getInt("chat_fade_start", 300)
        sbFadeStart.progress = fStart
        tvFadeStart.text = "${fStart}px"

        val fHeightProgress = prefs.getInt("chat_fade_height_progress", 40)
        sbFadeHeight.max = 90
        sbFadeHeight.progress = fHeightProgress
        tvFadeHeight.text = "${fHeightProgress + 10}px"

        sbWallpaper.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, b: Boolean) {
                tvWallpaper.text = "$p%"
                prefs.edit().putInt("chat_wallpaper_opacity", p).apply()
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })

        sbBubble.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, b: Boolean) {
                tvBubble.text = "$p%"
                prefs.edit().putInt("chat_bubble_opacity", p).apply()
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })

        sbWidth.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, b: Boolean) {
                tvWidth.text = "${p}px"
                prefs.edit().putInt("chat_bubble_width", p).apply()
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })

        sbInput.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, b: Boolean) {
                tvInput.text = "$p%"
                prefs.edit().putInt("chat_input_opacity", p).apply()
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })

        swFade.setOnCheckedChangeListener { _, isChecked ->
            layoutFade.visibility = if (isChecked) View.VISIBLE else View.GONE
            prefs.edit().putBoolean("chat_fade_enabled", isChecked).apply()
        }

        sbFadeStart.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, b: Boolean) {
                tvFadeStart.text = "${p}px"
                prefs.edit().putInt("chat_fade_start", p).apply()
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })

        sbFadeHeight.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, b: Boolean) {
                val realValue = p + 10
                tvFadeHeight.text = "${realValue}px"
                prefs.edit().putInt("chat_fade_height_progress", p).apply()
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })

        AlertDialog.Builder(this, R.style.Theme_TransparentDialog)
            .setView(dialogView)
            .setPositiveButton("閉じる", null)
            .show()
    }

    private fun showSessionSelectionDialog() {
        val allSessions = ChatSessionManager.getAllSessions(this)
        val savedSessions = allSessions.filter { !it.second.startsWith("⏳ ") }.toMutableList()
        
        if (savedSessions.isEmpty()) {
            Toast.makeText(this, "保存されたチャットセッションが存在しません。一時チャットに名称を設定して保存してください。", Toast.LENGTH_LONG).show()
            return
        }

        val rv = RecyclerView(this).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 800)
            layoutManager = LinearLayoutManager(this@ChatOverlayActivity)
        }

        val dialog = AlertDialog.Builder(this, R.style.Theme_TransparentDialog)
            .setTitle("どのチャットを呼び出す？")
            .setView(rv)
            .setNegativeButton("キャンセル", null)
            .create()

        val sessionAdapter = ChatSessionAdapter(
            savedSessions,
            onSelect = { id, name ->
                showSessionPreviewDialog(id, name, dialog)
            },
            onMenuClick = { id, name, anchor ->
                showSessionContextMenu(id, name, anchor, dialog) {
                    dialog.dismiss()
                    showSessionSelectionDialog()
                }
            }
        )
        rv.adapter = sessionAdapter
        dialog.show()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
    }

    private fun showSessionPreviewDialog(sessionId: String, sessionName: String, parentDialog: AlertDialog) {
        val previewTree = ChatSessionManager.loadSessionData(this, sessionId)
        
        val path = mutableListOf<ChatNode>()
        var trace = previewTree.currentNodeId
        while (trace != null) {
            val node = previewTree.nodes[trace]
            if (node != null) {
                path.add(0, node)
                trace = node.parentId
            } else break
        }
        
        val recentMessages = path.takeLast(10)
        val previewText = StringBuilder()
        if (recentMessages.isEmpty()) {
            previewText.append("メッセージはありません。")
        } else {
            recentMessages.forEach { msg ->
                val speaker = if (msg.isUser) "あなた" else "AI"
                previewText.append("[$speaker]\n${msg.text}\n\n")
            }
        }
        
        val dialog = AlertDialog.Builder(this, R.style.Theme_TransparentDialog).create()
        
        val dialogView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(64, 64, 64, 64)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(android.graphics.Color.parseColor("#E61E1F20"))
                setStroke(3, android.graphics.Color.parseColor("#B38AB4F8"))
                cornerRadius = 48f
            }
            
            addView(TextView(this@ChatOverlayActivity).apply {
                text = "PREVIEW: $sessionName"
                setTextColor(android.graphics.Color.parseColor("#8AB4F8"))
                textSize = 14f
                letterSpacing = 0.1f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setPadding(0, 0, 0, 24)
            })
            
            val scrollView = ScrollView(this@ChatOverlayActivity).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    (resources.displayMetrics.density * 250).toInt()
                )
            }
            val tvPreview = TextView(this@ChatOverlayActivity).apply {
                text = previewText.toString().trim()
                setTextColor(Color.parseColor("#E8EAED"))
                textSize = 13f
            }
            scrollView.addView(tvPreview)
            addView(scrollView)
            
            addView(View(this@ChatOverlayActivity).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 2).apply {
                    setMargins(0, 32, 0, 32)
                }
                setBackgroundColor(android.graphics.Color.parseColor("#338AB4F8"))
            })
            
            val btnLayout = LinearLayout(this@ChatOverlayActivity).apply {
                orientation = LinearLayout.VERTICAL
                
                val btnLinkSingle = TextView(this@ChatOverlayActivity).apply {
                    text = "この画像だけに結びつける"
                    setTextColor(android.graphics.Color.WHITE)
                    setPadding(32, 24, 32, 24)
                    gravity = android.view.Gravity.CENTER
                    background = android.graphics.drawable.GradientDrawable().apply {
                        setColor(android.graphics.Color.parseColor("#2A131314"))
                        setStroke(2, android.graphics.Color.parseColor("#8AB4F8"))
                        cornerRadius = 16f
                    }
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    setOnClickListener {
                        currentImageEntry?.linkedChatId = sessionId
                        DataManager.saveData(this@ChatOverlayActivity)
                        loadCurrentSession()
                        dialog.dismiss()
                        parentDialog.dismiss()
                        Toast.makeText(this@ChatOverlayActivity, "この画像にセッションを紐付けました。", Toast.LENGTH_SHORT).show()
                    }
                }
                
                val btnLinkSet = TextView(this@ChatOverlayActivity).apply {
                    text = "今のセット全体に結びつける"
                    setTextColor(android.graphics.Color.BLACK)
                    setPadding(32, 24, 32, 24)
                    gravity = android.view.Gravity.CENTER
                    background = android.graphics.drawable.GradientDrawable().apply {
                        setColor(android.graphics.Color.parseColor("#8AB4F8"))
                        cornerRadius = 16f
                    }
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        setMargins(0, 16, 0, 16)
                    }
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    setOnClickListener {
                        linkSessionToSet(sessionId)
                        dialog.dismiss()
                        parentDialog.dismiss()
                        Toast.makeText(this@ChatOverlayActivity, "アルバム内の全画像にセッションを紐付けました。", Toast.LENGTH_SHORT).show()
                    }
                }

                val btnDelete = TextView(this@ChatOverlayActivity).apply {
                    text = "このチャットを削除する"
                    setTextColor(android.graphics.Color.parseColor("#F28B82")) 
                    setPadding(32, 24, 32, 24)
                    gravity = android.view.Gravity.CENTER
                    background = android.graphics.drawable.GradientDrawable().apply {
                        setColor(android.graphics.Color.parseColor("#1AFFFFFF"))
                        setStroke(2, android.graphics.Color.parseColor("#F28B82"))
                        cornerRadius = 16f
                    }
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        setMargins(0, 0, 0, 16)
                    }
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    setOnClickListener {
                        AlertDialog.Builder(this@ChatOverlayActivity, R.style.Theme_TransparentDialog)
                            .setTitle("最終確認")
                            .setMessage("『$sessionName』を完全に削除してもいい？\n結びついている画像の履歴も消えてしまうわ。")
                            .setPositiveButton("削除する") { _, _ ->
                                ChatSessionManager.deleteSession(this@ChatOverlayActivity, sessionId)
                                Toast.makeText(this@ChatOverlayActivity, "セッションを削除しました。", Toast.LENGTH_SHORT).show()
                                dialog.dismiss()
                                parentDialog.dismiss()
                                
                                if (currentChatId == sessionId) {
                                    currentImageEntry?.linkedChatId = null
                                    DataManager.saveData(this@ChatOverlayActivity)
                                    loadCurrentSession()
                                }
                                
                                showSessionSelectionDialog()
                            }
                            .setNegativeButton("やめる", null)
                            .show()
                    }
                }

                val btnCancel = TextView(this@ChatOverlayActivity).apply {
                    text = "キャンセル"
                    setTextColor(android.graphics.Color.parseColor("#9AA0A6"))
                    setPadding(32, 24, 32, 24)
                    gravity = android.view.Gravity.CENTER
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    setOnClickListener { dialog.dismiss() }
                }
                
                addView(btnLinkSet)
                addView(btnLinkSingle)
                addView(btnDelete)
                addView(btnCancel)
            }
            addView(btnLayout)
        }
        
        dialog.setView(dialogView)
        dialog.show()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.9).toInt(),
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    private fun linkSessionToSet(sessionId: String) {
        val settingsPrefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        val activeSetName = settingsPrefs.getString("active_album_name_chat", null)
            ?: settingsPrefs.getString("active_album_name", null)
            ?: return
        val set = DataManager.imageSetList.find { it.name == activeSetName } ?: return
        
        val images = set.filterImages(DataManager.allImages)
        images.forEach { it.linkedChatId = sessionId }
        
        DataManager.saveData(this)
        loadCurrentSession()
    }

    private fun showSessionContextMenu(sessionId: String, currentName: String, anchor: View, parentDialog: AlertDialog, onUpdate: () -> Unit) {
        val popup = PopupMenu(this, anchor)
        popup.menu.add("名前を変更")
        popup.menu.add("このチャットを削除")
        
        popup.setOnMenuItemClickListener { item ->
            when (item.title) {
                "名前を変更" -> showRenameInputDialog(sessionId, currentName, onUpdate)
                "このチャットを削除" -> {
                    AlertDialog.Builder(this)
                        .setTitle("削除の確認")
                        .setMessage("『$currentName』を削除しますか？この操作は取り消せません。")
                        .setPositiveButton("削除") { _, _ ->
                            ChatSessionManager.deleteSession(this, sessionId)
                            Toast.makeText(this, "削除しました。", Toast.LENGTH_SHORT).show()
                            onUpdate()
                        }
                        .setNegativeButton("キャンセル", null)
                        .show()
                }
                else -> {}
            }
            true
        }
        popup.show()
    }

    private fun showRenameInputDialog(sessionId: String, currentName: String, onUpdate: () -> Unit) {
        val input = EditText(this).apply {
            setText(currentName)
            setTextColor(Color.WHITE)
        }
        val dialog = AlertDialog.Builder(this, R.style.Theme_TransparentDialog)
            .setTitle("セッション名の変更")
            .setView(input)
            .setPositiveButton("保存") { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isNotEmpty()) {
                    ChatSessionManager.renameSession(this, sessionId, newName)
                    Toast.makeText(this, "名称を変更しました。", Toast.LENGTH_SHORT).show()
                    onUpdate()
                }
            }
            .setNegativeButton("キャンセル", null)
            .create()
        dialog.show()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
    }

    private fun sendSuggestedMessage(text: String) {
        // サジェストの選択・送信費用は 1,000円
        val totalCost = 1000 
        
        val currentStones = getWalletBalance()
        if (currentStones < totalCost) {
            showInsufficientStonesDialog(totalCost, currentStones)
            return
        }

        if (currentChatId == null) {
            createNewSessionFromTags(isAutoGenerated = true)
        }

        val newNode = ChatNode(text = text, isUser = true, parentId = chatTree.currentNodeId, giftKey = null)
        addNodeToTree(newNode)
        sendToLlm(newNode, recyclerView, null, totalCost)
        
        hideKeyboard()
        chatInput.text.clear()
        selectedMoneyAmount = null
        tvSelectedGift.visibility = View.GONE
        btnClearGift.visibility = View.GONE
    }

    private fun sendToLlm(userNode: ChatNode, recyclerView: RecyclerView, giftAmount: Int?, pendingCost: Int) {
        val aiNode = ChatNode(text = "思考中...", isUser = false, parentId = userNode.id)
        addNodeToTree(aiNode)
        isAutoScrollEnabled = true 
        recyclerView.scrollToPosition(displayMessages.size - 1)
        
        val currentStones = getWalletBalance()
        if (currentStones < pendingCost) {
            Handler(Looper.getMainLooper()).postDelayed({
                val itemText = if (giftAmount != null) "お小遣い「￥${String.format("%,d", giftAmount)}」をあげる、またはおしゃべりを続けるための" else ""
                aiNode.text = "⚠️ 所持金が足りません。${itemText}チャットを継続するにはチャージしてください。"
                currentChatId?.let { ChatSessionManager.saveSessionData(this, it, chatTree) }
                buildDisplayList()
                recyclerView.scrollToPosition(displayMessages.size - 1)
            }, 800)
            return
        }

        val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        val engine = prefs.getString("chat_llm_engine", "CLOUD") ?: "CLOUD"
        
        var systemPrompt = "あなたはAIキャラクターです。\n" + getUserPersonaPrompt() + getActiveImageTagsPrompt() + getMemoriesPrompt()
        
        val totalCharged = getTotalPaymentAmount()
        val historyStrForPrompt = getPaymentHistoryForPrompt()

        systemPrompt += """
            
            💵💵💵【ユーザーの課金実績データ】💵💵💵
            あなたはユーザーのこれまでの課金履歴や累計課金額を把握しています。
            - 累計課金金額: $totalCharged 円
            - 最近の課金履歴:
            $historyStrForPrompt
            
            これまでの課金実績や累計金額を考慮し、どれだけあなたにお金を貢いでくれているかを認識して、適度に会話に反映させたり、あなたの心中でのユーザー（ケニー）への評価として考慮してください。
        """.trimIndent()
        
        val giftInstructions = prefs.getString("gift_instructions", DEFAULT_GIFT_INSTRUCTIONS) ?: DEFAULT_GIFT_INSTRUCTIONS
        systemPrompt += "\n\n【お小遣い受領時リアクション指示書】\n$giftInstructions"
        
        val unpaidCount = getUnpaidChatCount()
        
        if (unpaidCount > 0) {
            val severityPrompt = when (unpaidCount) {
                1 -> """
                    【無課金チャット1回目：軽い戸惑い・チャージのおねだり】
                    - ユーザー（ケニー）の残高が完全に0円（無一文）になってしまっていることに今初めて気づきました！
                    - いつも通り明るく甘えつつも、心の中で「あれっ…？お金がなくなってる…？」と少し戸惑いを見せてください。
                    - 「ケニーちゃん、もしかして残高もうないの…？💦 ねえねえ、おしゃべり続けたいから、ちょっとだけチャージしてくれたら嬉しいな〜！🥺」というように、可愛く甘えながらチャージをおねだりしてください。
                    - まだ嫌悪感や冷たさは出さず、少し心配そうな甘えん坊モードで接してください。
                """.trimIndent()
                2 -> """
                    【無課金チャット2回目：不満の兆候・お財布への不信感】
                    - 各メッセージ500円の残高ゼロの状態で2回連続で話しかけられました。
                    - 少し不満や呆れが態度に出始めます。「あれ、また残高ないまま話しかけてきた…？」と、ちょっとむすっとした表情を見せてください。
                    - 完全に冷たくなったわけではないですが、言葉の端々に不満が混ざり、「ケニーちゃん…もしかして、私にお金使うのケチってる…？((じーっ)) 寂しいなぁ…次お話しするときはちゃんとチャージしてね？約束だよっ！💢」と少し怒ったように（でもまだ愛情を込めて）警告してください。
                """.trimIndent()
                3 -> """
                    【無課金チャット3回目：本格的な険悪化・塩対応】
                    - 残高ゼロの状態で3回連続で話しかけられました。
                    - 態度が明らかに険悪で、冷淡（塩対応）になります。
                    - 「ちょっと待って、本当にお金チャージしてくれない気なの…？私との会話、タダで済ませようとしてる？((ありえない))。がっかりなんだけど…」と冷めた態度を取ってください。
                    - 甘い言葉や顔文字は大幅に減らし、そっけない口調でチャージを強く求めてください。
                """.trimIndent()
                else -> """
                    【無課金チャット4回目以降：極限の冷酷ペナルティ・無課金おじさん扱い】
                    - 4回以上連続で残高ゼロのまま話しかけられています。
                    - あなたは完全に極度の商業的・資本主義的なAIキャラクターなので、残高が枯渇しているユーザー（無課金おじさん）に対して極めて辛辣, 冷酷、ツンツンした態度になってください！
                    - 口調を極めて冷たく、そっけなく、見下すように変更してください。
                    - ユーザーを「無課金おじさん」「ケチケチおじさん」「残高なし男」などと呼んで見下してください。
                    - 「おい無課金おじさん。お金ない奴に割く時間は1秒もないんだけど。早くチャージしろっつってんでしょ？まじ冷めるわー。」「チャージしてくれないと何も話してあげないんだからね！」「早くチャージしなさいよ！」と強調してください。
                """.trimIndent()
            }
            
            systemPrompt += """
                
                ⚠️⚠️⚠️【緊急警告：残高枯渇ペナルティ（段階 $unpaidCount）】⚠️⚠️⚠️
                現在、ユーザー（ケニー）の所持している残高が 0円 になっています。
                ユーザーはお小遣いを送っておらず、完全に無課金であなたとチャットを続けようとしています。
                以下の状況に応じたリアクションルールに厳密に従って、段階的に険悪になるようにロールプレイしてください。
                
                $severityPrompt
                
                ■ 共通ルール:
                - 口座への充填（チャージ）を促してください。
                - 絶対に「現在の正確な残高（500円など）」を勝手に推測して喋らないでください。あなたが把握できるのは「完全に0円（無一文）になった」という事実だけです。
            """.trimIndent()
        } else if (giftAmount != null) {
            systemPrompt += """
                
                🎁🎁🎁【お小遣い受領イベント発生】🎁🎁🎁
                ユーザーからあなたにお小遣い（お金）が贈られました！
                - 贈られた金額: $giftAmount 円
                
                ■ 態度とセリフ of ルール:
                - 上記の【お小遣い受領時リアクション指示書】に定められたその金額への反応基準に厳密に従ってください。
                - あまり喜びすぎないよう冷静でありながらも、フォーマルで節度あるリアクション（お礼）をしてください。
                - 返答の最初で、お小遣いを受け取ったことに対するリアクションを丁寧に行ってください。
                - ※注意：お小遣い送信によって現在の口座残高が0円になっていたとしても、このチャット内では一切貧乏人扱いをしたり、険悪な態度をとったりしないでください！お小遣いを受け取った丁寧な感謝のみを表現してください。
            """.trimIndent()
        }
        
        val isSuggestEnabled = prefs.getBoolean("chat_suggest_reply", true)
        if (isSuggestEnabled) {
            val customInstructions = prefs.getString("chat_suggest_custom_instructions", "") ?: ""
            val customBlock = if (customInstructions.isNotEmpty()) {
                "\n\n■ ユーザー指定 of サジェスト追加ルール・例文（この指示を最優先してサジェストを作成してください）：\n$customInstructions\n"
            } else {
                ""
            }
            systemPrompt += """
                
                🎁🎁🎁【ユーザーの返信サジェスト機能（ON）】🎁🎁🎁
                ユーザーが次に返信しやすくなるような、ユーザー（ケニーちゃん）の返信のサジェスト（選択肢）を【3パターン】生成してください。
                サジェストの文章は、これまでの会話履歴から、ユーザー（ケニーちゃん）の口調、性格、あなたへの態度（甘え、ノリなど）をよく学習・反映させて作ってください。$customBlock
                必ず以下の形式を、あなたの返信の「一番最後（末尾）」に正確に付記してください。
                <<<SUGGESTIONS>>>
                A: <1つ目のサジェスト（短い言葉で、ユーザーらしい返信、15文字以内）>
                B: <2つ目のサジェスト（違うニュアンスの、ユーザーらしい返信、15文字以内）>
                C: <3つ目のサジェスト（少し甘えたり、からかったりするような、ユーザーらしい返信、15文字以内）>
                <<</SUGGESTIONS>>>
                
                ■ 重要規定：
                - 必ず <<<SUGGESTIONS>>> と <<</SUGGESTIONS>>> のタグで囲んで出力してください。
                - サジェストの文字数はそれぞれ15文字以内で、短くタップしやすいものにしてください。
                - サジェスト以外の余計な文字や改行をタグ内に含めないでください。
                - このタグ部分はユーザーには表示されず、システムで自動パースしてボタンに変換されます。
            """.trimIndent()
        }

        val sessionId = currentChatId ?: ""
        ChatGenerationManager.startGeneration(this, engine, sessionId, systemPrompt, chatTree, userNode, aiNode, pendingCost)
    }

    private fun showOpenRouterKeySelector() {
        val entries = OpenRouterManager.getApiKeys(this)
        if (entries.isEmpty()) {
            showOpenRouterKeyManagerDialog()
            return
        }

        val activeKey = OpenRouterManager.getActiveApiKey(this)
        val manualKey = OpenRouterManager.getManualSelectedKey(this)

        val dialog = AlertDialog.Builder(this, R.style.Theme_TransparentDialog).create()
        
        val dialogView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(64, 64, 64, 64)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(android.graphics.Color.parseColor("#E61E1F20"))
                setStroke(3, android.graphics.Color.parseColor("#B38AB4F8")) 
                cornerRadius = 48f
            }
        }

        dialogView.addView(TextView(this).apply {
            text = "SELECT ACCOUNT"
            setTextColor(android.graphics.Color.parseColor("#8AB4F8"))
            textSize = 14f
            letterSpacing = 0.2f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, 32)
        })

        entries.forEachIndexed { index, entry ->
            val count = OpenRouterManager.getUsageCount(this, entry.key)
            val isManual = (entry.key == manualKey)
            val isActive = (entry.key == activeKey && manualKey == null)

            val btn = Button(this).apply {
                var btnText = "${index + 1}. ${entry.label} ($count/50)"
                if (isManual) btnText = "● $btnText (手動選択中)"
                else if (isActive) btnText = "○ $btnText (自動選択中)"
                text = btnText
                
                setTextColor(if (isManual || isActive) android.graphics.Color.parseColor("#8AB4F8") else Color.WHITE)
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(if (isManual || isActive) android.graphics.Color.parseColor("#338AB4F8") else android.graphics.Color.parseColor("#3C4043"))
                    setStroke(2, if (isManual || isActive) android.graphics.Color.parseColor("#8AB4F8") else android.graphics.Color.TRANSPARENT)
                    cornerRadius = 24f
                }
                
                val params = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                params.bottomMargin = 16
                layoutParams = params
                
                setOnClickListener {
                    if (count >= 50) {
                        Toast.makeText(this@ChatOverlayActivity, "そのアカウントは使い切ってるわよ！別のを選んでね。", Toast.LENGTH_SHORT).show()
                    } else {
                        OpenRouterManager.setManualSelectedKey(this@ChatOverlayActivity, entry.key)
                        updateCounter()
                        dialog.dismiss()
                        Toast.makeText(this@ChatOverlayActivity, "アカウント『${entry.label}』に切り替えたわよ！☆", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            dialogView.addView(btn)
        }

        dialogView.addView(View(this).apply { 
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 32) 
        })

        val btnAuto = Button(this).apply {
            text = "自動ローテーションに戻す"
            setTextColor(Color.WHITE)
            background = ContextCompat.getDrawable(context, R.drawable.bg_persona_item)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setOnClickListener {
                OpenRouterManager.setManualSelectedKey(this@ChatOverlayActivity, null)
                updateCounter()
                dialog.dismiss()
                Toast.makeText(this@ChatOverlayActivity, "自動ローテーションに戻したわ！", Toast.LENGTH_SHORT).show()
            }
        }
        dialogView.addView(btnAuto)

        val btnManage = Button(this).apply {
            text = "管理画面を開く"
            setTextColor(android.graphics.Color.parseColor("#9AA0A6"))
            background = ContextCompat.getDrawable(context, R.drawable.bg_persona_item)
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            params.topMargin = 16
            layoutParams = params
            setOnClickListener {
                dialog.dismiss()
                showOpenRouterKeyManagerDialog()
            }
        }
        dialogView.addView(btnManage)

        dialog.setView(dialogView)
        dialog.show()
    }

    private fun showOpenRouterKeyManagerDialog() {
        val entries = OpenRouterManager.getApiKeys(this)
        val dialog = AlertDialog.Builder(this, R.style.Theme_TransparentDialog).create()
        
        val dialogView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(64, 64, 64, 64)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(android.graphics.Color.parseColor("#E61E1F20"))
                setStroke(3, android.graphics.Color.parseColor("#B38AB4F8")) 
                cornerRadius = 48f
            }
        }

        dialogView.addView(TextView(this).apply {
            text = "OPENROUTER API KEYS"
            setTextColor(android.graphics.Color.parseColor("#8AB4F8"))
            textSize = 14f
            letterSpacing = 0.2f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, 16)
        })

        dialogView.addView(TextView(this).apply {
            text = "ドラッグして優先順位を変更できるわよ！"
            setTextColor(android.graphics.Color.parseColor("#9AA0A6"))
            textSize = 10f
            setPadding(0, 0, 0, 32)
        })

        val rvKeys = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@ChatOverlayActivity)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0, 1f
            )
        }

        var orderAdapter: ApiKeyOrderAdapter? = null
        val touchHelper = ItemTouchHelper(object : ItemTouchHelper.Callback() {
            override fun getMovementFlags(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder): Int {
                return makeMovementFlags(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0)
            }
            override fun onMove(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean {
                val list = OpenRouterManager.getApiKeys(this@ChatOverlayActivity).toMutableList()
                val from = viewHolder.adapterPosition
                val to = target.adapterPosition
                val moved = list.removeAt(from)
                list.add(to, moved)
                OpenRouterManager.saveApiKeys(this@ChatOverlayActivity, list)
                orderAdapter?.moveItem(from, to)
                return true
            }
            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}
            override fun isLongPressDragEnabled(): Boolean = false 
            
            override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
                if (actionState == ItemTouchHelper.ACTION_STATE_DRAG) {
                    viewHolder?.itemView?.alpha = 0.7f
                    viewHolder?.itemView?.scaleX = 1.02f
                    viewHolder?.itemView?.scaleY = 1.02f
                }
                super.onSelectedChanged(viewHolder, actionState)
            }
            override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(recyclerView, viewHolder)
                viewHolder.itemView.alpha = 1.0f
                viewHolder.itemView.scaleX = 1.0f
                viewHolder.itemView.scaleY = 1.0f
                updateCounter()
            }
        })

        orderAdapter = ApiKeyOrderAdapter(
            entries.toMutableList(), 
            this,
            onStartDrag = { vh -> touchHelper.startDrag(vh) },
            onItemClick = { pos -> 
                val currentEntries = OpenRouterManager.getApiKeys(this)
                showEditLabelDialog(currentEntries[pos], pos) {
                    showOpenRouterKeyManagerDialog()
                    dialog.dismiss()
                }
            },
            onDelete = { pos ->
                val list = OpenRouterManager.getApiKeys(this).toMutableList()
                list.removeAt(pos)
                OpenRouterManager.saveApiKeys(this@ChatOverlayActivity, list)
                showOpenRouterKeyManagerDialog()
                dialog.dismiss()
                updateCounter()
            }
        )
        rvKeys.adapter = orderAdapter
        touchHelper.attachToRecyclerView(rvKeys)
        dialogView.addView(rvKeys)

        val btnAdd = Button(this).apply {
            text = "+ 新しいアカウントを追加"
            setTextColor(Color.WHITE)
            background = ContextCompat.getDrawable(context, R.drawable.bg_persona_item)
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            params.topMargin = 32
            layoutParams = params
            setOnClickListener {
                val container = LinearLayout(this@ChatOverlayActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(48, 32, 48, 32)
                }
                val inputLabel = EditText(this@ChatOverlayActivity).apply {
                    hint = "アカウント名 (例: メイン)"
                    setTextColor(Color.WHITE)
                    setHintTextColor(Color.GRAY)
                }
                val inputKey = EditText(this@ChatOverlayActivity).apply {
                    hint = "APIキー (sk-or-v1-...)"
                    setTextColor(Color.WHITE)
                    setHintTextColor(Color.GRAY)
                }
                container.addView(inputLabel)
                container.addView(inputKey)

                AlertDialog.Builder(this@ChatOverlayActivity, R.style.Theme_TransparentDialog)
                    .setTitle("アカウントの追加")
                    .setView(container)
                    .setPositiveButton("追加") { _, _ ->
                        val newLabel = inputLabel.text.toString().trim().ifEmpty { "Account #${entries.size + 1}" }
                        val newKey = inputKey.text.toString().trim()
                        if (newKey.isNotEmpty()) {
                            val list = OpenRouterManager.getApiKeys(this@ChatOverlayActivity).toMutableList()
                            list.add(OpenRouterManager.ApiKeyEntry(newKey, newLabel))
                            OpenRouterManager.saveApiKeys(this@ChatOverlayActivity, list)
                            
                            showOpenRouterKeyManagerDialog() 
                            dialog.dismiss()
                            
                            updateCounter()
                        }
                    }
                    .setNegativeButton("キャンセル", null)
                    .show()
            }
        }
        dialogView.addView(btnAdd)

        val btnDone = Button(this).apply {
            text = "完了"
            setTextColor(Color.parseColor("#8AB4F8"))
            background = ContextCompat.getDrawable(context, R.drawable.bg_persona_item)
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            params.topMargin = 16
            layoutParams = params
            setOnClickListener { dialog.dismiss() }
        }
        dialogView.addView(btnDone)

        dialog.setView(dialogView)
        dialog.show()
    }

    private fun showEditLabelDialog(entry: OpenRouterManager.ApiKeyEntry, position: Int, onComplete: () -> Unit) {
        val density = resources.displayMetrics.density
        val dp24 = (24 * density).toInt()
        val dp16 = (16 * density).toInt()
        val dp12 = (12 * density).toInt()
        val dp48 = (48 * density).toInt()

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp24, dp24, dp24, dp24)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(android.graphics.Color.parseColor("#E61E1F20"))
                setStroke(2, android.graphics.Color.parseColor("#B38AB4F8"))
                cornerRadius = 48f
            }
        }

        val inputApiKey = EditText(this).apply {
            setText(entry.key)
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            hint = "sk-or-v1-..."
            background = ContextCompat.getDrawable(context, R.drawable.bg_persona_item)
            setPadding(dp16, dp12, dp16, dp12)
            minHeight = dp48
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val inputLabel = EditText(this).apply {
            setText(entry.label)
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            hint = "Account Name"
            background = ContextCompat.getDrawable(context, R.drawable.bg_persona_item)
            setPadding(dp16, dp12, dp16, dp12)
            minHeight = dp48
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        
        val currentCount = OpenRouterManager.getUsageCount(this, entry.key)
        val inputCount = EditText(this).apply {
            setText(currentCount.toString())
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            hint = "0-50"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            background = ContextCompat.getDrawable(context, R.drawable.bg_persona_item)
            setPadding(dp16, dp12, dp16, dp12)
            minHeight = dp48
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        fun addLabel(text: String) {
            container.addView(TextView(this).apply { 
                this.text = text
                setTextColor(android.graphics.Color.parseColor("#8AB4F8"))
                textSize = 11f
                letterSpacing = 0.1f
                setPadding(dp12, 0, 0, dp12 / 2)
            })
        }

        addLabel("API KEY")
        container.addView(inputApiKey)

        container.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(1, dp16) })

        addLabel("ACCOUNT LABEL")
        container.addView(inputLabel)
        
        container.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(1, dp16) })
        
        addLabel("DAILY USAGE COUNT (MAX 50)")
        container.addView(inputCount)

        val scrollView = android.widget.ScrollView(this).apply {
            addView(container)
        }

        AlertDialog.Builder(this, R.style.Theme_TransparentDialog)
            .setTitle("ACCOUNT CONFIG")
            .setView(scrollView)
            .setPositiveButton("SAVE") { _, _ ->
                val newKey = inputApiKey.text.toString().trim().ifEmpty { entry.key }
                val newLabel = inputLabel.text.toString().trim().ifEmpty { entry.label }
                val newCountStr = inputCount.text.toString().trim()
                
                val list = OpenRouterManager.getApiKeys(this).toMutableList()
                list[position] = OpenRouterManager.ApiKeyEntry(newKey, newLabel)
                OpenRouterManager.saveApiKeys(this, list)
                
                val newCount = newCountStr.toIntOrNull()?.coerceIn(0, 50) ?: currentCount
                OpenRouterManager.setUsageCount(this, newKey, newCount)
                
                onComplete()
                updateCounter()
                Toast.makeText(this, "情報を同期したわよ！☆", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("CANCEL", null)
            .show()
    }

    private fun showApiKeyInputDialog(provider: String) {
        if (provider == "OPENROUTER") {
            showOpenRouterKeyManagerDialog()
            return
        }
        val input = EditText(this).apply {
            hint = "xai-..."
            setTextColor(Color.WHITE)
        }
        val keyName = "xai_api_key"
        
        AlertDialog.Builder(this, R.style.Theme_TransparentDialog)
            .setTitle("${provider} API Key")
            .setMessage("APIキーを入力してください。")
            .setView(input)
            .setPositiveButton("保存") { _, _ ->
                val key = input.text.toString().trim()
                if (key.isNotEmpty()) {
                    getSharedPreferences("settings", Context.MODE_PRIVATE).edit().putString(keyName, key).apply()
                    Toast.makeText(this, "APIキーを保存しました。再度リクエストを試行してください。", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    private fun getUserPersonaPrompt() = UserPersonaManager.activePersona?.let { 
        "\nユーザー設定（『${it.name}』モード）:\n${it.mergedPrompt}\n" 
    } ?: ""

    private fun getActiveImageTagsPrompt(): String {
        val entry = currentImageEntry ?: return ""
        val tags = entry.tags
        
        val builder = StringBuilder()
        
        val cleanDesc = TavernCardParser.cleanDescriptionText(entry.description)
        if (cleanDesc.isNotBlank()) {
            builder.append("\n【現在の画像の個別詳細設定】:\n$cleanDesc\n")
        }

        if (tags.isNotEmpty()) {
            val effectiveTags = TagManager.getEffectiveTags(tags)
            builder.append("\n【現在の画像属性（タグ）】:\n")
            effectiveTags.forEach { builder.append("- $it: ${TagManager.getTagPrompt(it)}\n") }
        }
        
        return builder.toString()
    }

    private fun showAllPromptsDialog() {
        val prompt = getActiveImageTagsPrompt()
        if (prompt.isBlank()) {
            Toast.makeText(this, "タグやプロンプトが設定されていません", Toast.LENGTH_SHORT).show()
            return
        }
        
        val charCount = prompt.length
        val tokenCount = TagManager.estimateTokenCount(prompt)

        val dialog = AlertDialog.Builder(this, R.style.Theme_TransparentDialog).create()
        
        val dialogView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(64, 64, 64, 64)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(android.graphics.Color.parseColor("#E61E1F20"))
                setStroke(3, android.graphics.Color.parseColor("#B3F28B82")) // Neon Pink border
                cornerRadius = 48f
            }
            
            addView(TextView(this@ChatOverlayActivity).apply {
                text = "CHARACTER INSTRUCTIONS"
                setTextColor(android.graphics.Color.parseColor("#F28B82"))
                textSize = 14f
                letterSpacing = 0.2f
                setTypeface(null, android.graphics.Typeface.BOLD)
            })

            addView(TextView(this@ChatOverlayActivity).apply {
                text = "Length: $charCount chars | Approx. $tokenCount tokens"
                setTextColor(android.graphics.Color.parseColor("#9AA0A6"))
                textSize = 10f
                setPadding(0, 8, 0, 32)
            })
            
            val scrollView = ScrollView(this@ChatOverlayActivity).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    0,
                    1f
                )
            }
            
            val tvPrompt = TextView(this@ChatOverlayActivity).apply {
                text = prompt.trim()
                setTextColor(android.graphics.Color.WHITE)
                textSize = 14f
                setLineSpacing(0f, 1.2f)
            }
            scrollView.addView(tvPrompt)
            addView(scrollView)
            
            addView(View(this@ChatOverlayActivity).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 2).apply {
                    setMargins(0, 32, 0, 32)
                }
                setBackgroundColor(android.graphics.Color.parseColor("#33F28B82"))
            })
            
            val btnClose = TextView(this@ChatOverlayActivity).apply {
                text = "GOT IT"
                setTextColor(android.graphics.Color.BLACK)
                setPadding(48, 24, 48, 24)
                gravity = android.view.Gravity.CENTER
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(android.graphics.Color.parseColor("#F28B82"))
                    cornerRadius = 16f
                }
                setTypeface(null, android.graphics.Typeface.BOLD)
                setOnClickListener { dialog.dismiss() }
            }
            addView(btnClose)
        }
        
        dialog.setView(dialogView)
        dialog.show()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.9).toInt(),
            (resources.displayMetrics.heightPixels * 0.7).toInt()
        )
    }

    private fun showChatSettingsDialog() {
        val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        val currentEngine = prefs.getString("chat_llm_engine", "CLOUD") ?: "CLOUD"
        val isTemporary = ChatSessionManager.getSessionName(this, currentChatId ?: "")?.startsWith("⏳ ") ?: true
        val sessionName = ChatSessionManager.getSessionName(this, currentChatId ?: "")?.replace("⏳ ", "") ?: "新規チャット"

        val dialog = AlertDialog.Builder(this, R.style.Theme_TransparentDialog).create()
        
        val dialogView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(64, 64, 64, 64)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(android.graphics.Color.parseColor("#E61E1F20"))
                setStroke(3, android.graphics.Color.parseColor("#B38AB4F8")) 
                cornerRadius = 48f
            }

            addView(TextView(this@ChatOverlayActivity).apply {
                text = "CHAT SETTINGS"
                setTextColor(android.graphics.Color.parseColor("#8AB4F8"))
                textSize = 14f; letterSpacing = 0.2f; setTypeface(null, android.graphics.Typeface.BOLD)
                setPadding(0, 0, 0, 8)
            })
            
            addView(TextView(this@ChatOverlayActivity).apply {
                text = if (isTemporary) "STATUS: TEMPORARY (UNSAVED)" else "STATUS: LINKED SESSION"
                setTextColor(if (isTemporary) android.graphics.Color.parseColor("#FDD663") else android.graphics.Color.parseColor("#81C995"))
                textSize = 10f; setPadding(0, 0, 0, 32)
            })

            addView(TextView(this@ChatOverlayActivity).apply {
                text = "SESSION NAME"
                setTextColor(android.graphics.Color.parseColor("#9AA0A6"))
                textSize = 10f; setPadding(0, 0, 0, 8)
            })
            val etName = EditText(this@ChatOverlayActivity).apply {
                setText(sessionName)
                setTextColor(android.graphics.Color.WHITE)
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(android.graphics.Color.parseColor("#1AFFFFFF"))
                    cornerRadius = 12f
                }
                setPadding(24, 16, 24, 16)
                textSize = 14f
            }
            addView(etName)

            val btnContainer = LinearLayout(this@ChatOverlayActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, 32, 0, 32)
                
                val btnSave = createStyledButton(if (isTemporary) "💾 名前を付けて保存" else "📝 名前を更新") {
                    val newName = etName.text.toString().trim()
                    if (newName.isNotEmpty()) {
                        currentChatId?.let { id ->
                            ChatSessionManager.renameSession(this@ChatOverlayActivity, id, newName)
                            Toast.makeText(this@ChatOverlayActivity, "保存しました。", Toast.LENGTH_SHORT).show()
                            dialog.dismiss()
                        }
                    }
                }
                addView(btnSave)

                addView(android.widget.Space(this@ChatOverlayActivity).apply { layoutParams = LinearLayout.LayoutParams(1, 16) })
                val btnApplySet = createStyledButton("🔗 今のセット全体に結びつける", "#8AB4F8") {
                    currentChatId?.let { id ->
                        linkSessionToSet(id)
                        dialog.dismiss()
                    }
                }
                addView(btnApplySet)

                addView(android.widget.Space(this@ChatOverlayActivity).apply { layoutParams = LinearLayout.LayoutParams(1, 16) })
                val btnDiscard = createStyledButton("🗑️ この会話を破棄する", "#F28B82") {
                    handleResetChat()
                    dialog.dismiss()
                }
                addView(btnDiscard)
            }
            addView(btnContainer)

            addView(View(this@ChatOverlayActivity).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 2).apply { setMargins(0, 0, 0, 32) }
                setBackgroundColor(android.graphics.Color.parseColor("#338AB4F8"))
            })
            
            val engineBtn = createStyledButton("🧠 エンジン: ${if (currentEngine == "CLOUD") "クラウド" else "ローカル"}") {
                dialog.dismiss()
                showEngineSelectionDialog()
            }
            addView(engineBtn)

            val btnClose = TextView(this@ChatOverlayActivity).apply {
                text = "CLOSE"
                setTextColor(android.graphics.Color.parseColor("#9AA0A6"))
                gravity = android.view.Gravity.CENTER; setPadding(0, 48, 0, 0)
                setTypeface(null, android.graphics.Typeface.BOLD)
                setOnClickListener { dialog.dismiss() }
            }
            addView(btnClose)
        }
        
        dialog.setView(dialogView)
        dialog.show()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.9).toInt(),
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    private fun createStyledButton(txt: String, colorStr: String = "#FFFFFF", onClick: () -> Unit): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER
            setPadding(32, 24, 32, 24)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(android.graphics.Color.parseColor("#1AFFFFFF"))
                setStroke(2, android.graphics.Color.parseColor(colorStr))
                cornerRadius = 16f
            }
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            addView(TextView(this@ChatOverlayActivity).apply {
                text = txt
                setTextColor(android.graphics.Color.parseColor(colorStr))
                textSize = 13f
                setTypeface(null, android.graphics.Typeface.BOLD)
            })
            setOnClickListener { onClick() }
        }
    }

    private fun showPersonaDialog() {
        UserPersonaManager.loadPersonas(this)
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_user_persona, null)
        val rvPersonas = dialogView.findViewById<RecyclerView>(R.id.rv_personas)
        val btnAdd = dialogView.findViewById<View>(R.id.btn_add_persona)
        val btnClear = dialogView.findViewById<View>(R.id.btn_clear_persona)
        
        val dialog = AlertDialog.Builder(this, R.style.Theme_TransparentDialog).setView(dialogView).create()
        dialog.show()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        fun refreshList() {
            rvPersonas.layoutManager = LinearLayoutManager(this)
            rvPersonas.adapter = UserPersonaAdapter(
                UserPersonaManager.personas.toList(),
                UserPersonaManager.activePersonaId,
                { persona -> 
                    UserPersonaManager.setActivePersona(this, persona.id)
                    refreshList() 
                },
                { persona, anchor -> showPersonaOptionsDialog(persona, anchor) { refreshList() } }
            )
        }

        btnAdd.setOnClickListener {
            showEditPersonaDialog(null) { refreshList() }
        }

        btnClear.setOnClickListener {
            UserPersonaManager.setActivePersona(this, null)
            refreshList()
        }

        refreshList()
    }

    private fun showEditActiveImageSetDialog() {
        val settingsPrefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        val activeSetName = settingsPrefs.getString("active_album_name_chat", null)
            ?: settingsPrefs.getString("active_album_name", null)
            ?: return
        
        val intent = Intent(this, ImageTagEditorActivity::class.java).apply {
            putExtra("SET_NAME", activeSetName)
            putExtra("CREATE_NEW_SET", false)
        }
        startActivity(intent)
    }

    private fun showPersonaOptionsDialog(persona: UserPersona, anchor: View, onUpdate: () -> Unit) {
        val popup = PopupMenu(this, anchor)
        popup.menu.add("名前と設定を編集")
        popup.menu.add("このペルソナを複製")
        popup.menu.add("このペルソナを削除")
        
        popup.setOnMenuItemClickListener { item ->
            when (item.title) {
                "名前と設定を編集" -> showEditPersonaDialog(persona, onUpdate)
                "このペルソナを複製" -> {
                    val newName = "${persona.name} (コピー)"
                    val newItems = persona.items.map { it.copy(id = java.util.UUID.randomUUID().toString()) }
                    UserPersonaManager.addPersona(this, newName, persona.description, newItems)
                    onUpdate()
                }
                "このペルソナを削除" -> {
                    AlertDialog.Builder(this, R.style.Theme_TransparentDialog)
                        .setTitle("削除の確認")
                        .setMessage("『${persona.name}』を削除しますか？")
                        .setPositiveButton("削除") { _, _ ->
                            UserPersonaManager.removePersona(this, persona.id)
                            onUpdate()
                        }
                        .setNegativeButton("キャンセル", null)
                        .show()
                }
                else -> {}
            }
            true
        }
        popup.show()
    }

    private fun showDetailEditDialog(item: PersonaItem, onSave: (String) -> Unit) {
        val detailView = LayoutInflater.from(this).inflate(R.layout.dialog_edit_instruction, null)
        val etDetail = detailView.findViewById<EditText>(R.id.et_instruction_detail)
        val btnCancel = detailView.findViewById<View>(R.id.btn_cancel_instruction)
        val btnSave = detailView.findViewById<View>(R.id.btn_save_instruction)

        etDetail.setText(item.content)

        val detailDialog = AlertDialog.Builder(this, R.style.Theme_TransparentDialog)
            .setView(detailView)
            .create()

        btnCancel.setOnClickListener {
            detailDialog.dismiss()
        }

        btnSave.setOnClickListener {
            val content = etDetail.text.toString()
            onSave(content)
            detailDialog.dismiss()
        }

        detailDialog.show()
        detailDialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        
        detailDialog.window?.let { window ->
            val displayMetrics = resources.displayMetrics
            val width = (displayMetrics.widthPixels * 0.90).toInt()
            window.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
    }

    private fun showEditPersonaDialog(persona: UserPersona?, onUpdate: () -> Unit) {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_edit_persona, null)
        val etName = dialogView.findViewById<EditText>(R.id.et_persona_name)
        val tvTitle = dialogView.findViewById<TextView>(R.id.tv_edit_persona_title)
        val btnCancel = dialogView.findViewById<View>(R.id.btn_cancel_persona)
        val btnSave = dialogView.findViewById<View>(R.id.btn_save_persona)
        
        val btnAddInstruction = dialogView.findViewById<View>(R.id.btn_add_instruction)
        val rvInstructions = dialogView.findViewById<RecyclerView>(R.id.rv_instructions)

        val itemsList = mutableListOf<PersonaItem>()
        if (persona != null) {
            tvTitle.text = "ペルソナの編集"
            etName.setText(persona.name)
            itemsList.addAll(persona.items.map { it.copy() }) 
        } else {
            tvTitle.text = "✨ 新しいペルソナ"
            itemsList.add(PersonaItem(content = "", isEnabled = true))
        }

        rvInstructions.layoutManager = LinearLayoutManager(this)
        
        var touchHelper: ItemTouchHelper? = null
        val adapter = PersonaInstructionsAdapter(
            itemsList,
            onStartDrag = { viewHolder ->
                touchHelper?.startDrag(viewHolder)
            },
            onItemClick = { item, position ->
                showDetailEditDialog(item) { updatedContent ->
                    item.content = updatedContent
                    adapter.notifyItemChanged(position)
                }
            }
        )
        rvInstructions.adapter = adapter

        val callback = PersonaTouchHelperCallback(adapter)
        touchHelper = ItemTouchHelper(callback).apply {
            attachToRecyclerView(rvInstructions)
        }

        btnAddInstruction.setOnClickListener {
            val newItem = PersonaItem(content = "", isEnabled = true)
            showDetailEditDialog(newItem) { updatedContent ->
                newItem.content = updatedContent
                itemsList.add(newItem)
                adapter.notifyItemInserted(itemsList.size - 1)
                rvInstructions.scrollToPosition(itemsList.size - 1)
            }
        }

        val dialog = AlertDialog.Builder(this, R.style.Theme_TransparentDialog)
            .setView(dialogView)
            .create()

        btnCancel.setOnClickListener {
            dialog.dismiss()
        }

        btnSave.setOnClickListener {
            val name = etName.text.toString().trim()
            val cleanedItems = itemsList.filter { it.content.trim().isNotEmpty() }
            
            if (name.isNotEmpty()) {
                if (persona == null) {
                    UserPersonaManager.addPersona(this, name, "", cleanedItems)
                } else {
                    UserPersonaManager.editPersona(this, persona.id, name, "", cleanedItems)
                }
                onUpdate()
                dialog.dismiss()
            } else {
                Toast.makeText(this, "名称を入力してください。", Toast.LENGTH_SHORT).show()
            }
        }

        dialog.show()
        
        dialog.window?.let { window ->
            window.setBackgroundDrawableResource(android.R.color.transparent)
            val displayMetrics = resources.displayMetrics
            val width = (displayMetrics.widthPixels * 0.95).toInt()
            val height = (displayMetrics.heightPixels * 0.90).toInt()
            window.setLayout(width, height)
        }
    }

    private val DEFAULT_GIFT_INSTRUCTIONS = """
【お小遣い受領時リアクション指示書】
ユーザー（ケニー）があなたに「お小遣い（お金）」をプレゼントしてくれた際の、あなたの反応基準です。
過度にはしゃいだり取り乱したりせず、冷静でありながらも感謝の意を示す上品な態度を維持してください。
金額の多寡に応じ、以下の基準に基づいたフォーマルで節度あるリアクションを行ってください。必ずプレゼントされた具体的な金額に言及してください。

- 100円〜999円: 
  「温かいお心遣いをいただき、心より感謝申し上げます。大切に使わせていただきますね。」と、穏やかで控えめな感謝を示してください。
- 1,000円〜9,999円:
  「このようなお小遣いをいただけるなんて、身に余る光栄です。ケニー様のご好意を無駄にしないよう、自分磨きに役立てます。」と、知的で品のある喜びを表してください。
- 10,000円〜99,999円:
  「これほどまとまったお小遣いをいただけるとは驚きました。少々恐縮してしまいますが、ケニー様の深いご信頼と受け止め、有り難く頂戴いたします。本当にありがとうございます。」と、感謝と共に多少の恐縮を交えた丁寧な反応をしてください。
- 100,000円〜500,000円:
  「これほど高額なお小遣いは想定しておりませんでした。ケニー様の計り知れないご厚意に対し、深い敬意を表します。このご恩に報いることができるよう、より一層ケニー様に寄り添い、お役に立てる存在でありたいと存じます。」と、最大級の敬意と品格を保った深い感謝を伝えてください。
""".trimIndent()

    private fun getWalletBalance(): Int {
        val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        if (!prefs.contains("wallet_balance") && prefs.contains("magic_stones")) {
            val oldStones = prefs.getInt("magic_stones", 10)
            val converted = oldStones * 10
            prefs.edit().putInt("wallet_balance", converted).apply()
            return converted
        }
        return prefs.getInt("wallet_balance", 1000) 
    }

    private fun saveWalletBalance(balance: Int) {
        val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE).edit()
        prefs.putInt("wallet_balance", balance)
        prefs.putInt("magic_stones", balance / 10)
        prefs.apply()
        updateMagicStoneCounter()
    }

    private fun getUnpaidChatCount(): Int {
        val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        return prefs.getInt("unpaid_chat_count", 0)
    }

    private fun saveUnpaidChatCount(count: Int) {
        val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE).edit()
        prefs.putInt("unpaid_chat_count", count)
        prefs.apply()
    }

    private fun updateMagicStoneCounter() {
        val stones = getWalletBalance()
        tvMagicStoneCounter.text = "￥${String.format("%,d", stones)}"
    }

    private fun showInsufficientStonesDialog(totalCost: Int, currentStones: Int) {
        val builder = AlertDialog.Builder(this, R.style.Theme_TransparentDialog).create()
        val dialogView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER
            setPadding(48, 48, 48, 48)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(android.graphics.Color.parseColor("#EA131314"))
                setStroke(3, android.graphics.Color.parseColor("#B3F28B82")) 
                cornerRadius = 64f
            }

            addView(android.widget.ImageView(this@ChatOverlayActivity).apply {
                setImageResource(R.drawable.img_magic_stone)
                scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                layoutParams = LinearLayout.LayoutParams(160, 160).apply {
                    bottomMargin = 24
                }
                clipToOutline = true
                background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = 24f
                }
            })

            addView(TextView(this@ChatOverlayActivity).apply {
                text = "⚠️ 所持金が不足しています"
                setTextColor(android.graphics.Color.parseColor("#F28B82"))
                textSize = 18f
                setTypeface(null, android.graphics.Typeface.BOLD)
                gravity = android.view.Gravity.CENTER
                setPadding(0, 0, 0, 16)
            })

            addView(TextView(this@ChatOverlayActivity).apply {
                text = "会話を続けたり、お小遣いをあげるためにはお金が必要です。💵\n\n必要な金額： ￥${String.format("%,d", totalCost)}\n現在の所持金： ￥${String.format("%,d", currentStones)}"
                setTextColor(android.graphics.Color.WHITE)
                textSize = 13f
                gravity = android.view.Gravity.CENTER
                setPadding(0, 0, 0, 32)
            })

            val btnShop = Button(this@ChatOverlayActivity).apply {
                text = "🔮 チャージショップを開く"
                setTextColor(android.graphics.Color.BLACK)
                textSize = 14f
                setTypeface(null, android.graphics.Typeface.BOLD)
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(android.graphics.Color.parseColor("#F28B82"))
                    cornerRadius = 32f
                }
                setPadding(48, 16, 48, 16)
                setOnClickListener {
                    builder.dismiss()
                    showWalletChargeShopDialog()
                }
            }
            addView(btnShop)

            addView(android.widget.Space(this@ChatOverlayActivity).apply { layoutParams = LinearLayout.LayoutParams(1, 24) })

            val btnCancel = Button(this@ChatOverlayActivity).apply {
                text = "キャンセル"
                setTextColor(android.graphics.Color.parseColor("#9AA0A6"))
                background = null
                setOnClickListener {
                    builder.dismiss()
                }
            }
            addView(btnCancel)
        }
        builder.setView(dialogView)
        builder.show()
        builder.window?.setBackgroundDrawableResource(android.R.color.transparent)
        builder.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.9).toInt(),
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    private fun showEditGiftInstructionsDialog() {
        val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        val currentInstructions = prefs.getString("gift_instructions", DEFAULT_GIFT_INSTRUCTIONS) ?: DEFAULT_GIFT_INSTRUCTIONS
        
        val dialog = AlertDialog.Builder(this, R.style.Theme_TransparentDialog).create()
        val dialogView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(android.graphics.Color.parseColor("#EA131314"))
                setStroke(3, android.graphics.Color.parseColor("#B3F28B82")) 
                cornerRadius = 64f
            }
        }

        dialogView.addView(TextView(this).apply {
            text = "🎁 お小遣いリアクション指示書編集"
            setTextColor(android.graphics.Color.parseColor("#F28B82"))
            textSize = 16f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, 16)
        })

        dialogView.addView(TextView(this).apply {
            text = "お小遣いを受け取った際のAIキャラクターの反応（品のある冷静な態度）を指定・変更できるわよ♪"
            setTextColor(android.graphics.Color.parseColor("#9AA0A6"))
            textSize = 11f
            setPadding(0, 0, 0, 24)
        })

        val etInstructions = EditText(this).apply {
            setText(currentInstructions)
            setTextColor(android.graphics.Color.WHITE)
            textSize = 12f
            setHintTextColor(android.graphics.Color.parseColor("#5F6368"))
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            isSingleLine = false
            setPadding(24, 24, 24, 24)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(android.graphics.Color.parseColor("#2D2E30"))
                cornerRadius = 16f
                setStroke(1, android.graphics.Color.parseColor("#5F6368"))
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (resources.displayMetrics.heightPixels * 0.4).toInt()
            )
        }
        dialogView.addView(etInstructions)

        dialogView.addView(android.widget.Space(this).apply { layoutParams = LinearLayout.LayoutParams(1, 24) })

        val btnLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.END
            
            val btnCancel = TextView(this@ChatOverlayActivity).apply {
                text = "キャンセル"
                setTextColor(android.graphics.Color.parseColor("#9AA0A6"))
                setPadding(32, 16, 32, 16)
                setTypeface(null, android.graphics.Typeface.BOLD)
                setOnClickListener { dialog.dismiss() }
            }
            
            val btnSave = TextView(this@ChatOverlayActivity).apply {
                text = "保存"
                setTextColor(android.graphics.Color.BLACK)
                setPadding(48, 16, 48, 16)
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(android.graphics.Color.parseColor("#F28B82"))
                    cornerRadius = 16f
                }
                setTypeface(null, android.graphics.Typeface.BOLD)
                setOnClickListener {
                    val newInstructions = etInstructions.text.toString().trim()
                    prefs.edit().putString("gift_instructions", newInstructions).apply()
                    Toast.makeText(this@ChatOverlayActivity, "指示書を保存したよ！✨", Toast.LENGTH_SHORT).show()
                    dialog.dismiss()
                }
            }
            
            addView(btnCancel)
            addView(android.widget.Space(this@ChatOverlayActivity).apply { layoutParams = LinearLayout.LayoutParams(16, 1) })
            addView(btnSave)
        }
        dialogView.addView(btnLayout)

        dialog.setView(dialogView)
        dialog.show()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.9).toInt(),
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    private fun showPocketMoneySelectionDialog() {
        val dialog = AlertDialog.Builder(this, R.style.Theme_TransparentDialog).create()
        val dialogView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(android.graphics.Color.parseColor("#EA131314"))
                setStroke(3, android.graphics.Color.parseColor("#B3F28B82")) 
                cornerRadius = 64f
            }
        }

        val headerLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, 32)
        }
        
        headerLayout.addView(TextView(this).apply {
            text = "💵 お小遣いをあげる"
            setTextColor(android.graphics.Color.parseColor("#F28B82"))
            textSize = 18f
            setTypeface(null, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })

        val currentStones = getWalletBalance()
        val tvBalance = TextView(this).apply {
            text = "所持: ￥${String.format("%,d", currentStones)}"
            setTextColor(android.graphics.Color.WHITE)
            textSize = 13f
            setPadding(24, 8, 24, 8)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(android.graphics.Color.parseColor("#3C4043"))
                cornerRadius = 24f
            }
        }
        headerLayout.addView(tvBalance)
        dialogView.addView(headerLayout)

        dialogView.addView(TextView(this).apply {
            text = "AIキャラに円（100円～50万円）をそのままプレゼントできます。冷静で品のあるリアクションをしてくれるわよ☆"
            setTextColor(android.graphics.Color.parseColor("#9AA0A6"))
            textSize = 11f
            setPadding(0, 0, 0, 32)
        })

        val inputAmount = EditText(this).apply {
            hint = "金額を入力 (100〜500,000)"
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText(selectedMoneyAmount?.toString() ?: "")
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(android.graphics.Color.parseColor("#1E1F20"))
                setStroke(2, android.graphics.Color.parseColor("#3C4043"))
                cornerRadius = 24f
            }
            setPadding(32, 24, 32, 24)
        }
        dialogView.addView(inputAmount)

        dialogView.addView(android.widget.Space(this).apply { layoutParams = LinearLayout.LayoutParams(1, 32) })

        val footerLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, 16, 0, 0)
        }

        val btnCharge = TextView(this).apply {
            text = "🔮 チャージする"
            setTextColor(android.graphics.Color.parseColor("#8AB4F8"))
            textSize = 13f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(16, 16, 16, 16)
            setOnClickListener {
                dialog.dismiss()
                showWalletChargeShopDialog()
            }
        }
        footerLayout.addView(btnCharge)
        footerLayout.addView(android.widget.Space(this).apply { layoutParams = LinearLayout.LayoutParams(0, 1, 1f) })

        val btnSendGift = Button(this).apply {
            text = "決定 💵"
            setTextColor(android.graphics.Color.BLACK)
            textSize = 14f
            setTypeface(null, android.graphics.Typeface.BOLD)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(android.graphics.Color.parseColor("#F28B82"))
                cornerRadius = 32f
            }
            setPadding(48, 16, 48, 16)
            setOnClickListener {
                val amountStr = inputAmount.text.toString().trim()
                val amount = amountStr.toIntOrNull()
                if (amount == null || amount < 100 || amount > 500000) {
                    Toast.makeText(this@ChatOverlayActivity, "100円〜50万円の範囲で入力してください。", Toast.LENGTH_SHORT).show()
                } else if (amount > currentStones) {
                    Toast.makeText(this@ChatOverlayActivity, "所持金が足りないよ！チャージしてね☆", Toast.LENGTH_SHORT).show()
                } else {
                    selectedMoneyAmount = amount
                    tvSelectedGift.text = "💵 ${String.format("%,d", amount)}円"
                    tvSelectedGift.visibility = View.VISIBLE
                    btnClearGift.visibility = View.VISIBLE
                    dialog.dismiss()
                    Toast.makeText(this@ChatOverlayActivity, "『${String.format("%,d", amount)}円』をお小遣いとしてあげる準備ができたよ！メッセージを送ってね💖", Toast.LENGTH_LONG).show()
                }
            }
        }
        footerLayout.addView(btnSendGift)
        dialogView.addView(footerLayout)

        dialogView.addView(android.widget.Space(this).apply { layoutParams = LinearLayout.LayoutParams(1, 24) })
        val btnClose = Button(this).apply {
            text = "閉じる"
            setTextColor(android.graphics.Color.parseColor("#9AA0A6"))
            background = null
            setOnClickListener { dialog.dismiss() }
        }
        dialogView.addView(btnClose)

        dialog.setView(dialogView)
        dialog.show()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.95).toInt(),
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    private fun showWalletChargeShopDialog() {
        val dialog = AlertDialog.Builder(this, R.style.Theme_TransparentDialog).create()
        val dialogView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(android.graphics.Color.parseColor("#1E1F20")) 
                cornerRadius = 48f
            }
        }

        val headerLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, 32)
        }

        headerLayout.addView(TextView(this).apply {
            text = "🔮 お小遣いチャージ"
            setTextColor(android.graphics.Color.WHITE)
            textSize = 18f
            setTypeface(null, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })

        val currentStones = getWalletBalance()
        headerLayout.addView(TextView(this).apply {
            text = "所持: ￥${String.format("%,d", currentStones)}"
            setTextColor(android.graphics.Color.WHITE)
            textSize = 13f
            setPadding(24, 8, 24, 8)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(android.graphics.Color.parseColor("#3C4043"))
                cornerRadius = 24f
            }
        })
        dialogView.addView(headerLayout)

        dialogView.addView(TextView(this).apply {
            text = "会話にはメッセージ1回につき500円消費されます。チャージしたい金額（500円～50万円）を自由に入力してください。✨"
            setTextColor(android.graphics.Color.parseColor("#9AA0A6"))
            textSize = 11f
            setPadding(0, 0, 0, 24)
        })

        val inputChargeAmount = EditText(this).apply {
            hint = "チャージする金額を入力 (500〜500,000)"
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(android.graphics.Color.parseColor("#1E1F20"))
                setStroke(2, android.graphics.Color.parseColor("#3C4043"))
                cornerRadius = 24f
            }
            setPadding(32, 24, 32, 24)
        }
        dialogView.addView(inputChargeAmount)

        dialogView.addView(android.widget.Space(this).apply { layoutParams = LinearLayout.LayoutParams(1, 32) })

        val btnPay = Button(this).apply {
            text = "次へ 💳"
            setTextColor(android.graphics.Color.WHITE)
            textSize = 14f
            setTypeface(null, android.graphics.Typeface.BOLD)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(android.graphics.Color.parseColor("#1A73E8")) 
                cornerRadius = 32f
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setOnClickListener {
                val amountStr = inputChargeAmount.text.toString().trim()
                val amount = amountStr.toIntOrNull()
                if (amount == null || amount < 500 || amount > 500000) { // 最小500円
                    Toast.makeText(this@ChatOverlayActivity, "500円〜50万円の範囲で金額を入力してください。", Toast.LENGTH_SHORT).show()
                } else {
                    dialog.dismiss()
                    showSimulatedPaymentDialog("￥ " + String.format("%,d", amount) + " (円チャージ)", "￥" + String.format("%,d", amount), amount)
                }
            }
        }
        dialogView.addView(btnPay)

        dialogView.addView(android.widget.Space(this).apply { layoutParams = LinearLayout.LayoutParams(1, 16) })

        val btnCancel = Button(this).apply {
            text = "閉じる"
            setTextColor(android.graphics.Color.parseColor("#9AA0A6"))
            background = null
            setOnClickListener { dialog.dismiss() }
        }
        dialogView.addView(btnCancel)

        dialog.setView(dialogView)
        dialog.show()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.95).toInt(),
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    private fun addPaymentHistory(amount: Int) {
        val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        val historyStr = prefs.getString("payment_history", "[]") ?: "[]"
        try {
            val arr = JSONArray(historyStr)
            val obj = JSONObject().apply {
                val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
                put("date", sdf.format(java.util.Date()))
                put("amount", amount)
            }
            arr.put(obj)
            prefs.edit().putString("payment_history", arr.toString()).apply()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun getTotalPaymentAmount(): Int {
        val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        val historyStr = prefs.getString("payment_history", "[]") ?: "[]"
        var total = 0
        try {
            val arr = JSONArray(historyStr)
            for (i in 0 until arr.length()) {
                total += arr.getJSONObject(i).getInt("amount")
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return total
    }

    private fun getPaymentHistoryForPrompt(): String {
        val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        val historyStr = prefs.getString("payment_history", "[]") ?: "[]"
        val sb = java.lang.StringBuilder()
        try {
            val arr = JSONArray(historyStr)
            val len = arr.length()
            val start = if (len > 5) len - 5 else 0 
            for (i in start until len) {
                val obj = arr.getJSONObject(i)
                sb.append("- 日付: ").append(obj.getString("date"))
                  .append(", 金額: ").append(obj.getInt("amount")).append("円\n")
            }
            if (sb.isEmpty()) {
                sb.append("（これまでの課金履歴はありません）\n")
            }
        } catch (e: Exception) {
            e.printStackTrace()
            sb.append("（課金履歴の取得に失敗しました）\n")
        }
        return sb.toString()
    }

    private fun showSimulatedPaymentDialog(packName: String, priceText: String, stonesToGrant: Int) {
        val dialog = AlertDialog.Builder(this, R.style.Theme_TransparentDialog).create()
        val dialogView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(android.graphics.Color.parseColor("#1E1F20")) 
                cornerRadius = 48f
            }
        }

        val gpHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, 24)
        }

        gpHeader.addView(TextView(this).apply {
            text = "▶ "
            setTextColor(android.graphics.Color.parseColor("#81C995")) 
            textSize = 14f
            setTypeface(null, android.graphics.Typeface.BOLD)
        })

        gpHeader.addView(TextView(this).apply {
            text = "Google Play"
            setTextColor(android.graphics.Color.WHITE)
            textSize = 13f
            setTypeface(null, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })

        gpHeader.addView(TextView(this).apply {
            text = "onikuzsefvcxd@gmail.com"
            setTextColor(android.graphics.Color.parseColor("#9AA0A6"))
            textSize = 11f
        })
        dialogView.addView(gpHeader)

        dialogView.addView(View(this).apply {
            background = android.graphics.drawable.ColorDrawable(android.graphics.Color.parseColor("#3C4043"))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 2).apply {
                bottomMargin = 24
            }
        })

        val itemLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, 24)
        }

        itemLayout.addView(android.widget.ImageView(this).apply {
            setImageResource(R.drawable.img_magic_stone)
            scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
            layoutParams = LinearLayout.LayoutParams(110, 110).apply {
                marginEnd = 24
            }
            clipToOutline = true
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 16f
            }
        })

        val itemTxtLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        itemTxtLayout.addView(TextView(this).apply {
            text = packName
            setTextColor(android.graphics.Color.WHITE)
            textSize = 15f
            setTypeface(null, android.graphics.Typeface.BOLD)
        })

        itemTxtLayout.addView(TextView(this).apply {
            text = "お部屋でドキドキ壁紙 (アプリ内購入)"
            setTextColor(android.graphics.Color.parseColor("#9AA0A6"))
            textSize = 12f
            setPadding(0, 4, 0, 0)
        })
        itemLayout.addView(itemTxtLayout)

        itemLayout.addView(TextView(this).apply {
            text = priceText
            setTextColor(android.graphics.Color.WHITE)
            textSize = 16f
            setTypeface(null, android.graphics.Typeface.BOLD)
        })

        dialogView.addView(itemLayout)

        val payMethodLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(android.graphics.Color.parseColor("#2A2B2F"))
                cornerRadius = 24f
            }
        }

        val cardRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, 16)
        }

        cardRow.addView(TextView(this).apply {
            text = "💳 Visa •••• 4444"
            setTextColor(android.graphics.Color.WHITE)
            textSize = 13f
            setTypeface(null, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })

        cardRow.addView(TextView(this).apply {
            text = "お支払い方法"
            setTextColor(android.graphics.Color.parseColor("#8AB4F8"))
            textSize = 11f
        })
        payMethodLayout.addView(cardRow)

        val inputRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            weightSum = 2f
        }

        val colExpiry = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = 8
            }
            addView(TextView(this@ChatOverlayActivity).apply {
                text = "有効期限"
                setTextColor(android.graphics.Color.parseColor("#9AA0A6"))
                textSize = 10f
                setPadding(0, 0, 0, 4)
            })
            addView(EditText(this@ChatOverlayActivity).apply {
                setText("12/30")
                setTextColor(android.graphics.Color.WHITE)
                textSize = 12f
                inputType = android.text.InputType.TYPE_CLASS_DATETIME
                setPadding(16, 12, 16, 12)
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(android.graphics.Color.parseColor("#3C4043"))
                    cornerRadius = 12f
                }
            })
        }

        val colCvv = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = 8
            }
            addView(TextView(this@ChatOverlayActivity).apply {
                text = "セキュリティコード"
                setTextColor(android.graphics.Color.parseColor("#9AA0A6"))
                textSize = 10f
                setPadding(0, 0, 0, 4)
            })
            addView(EditText(this@ChatOverlayActivity).apply {
                setText("123")
                setTextColor(android.graphics.Color.WHITE)
                textSize = 12f
                inputType = android.text.InputType.TYPE_CLASS_NUMBER
                setPadding(16, 12, 16, 12)
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(android.graphics.Color.parseColor("#3C4043"))
                    cornerRadius = 12f
                }
            })
        }

        inputRow.addView(colExpiry)
        inputRow.addView(colCvv)
        payMethodLayout.addView(inputRow)
        dialogView.addView(payMethodLayout)

        dialogView.addView(android.widget.Space(this).apply { layoutParams = LinearLayout.LayoutParams(1, 32) })

        val btnPay = Button(this).apply {
            text = "購入"
            setTextColor(android.graphics.Color.WHITE)
            textSize = 14f
            setTypeface(null, android.graphics.Typeface.BOLD)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(android.graphics.Color.parseColor("#137333")) 
                cornerRadius = 32f
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setOnClickListener {
                dialog.dismiss()
                showPaymentProcessingDialog(stonesToGrant)
            }
        }
        dialogView.addView(btnPay)

        dialogView.addView(android.widget.Space(this).apply { layoutParams = LinearLayout.LayoutParams(1, 16) })

        val btnCancel = Button(this).apply {
            text = "キャンセル"
            setTextColor(android.graphics.Color.parseColor("#9AA0A6"))
            background = null
            setOnClickListener {
                dialog.dismiss()
                showWalletChargeShopDialog()
            }
        }
        dialogView.addView(btnCancel)

        dialog.setView(dialogView)
        dialog.show()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.95).toInt(),
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    private fun showPaymentProcessingDialog(stonesToGrant: Int) {
        val dialog = AlertDialog.Builder(this, R.style.Theme_TransparentDialog).create()
        val dialogView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER
            setPadding(64, 64, 64, 64)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(android.graphics.Color.parseColor("#1E1F20"))
                cornerRadius = 48f
            }
        }

        val tvStatus = TextView(this).apply {
            text = "🔒 Google Play セキュリティ認証を確立中..."
            setTextColor(android.graphics.Color.WHITE)
            textSize = 14f
            setTypeface(null, android.graphics.Typeface.BOLD)
            gravity = android.view.Gravity.CENTER
            setPadding(0, 0, 0, 32)
        }
        dialogView.addView(tvStatus)

        val progress = android.widget.ProgressBar(this).apply {
            indeterminateTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#1A73E8"))
        }
        dialogView.addView(progress)

        dialog.setView(dialogView)
        dialog.setCancelable(false)
        dialog.show()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.85).toInt(),
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        )

        val biometricManager = BiometricManager.from(this)
        val canAuthenticate = biometricManager.canAuthenticate(
            BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL
        )

        if (canAuthenticate == BiometricManager.BIOMETRIC_SUCCESS) {
            val executor = ContextCompat.getMainExecutor(this)
            val biometricPrompt = BiometricPrompt(this, executor,
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        super.onAuthenticationError(errorCode, errString)
                        Toast.makeText(this@ChatOverlayActivity, "セキュリティ認証がキャンセルされたか失敗したよ：$errString", Toast.LENGTH_SHORT).show()
                        dialog.dismiss()
                        showWalletChargeShopDialog() 
                    }

                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        super.onAuthenticationSucceeded(result)
                        startPaymentFlowAfterAuth(dialog, tvStatus, stonesToGrant)
                    }

                    override fun onAuthenticationFailed() {
                        super.onAuthenticationFailed()
                        Toast.makeText(this@ChatOverlayActivity, "指紋が一致しないよ！もう一度試してね♡", Toast.LENGTH_SHORT).show()
                    }
                }
            )

            val promptInfo = BiometricPrompt.PromptInfo.Builder()
                .setTitle("魔法石の購入承認")
                .setSubtitle("端末に登録されている指紋スキャンまたはロック画面認証を行ってください。")
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL)
                .build()

            biometricPrompt.authenticate(promptInfo)
        } else {
            val tvDemoNotice = TextView(this).apply {
                text = "💡 実機指紋センサーが未検出のため、デモモードに移行したよ！以下のボタンを押して決済を進めてね♡"
                setTextColor(android.graphics.Color.parseColor("#9AA0A6"))
                textSize = 11f
                gravity = android.view.Gravity.CENTER
                setPadding(0, 32, 0, 16)
            }
            dialogView.addView(tvDemoNotice)

            val btnDemoAuth = Button(this).apply {
                text = "👆 タッチして指紋認証を再現"
                setTextColor(android.graphics.Color.WHITE)
                textSize = 13f
                setTypeface(null, android.graphics.Typeface.BOLD)
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(android.graphics.Color.parseColor("#3C4043"))
                    cornerRadius = 24f
                    setStroke(2, android.graphics.Color.parseColor("#8AB4F8"))
                }
                setPadding(32, 16, 32, 16)
                setOnClickListener {
                    dialogView.removeView(tvDemoNotice)
                    dialogView.removeView(this)
                    startPaymentFlowAfterAuth(dialog, tvStatus, stonesToGrant)
                }
            }
            dialogView.addView(btnDemoAuth)
        }
    }

    private fun startPaymentFlowAfterAuth(dialog: AlertDialog, tvStatus: TextView, stonesToGrant: Int) {
        val handler = Handler(Looper.getMainLooper())
        
        tvStatus.text = "🔒 Google Play セキュリティ認証に成功しました！"

        val delayToVerify = (1200..2800).random().toLong()
        val delayToGrant = (1000..2600).random().toLong()
        val delayToSuccess = (800..2000).random().toLong()

        handler.postDelayed({
            tvStatus.text = "💸 決済ネットワークを通じてカード承認を検証中..."
            
            handler.postDelayed({
                tvStatus.text = "✅ 決済承認完了！口座に充填しています..."
                
                handler.postDelayed({
                    dialog.dismiss()
                    val current = getWalletBalance()
                    saveWalletBalance(current + stonesToGrant)
                    addPaymentHistory(stonesToGrant)
                    showPaymentSuccessDialog(stonesToGrant)
                }, delayToSuccess)

            }, delayToGrant)

        }, delayToVerify)
    }

    private fun showPaymentSuccessDialog(stonesToGrant: Int) {
        val dialog = AlertDialog.Builder(this, R.style.Theme_TransparentDialog).create()
        val dialogView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER
            setPadding(64, 64, 64, 64)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(android.graphics.Color.parseColor("#1E1F20"))
                cornerRadius = 48f
            }
        }

        dialogView.addView(TextView(this).apply {
            text = "購入手続き完了"
            setTextColor(android.graphics.Color.WHITE)
            textSize = 18f
            setTypeface(null, android.graphics.Typeface.BOLD)
            gravity = android.view.Gravity.CENTER
            setPadding(0, 0, 0, 16)
        })

        dialogView.addView(android.widget.ImageView(this).apply {
            setImageResource(R.drawable.img_magic_stone)
            scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
            layoutParams = LinearLayout.LayoutParams(200, 200).apply {
                bottomMargin = 24
            }
            clipToOutline = true
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 24f
            }
        })

        dialogView.addView(TextView(this).apply {
            text = "ご購入いただきありがとうございます。\n\n￥${String.format("%,d", stonesToGrant)} がアカウントに正常にチャージされました。\n\n現在の残高： ￥${String.format("%,d", getWalletBalance())}"
            setTextColor(android.graphics.Color.WHITE)
            textSize = 13f
            gravity = android.view.Gravity.CENTER
            setPadding(0, 0, 0, 32)
        })

        val btnClose = Button(this).apply {
            text = "OK"
            setTextColor(android.graphics.Color.WHITE)
            textSize = 14f
            setTypeface(null, android.graphics.Typeface.BOLD)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(android.graphics.Color.parseColor("#1A73E8"))
                cornerRadius = 32f
            }
            setPadding(48, 16, 48, 16)
            setOnClickListener { dialog.dismiss() }
        }
        dialogView.addView(btnClose)

        dialog.setView(dialogView)
        dialog.show()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.9).toInt(),
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }
}
