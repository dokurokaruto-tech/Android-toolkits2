package com.example.kennys_dokidoki_wallpaper

import android.content.Context
import android.util.Log
import com.llamatik.library.platform.LlamaBridge
import com.llamatik.library.platform.GenStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * ローカルLLM推論エンジン
 * Llamatik (llama.cpp wrapper) を使って .gguf モデルをオンデバイスで推論するわ。
 */
object LlmInferenceEngine {

    private const val TAG = "LlmInferenceEngine"

    init {
        try {
            // Llamatik のネイティブライブラリをロードするわ
            System.loadLibrary("llama_jni")
            Log.d(TAG, "Native library 'llama_jni' loaded successfully")
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "Native library 'llama_jni' not found! Llamatik might load it internally or name might be different: ${e.message}")
        }
    }

    enum class ModelState {
        UNLOADED,   // モデル未ロード
        LOADING,    // ロード中
        LOADED,     // ロード完了（推論可能）
        ERROR       // ロード失敗
    }

    var modelState: ModelState = ModelState.UNLOADED
        private set

    var loadedModelName: String? = null
        private set

    private var loadedModelPath: String? = null

    var lastErrorMessage: String? = null
        private set

    /**
     * GGUFモデルまたはLiteRTモデルをロードするわ。
     * @return 成功したら null, 失敗したらエラーメッセージ（コード付き）を返す
     */
    suspend fun loadModelDetailed(context: Context, modelFile: File): String? = withContext(Dispatchers.IO) {
        lastErrorMessage = null
        val ext = modelFile.extension.lowercase()
        
        // 1. 基本チェック
        if (!modelFile.exists()) return@withContext "[ERR_01] ファイルがないわ"
        
        // 2. 拡張子でエンジンを分岐させるわ
        if (ext == "litertlm" || ext == "bin" || ext == "tflite") {
            Log.d(TAG, "LiteRT エンジンを使用するわ: $ext")
            val error = LiteRtInferenceEngine.loadModel(context, modelFile)
            if (error == null) {
                modelState = ModelState.LOADED
                loadedModelName = modelFile.nameWithoutExtension
                loadedModelPath = modelFile.absolutePath
            } else {
                modelState = ModelState.ERROR
            }
            return@withContext error
        }

        // 以下、既存の .gguf (Llamatik) 処理
        if (!modelFile.canRead()) return@withContext "[ERR_02] 読み取れないわ"

        // GGUFチェック (LiteRTでない場合のみ)
        try {
            val buffer = ByteArray(4)
            modelFile.inputStream().use { it.read(buffer) }
            val magic = String(buffer)
            if (magic != "GGUF") {
                Log.w(TAG, "GGUFヘッダー不一致だけど、強行突破してみるわ... (Magic: $magic)")
            }
        } catch (e: Exception) {
            Log.w(TAG, "ヘッダーチェック失敗: ${e.message}")
        }

        // 3. メモリチェック (ログのみ)
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val memoryInfo = android.app.ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memoryInfo)
        val availableMegs = memoryInfo.availMem / (1024 * 1024)
        Log.d(TAG, "Available RAM: ${availableMegs}MB")

        // ロード済みならアンロード
        if (modelState == ModelState.LOADED) unloadModel()

        modelState = ModelState.LOADING
        loadedModelName = modelFile.nameWithoutExtension

        return@withContext try {
            // Llamatik ロード実行
            val resolvedPath = try { LlamaBridge.getModelPath(modelFile.absolutePath) } catch (e: Exception) { modelFile.absolutePath }
            val success = LlamaBridge.initGenerateModel(resolvedPath)

            if (success) {
                modelState = ModelState.LOADED
                loadedModelPath = modelFile.absolutePath
                null
            } else {
                modelState = ModelState.ERROR
                "[ERR_05] Llamatikロード拒否。このモデルはLiteRT用かもしれないわ。拡張子を .litertlm に変えてみて！"
            }
        } catch (e: Exception) {
            modelState = ModelState.ERROR
            "[ERR_05] 例外: ${e.message}"
        }
    }

    /**
     * モデルをアンロードするわ。
     */
    fun unloadModel() {
        try {
            // 両方のエンジンをアンロード
            LiteRtInferenceEngine.unloadModel()
            // LlamaBridge側もリセットが必要ならここで行う
            
            modelState = ModelState.UNLOADED
            loadedModelPath = null
            loadedModelName = null
            Log.d(TAG, "全モデルをアンロードしたわ")
        } catch (e: Exception) {
            Log.e(TAG, "アンロード中にエラー: ${e.message}", e)
        }
    }

    fun isModelLoaded(): Boolean = modelState == ModelState.LOADED

    /**
     * 推論実行（ハイブリッド対応）
     */
    suspend fun generate(
        prompt: String,
        onToken: (String) -> Unit,
        onComplete: () -> Unit,
        onError: (String) -> Unit
    ) = withContext(Dispatchers.IO) {
        val path = loadedModelPath ?: ""
        val ext = File(path).extension.lowercase()

        if (ext == "litertlm" || ext == "bin" || ext == "tflite") {
            LiteRtInferenceEngine.generate(prompt, onToken, onComplete, onError)
            return@withContext
        }

        // 従来の Llamatik 推論
        if (modelState != ModelState.LOADED) {
            withContext(Dispatchers.Main) { onError("モデルがロードされてないわ") }
            return@withContext
        }

        try {
            LlamaBridge.generateStream(prompt, object : GenStream {
                override fun onDelta(text: String) = onToken(text)
                override fun onComplete() = onComplete()
                override fun onError(message: String) = onError(message)
            })
        } catch (e: Exception) {
            withContext(Dispatchers.Main) { onError("推論エラー: ${e.message}") }
        }
    }

    /**
     * チャット履歴からプロンプト文字列を構築するわ。
     * ChatMLフォーマットを使用。
     */
    fun buildChatPrompt(systemPrompt: String, history: List<ChatNode>): String {
        val sb = StringBuilder()

        // ChatML format
        sb.append("<|im_start|>system\n")
        sb.append(systemPrompt)
        sb.append("<|im_end|>\n")

        for (msg in history) {
            val role = if (msg.isUser) "user" else "assistant"
            sb.append("<|im_start|>$role\n")
            sb.append(msg.text)
            sb.append("<|im_end|>\n")
        }

        // アシスタントの応答を促す
        sb.append("<|im_start|>assistant\n")

        return sb.toString()
    }

    /**
     * 自動的に利用可能なモデルを探してロードするわ。
     * @return 成功したら null, 失敗したらエラーコード付きメッセージ
     */
    suspend fun autoLoadModelDetailed(context: Context): String? {
        // 設定で選択されたモデルを優先
        val selectedModel = LocalModelManager.getSelectedModel(context)
        if (selectedModel != null && selectedModel.file.exists()) {
            return loadModelDetailed(context, selectedModel.file)
        }

        // なければ最初に見つかったモデルを使う
        val firstModel = LocalModelManager.getFirstAvailableModel(context)
        if (firstModel != null) {
            LocalModelManager.setSelectedModel(context, firstModel.name)
            return loadModelDetailed(context, firstModel.file)
        }

        return "[ERR_01] モデルが一つも見つからなかったわ。ダウンロードしてね。"
    }

    /**
     * 後方互換性
     */
    suspend fun autoLoadModel(context: Context): Boolean {
        return autoLoadModelDetailed(context) == null
    }
}
