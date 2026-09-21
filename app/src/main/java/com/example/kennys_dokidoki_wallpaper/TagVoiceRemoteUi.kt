package com.example.kennys_dokidoki_wallpaper

import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** PC deletions are immediate; canceling the tag editor does not undo them. */
class TagVoiceRemoteUi(private val activity: AppCompatActivity) {
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var job: Job? = null
    private var service: TagVoiceRemoteService? = null
    private var dialog: AlertDialog? = null

    fun show(tagId: String, tag: String) {
        request("PCの保存音声を確認中…") { remote -> showListing(tagId, tag, remote.list(tagId)) }
    }

    private fun showListing(tagId: String, tag: String, listing: PcVoiceListing) {
        if (activity.isDestroyed || activity.isFinishing) { return }
        if (listing.samples.isEmpty()) {
            dialog = builder().setTitle("$tag：PC保存音声")
                .setMessage("このタグの音声はPCに保存されていません。次回TTS時に必要なサンプルを送信します。")
                .setPositiveButton("OK", null).show()
            return
        }
        val entries = listing.samples.map {
            "${it.name}\n${it.id.take(8)} · ${it.sizeBytes / 1024} KiB · ${it.createdAt.take(10)}"
        } + "このタグのPC音声をすべて削除"
        dialog = builder().setTitle("$tag：PC保存音声（${listing.samples.size}件）")
            .setItems(entries.toTypedArray()) { _, index ->
                val sample = listing.samples.getOrNull(index)
                confirmDelete(tagId, tag, listing, sample)
            }
            .setNegativeButton("閉じる", null).show()
    }

    private fun confirmDelete(tagId: String, tag: String, listing: PcVoiceListing, sample: PcVoiceSample?) {
        val target = sample?.name ?: "このタグの保存音声すべて"
        dialog = builder().setTitle("PC上の音声を削除")
            .setMessage("$target を削除しますか？\n\n削除は即時適用です。アプリ内のサンプルは残ります。次回TTSで必要な場合は再送されます。実行中の生成は止まりません。")
            .setPositiveButton("PCから削除") { _, _ ->
                request("PCの音声を削除中…") { remote ->
                    remote.delete(tagId, listing.epoch, sample?.id)
                    showListing(tagId, tag, remote.list(tagId))
                }
            }
            .setNegativeButton("キャンセル", null).show()
    }

    private fun request(title: String, action: suspend (TagVoiceRemoteService) -> Unit) {
        if (job?.isActive == true) { return }
        val remote = TagVoiceRemoteService(activity)
        service = remote
        val progress = builder().setTitle(title).setMessage("PC生成エージェントへ接続しています。")
            .setCancelable(false)
            .setNegativeButton("通信を中止") { _, _ ->
                job?.cancel()
                remote.close()
            }.create()
        dialog = progress
        progress.show()
        job = scope.launch {
            try {
                action(remote)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (!activity.isDestroyed && !activity.isFinishing && service === remote && job?.isActive == true) {
                    dialog = builder().setTitle("PC音声エラー")
                        .setMessage(error.message ?: "PC音声の操作に失敗しました。")
                        .setPositiveButton("OK", null).show()
                }
            } finally {
                progress.dismiss()
                remote.close()
                if (service === remote) { service = null }
            }
        }
    }

    private fun builder() = AlertDialog.Builder(activity, R.style.Theme_Kennys_dokidoki_wallpaper)

    fun close() {
        service?.close()
        job?.cancel()
        dialog?.dismiss()
        scope.cancel()
    }
}
