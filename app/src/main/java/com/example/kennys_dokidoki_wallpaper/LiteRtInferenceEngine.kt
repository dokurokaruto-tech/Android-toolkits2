package com.example.kennys_dokidoki_wallpaper

import android.content.Context
import android.util.Log
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Google LiteRT (MediaPipe GenAI) 推論エンジン
 * .litertlm, .bin, .tflite 形式のモデルを動かすためのクラスよ。
 */
object LiteRtInferenceEngine {
    private const val TAG = "LiteRtInferenceEngine"
    private var llmInference: LlmInference? = null
    
    enum class ModelState {
        UNLOADED, LOADING, LOADED, ERROR
    }

    var modelState: ModelState = ModelState.UNLOADED
        private set

    var loadedModelName: String? = null
        private set

    /**
     * モデルをロードするわ。
     */
    suspend fun loadModel(context: Context, modelFile: File): String? = withContext(Dispatchers.IO) {
        try {
            modelState = ModelState.LOADING
            loadedModelName = modelFile.nameWithoutExtension

            // 既存のエンジンがあれば解放
            llmInference?.close()

            // 1. モデルパスの正規化
            val absolutePath = modelFile.absolutePath
            Log.d(TAG, "LiteRT 初期化開始: $absolutePath")

            // 2. オプション構築
            // 注: MediaPipe LLM Inference は GPU 必須 (OpenCL/Vulkan)。
            val optionsBuilder = LlmInference.LlmInferenceOptions.builder()
                .setModelPath(absolutePath)
                .setMaxTokens(512)
                .setTopK(40)
                .setTemperature(0.8f)
                .setRandomSeed(42)
                // ストリーミング用のリスナーを設定するわよ！
                .setResultListener { result, isDone ->
                    onTokenCallback?.invoke(result)
                    if (isDone) onCompleteCallback?.invoke()
                }
                .setErrorListener { error ->
                    onErrorCallback?.invoke(error.message ?: "LiteRT Error")
                }

            // 3. インスタンス生成
            llmInference = LlmInference.createFromOptions(context, optionsBuilder.build())
            
            modelState = ModelState.LOADED
            Log.d(TAG, "LiteRTモデルロード成功！GPUエンジンが点火したわよ: ${modelFile.name}")
            null
        } catch (e: Exception) {
            modelState = ModelState.ERROR
            loadedModelName = null
            val err = "[LITERT_ERR] ${e.message ?: "未知の初期化エラー"}\n※GPUとの相性か、モデル形式の不整合よ。Edge Gallery と同じモデルなら、アプリ側の権限（外部ストレージ読み取り）も確認してね。"
            Log.e(TAG, err, e)
            err
        }
    }

    private var onTokenCallback: ((String) -> Unit)? = null
    private var onCompleteCallback: (() -> Unit)? = null
    private var onErrorCallback: ((String) -> Unit)? = null

    /**
     * 推論を実行するわ。
     */
    fun generate(
        prompt: String,
        onToken: (String) -> Unit,
        onComplete: () -> Unit,
        onError: (String) -> Unit
    ) {
        val inference = llmInference
        if (inference == null || modelState != ModelState.LOADED) {
            onError("LiteRTエンジンが準備できていないわ。")
            return
        }

        this.onTokenCallback = onToken
        this.onCompleteCallback = onComplete
        this.onErrorCallback = onError

        try {
            Log.d(TAG, "LiteRT リアルタイム推論開始: $prompt")
            // ストリーミング推論（非同期）を実行！
            inference.generateResponseAsync(prompt)
        } catch (e: Exception) {
            Log.e(TAG, "LiteRT推論エラー", e)
            onError("LiteRT推論中にエラーが発生したわ: ${e.message}")
        }
    }

    fun isModelLoaded() = modelState == ModelState.LOADED

    fun unloadModel() {
        llmInference?.close()
        llmInference = null
        modelState = ModelState.UNLOADED
        loadedModelName = null
    }
}
