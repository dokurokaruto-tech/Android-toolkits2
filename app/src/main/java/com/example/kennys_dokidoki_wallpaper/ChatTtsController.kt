package com.example.kennys_dokidoki_wallpaper

import android.content.Context
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.AudioAttributes
import android.media.MediaPlayer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

enum class ChatTtsState { IDLE, GENERATING, PLAYING }

/** Activity-scoped playback. Leaving the screen stops audio and disconnects the request. */
class ChatTtsController(
    context: Context,
    private val onState: (String?, ChatTtsState) -> Unit,
    private val onError: (String) -> Unit
) {
    private val context = context.applicationContext
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var job: Job? = null
    private var client: ChatTtsClient? = null
    private var player: MediaPlayer? = null
    private var audioFile: File? = null
    private var activeId: String? = null
    private val audioManager = this.context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val audioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        .setAudioAttributes(audioAttributes)
        .setWillPauseWhenDucked(true)
        .setOnAudioFocusChangeListener { change ->
            if (change != AudioManager.AUDIOFOCUS_GAIN) { stop() }
        }.build()

    fun speak(node: ChatNode) {
        if (activeId == node.id) {
            stop()
            return
        }
        if (activeId != null) { return }
        try {
            ChatVoicePolicy.requireVoice(node.ttsVoices)
            val savedBinding = node.ttsVoices!!.single()
            TagManager.loadTags(context)
            val binding = savedBinding.copy(tagId = savedBinding.tagId.ifBlank {
                TagManager.voiceTagId(savedBinding.tag).orEmpty()
            })
            check(binding.tagId.isNotBlank()) { "この返信のタグIDがありません。返信を再生成してください。" }
            check(node.ttsReady && !node.isUser) { "返信の生成が完了してから実行してください。" }
            val text = ChatVoicePolicy.speechText(node.text)
            val file = File.createTempFile("chat-tts-", ".wav", context.cacheDir)
            audioFile = file
            val request = ChatTtsClient(context)
            client = request
            activeId = node.id
            onState(node.id, ChatTtsState.GENERATING)
            job = scope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        try {
                            request.generate(text, binding, file)
                        } catch (error: Exception) {
                            file.delete()
                            throw error
                        }
                    }
                    play(file, node.id)
                } catch (error: CancellationException) {
                    file.delete()
                    throw error
                } catch (error: Exception) {
                    file.delete()
                    // A disconnected request can fail after the next request has started.
                    if (client !== request) { return@launch }
                    stop()
                    onError(error.message ?: "TTS生成に失敗しました。")
                }
            }
        } catch (error: Exception) {
            stop()
            onError(error.message ?: "TTSを開始できません。")
        }
    }

    private fun play(file: File, nodeId: String) {
        val media = MediaPlayer()
        player = media
        media.setAudioAttributes(audioAttributes)
        media.setDataSource(file.absolutePath)
        media.setOnPreparedListener {
            if (player !== media || activeId != nodeId) { return@setOnPreparedListener }
            if (audioManager.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                stop()
                onError("他の音声を停止してから再試行してください。")
                return@setOnPreparedListener
            }
            it.start()
            onState(nodeId, ChatTtsState.PLAYING)
        }
        media.setOnCompletionListener { stop() }
        media.setOnErrorListener { _, _, _ ->
            stop()
            onError("生成音声を再生できません。")
            true
        }
        media.prepareAsync()
    }

    fun stop() {
        client?.cancel()
        client = null
        job?.cancel()
        job = null
        player?.release()
        player = null
        audioManager.abandonAudioFocusRequest(focus)
        audioFile?.delete()
        audioFile = null
        activeId = null
        onState(null, ChatTtsState.IDLE)
    }

    fun close() {
        stop()
        scope.cancel()
    }
}
