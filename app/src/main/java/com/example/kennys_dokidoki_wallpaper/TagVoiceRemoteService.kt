package com.example.kennys_dokidoki_wallpaper

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Tag editor operations, separated from transport and playback. */
class TagVoiceRemoteService(context: Context) {
    private val client = ChatTtsClient(context.applicationContext)

    suspend fun list(tagId: String): PcVoiceListing = withContext(Dispatchers.IO) {
        client.listVoices(tagId)
    }

    suspend fun delete(tagId: String, epoch: String, sampleId: String?): Int = withContext(Dispatchers.IO) {
        client.deleteVoices(tagId, epoch, sampleId)
    }

    fun close() { client.cancel() }
}
