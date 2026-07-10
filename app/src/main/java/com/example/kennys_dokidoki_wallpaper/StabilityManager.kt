package com.example.kennys_dokidoki_wallpaper

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object StabilityManager {
    suspend fun generateImage(
        context: Context, 
        prompt: String, 
        negativePrompt: String,
        width: Int = 720,
        height: Int = 1280,
        steps: Int = 20,
        batchCount: Int = 1,
        samplerName: String = "Euler a",
        isThumbnail: Boolean = false,
        oldThumbnailUri: Uri? = null,
        appliedTags: Set<String> = emptySet(),
        onGenerated: (Uri) -> Unit = {}
    ): Boolean {
        val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val baseUrl = prefs.getString("remote_server_url", "") ?: ""
        
        if (baseUrl.isEmpty()) return false

        // すでに外側で開始宣言（バッチモード等）されてなければ、ここで開始するわ
        val managedExternally = GenerationProgressManager.state.value.isGenerating
        if (!managedExternally) {
            GenerationProgressManager.startGeneration()
        }

        return withContext(Dispatchers.IO) {
            // 進捗ポーリング開始
            val pollingJob = launch {
                while (GenerationProgressManager.state.value.isGenerating) {
                    pollProgress(context, baseUrl)
                    delay(1500) // 1.5秒おきにチェック
                    
                    if (GenerationProgressManager.shouldInterrupt) {
                        interruptGeneration(baseUrl)
                        // ここでfalseに戻しちゃうと、後続の保存処理で「中止された」ことが分からなくなっちゃうから消すわね！
                    }
                    if (GenerationProgressManager.shouldSkip) {
                        skipGeneration(baseUrl)
                        GenerationProgressManager.shouldSkip = false
                    }
                }
            }

            try {
                // ... (txt2img POST processing)
                val url = URL("${baseUrl.removeSuffix("/")}/sdapi/v1/txt2img")
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    setRequestProperty("Content-Type", "application/json")
                    doOutput = true
                    connectTimeout = 10000
                    readTimeout = 300000 
                }

                val body = JSONObject().apply {
                    put("prompt", prompt)
                    put("negative_prompt", negativePrompt)
                    put("steps", steps)
                    put("width", width)
                    put("height", height)
                    put("cfg_scale", 7)
                    put("sampler_name", samplerName)
                    put("batch_size", batchCount)
                }

                OutputStreamWriter(conn.outputStream).use { it.write(body.toString()) }

                val responseCode = conn.responseCode
                Log.d("StabilityManager", "Response Code: $responseCode")
                
                if (responseCode == 200) {
                    val responseText = conn.inputStream.bufferedReader().use { it.readText() }
                    val jsonResponse = JSONObject(responseText)
                    val imagesArray = jsonResponse.optJSONArray("images")
                    
                    if (imagesArray != null) {
                        for (i in 0 until imagesArray.length()) {
                            val base64Image = imagesArray.getString(i)
                            val savedUri = saveImageToStorage(context, base64Image, isThumbnail, oldThumbnailUri, appliedTags)
                            if (savedUri != null) {
                                withContext(Dispatchers.Main) {
                                    onGenerated(savedUri)
                                }
                            }
                        }
                    }
                    true
                } else {
                    false
                }
            } catch (e: Exception) {
                Log.e("StabilityManager", "Generation failed", e)
                false
            } finally {
                GenerationProgressManager.endGeneration()
                pollingJob.cancel()
            }
        }
    }

    private suspend fun pollProgress(context: Context, baseUrl: String) {
        try {
            val url = URL("${baseUrl.removeSuffix("/")}/sdapi/v1/progress")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 3000
                readTimeout = 3000
            }

            if (conn.responseCode == 200) {
                val responseText = conn.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(responseText)
                val progress = json.optDouble("progress", 0.0).toFloat()
                val currentImageBase64 = json.optString("current_image", "")
                
                var bitmap: Bitmap? = null
                if (currentImageBase64.isNotEmpty()) {
                    try {
                        val bytes = Base64.decode(currentImageBase64, Base64.DEFAULT)
                        bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    } catch (e: Exception) {
                        Log.e("StabilityManager", "Failed to decode preview bitmap", e)
                    }
                }
                
                // 画像が取れなかった時は、真っ黒にならないように前の画像を使い回すわよ！っ！
                val finalBitmap = bitmap ?: GenerationProgressManager.state.value.currentImage
                GenerationProgressManager.updateState(true, progress, finalBitmap, "錬成中... ${(progress * 100).toInt()}%")
            }
        } catch (e: Exception) {
            Log.e("StabilityManager", "Progress polling failed", e)
        }
    }

    private suspend fun interruptGeneration(baseUrl: String) {
        try {
            val url = URL("${baseUrl.removeSuffix("/")}/sdapi/v1/interrupt")
            val conn = (url.openConnection() as HttpURLConnection).apply { requestMethod = "POST" }
            conn.responseCode
        } catch (e: Exception) {
            Log.e("StabilityManager", "Interrupt failed", e)
        }
    }

    private suspend fun skipGeneration(baseUrl: String) {
        try {
            val url = URL("${baseUrl.removeSuffix("/")}/sdapi/v1/skip")
            val conn = (url.openConnection() as HttpURLConnection).apply { requestMethod = "POST" }
            conn.responseCode
        } catch (e: Exception) {
            Log.e("StabilityManager", "Skip failed", e)
        }
    }

    private suspend fun saveImageToStorage(
        context: Context, 
        base64String: String, 
        isThumbnail: Boolean,
        oldThumbnailUri: Uri? = null,
        appliedTags: Set<String> = emptySet()
    ): Uri? {
        // 中止された時に壊れた画像を保存しちゃわないようにチェックするわ！
        if (GenerationProgressManager.shouldInterrupt && !isThumbnail) {
            Log.d("StabilityManager", "Skipping save because interrupted")
            return null
        }

        try {
            val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
            val folderKey = if (isThumbnail) "gen_thumbnail_save_folder_uri" else "gen_save_folder_uri"
            val folderUriStr = prefs.getString(folderKey, null)
            
            if (folderUriStr == null) {
                Log.w("StabilityManager", "Save folder not configured ($folderKey).")
                return null
            }

            val folderUri = Uri.parse(folderUriStr)
            var directory = DocumentFile.fromTreeUri(context, folderUri)
            
            if (directory == null || !directory.exists()) {
                Log.e("StabilityManager", "Could not access configured directory.")
                return null
            }

            // 日付フォルダの作成 (通常の生成のみ)
            if (!isThumbnail) {
                val dateFolderStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
                var dateFolder = directory.findFile(dateFolderStr)
                if (dateFolder == null || !dateFolder.isDirectory) {
                    dateFolder = directory.createDirectory(dateFolderStr)
                }
                if (dateFolder != null) {
                    directory = dateFolder
                }
            }

            val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.getDefault()).format(Date())
            val fileName = if (isThumbnail) "THUMB_$timeStamp.jpg" else "GEN_$timeStamp.png"
            val mimeType = if (isThumbnail) "image/jpeg" else "image/png"
            
            val file = directory.createFile(mimeType, fileName)
            if (file != null) {
                var imageBytes = Base64.decode(base64String, Base64.DEFAULT)
                
                if (isThumbnail) {
                    // 3分の1の解像度に圧縮
                    val bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
                    if (bitmap != null) {
                        val scaledWidth = bitmap.width / 3
                        val scaledHeight = bitmap.height / 3
                        val scaledBitmap = Bitmap.createScaledBitmap(bitmap, scaledWidth, scaledHeight, true)
                        val stream = ByteArrayOutputStream()
                        scaledBitmap.compress(Bitmap.CompressFormat.JPEG, 85, stream)
                        imageBytes = stream.toByteArray()
                        bitmap.recycle()
                        scaledBitmap.recycle()
                    }
                }

                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(file.uri)?.use { 
                        it.write(imageBytes)
                    }
                    
                    // 新しい画像が保存できたら、古い画像を消すわよ！
                    if (isThumbnail && oldThumbnailUri != null) {
                        DataManager.deleteImageFile(context, oldThumbnailUri)
                    }
                }
                
                withContext(Dispatchers.Main) {
                    if (!isThumbnail) {
                        if (DataManager.allImages.none { it.uri.toString() == file.uri.toString() }) {
                            val entry = ImageEntry(file.uri)
                            if (appliedTags.isNotEmpty()) {
                                entry.tags.addAll(appliedTags)
                            }
                            DataManager.allImages.add(0, entry)
                            DataManager.saveData(context)
                        }
                    }
                }
                return file.uri
            }
        } catch (e: Exception) {
            Log.e("StabilityManager", "Failed to save image", e)
        }
        return null
    }
}
