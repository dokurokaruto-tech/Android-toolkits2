package com.example.kennys_dokidoki_wallpaper

/** 完了順・ファイル名の連番ではなく、タスク自身の用途と対象で振り分ける。 */
object GenerationTaskResultPolicy {
    fun thumbnails(
        results: List<AgentTaskResult>,
        legacyTargets: List<ThumbnailBindPolicy.Target>
    ): Map<ThumbnailBindPolicy.Target, AgentTaskResult> {
        val bindings = mutableMapOf<ThumbnailBindPolicy.Target, AgentTaskResult>()
        results.filter { it.purpose == ThumbnailBindPolicy.JOB_KIND_THUMBNAIL }.forEach { result ->
            val target = result.target ?: legacyTargets.getOrNull(result.index) ?: return@forEach
            if (!target.isValid) {
                return@forEach
            }
            val previous = bindings[target]
            if (previous == null || result.index > previous.index) {
                bindings[target] = result
            }
        }
        return bindings
    }

    fun images(results: List<AgentTaskResult>): List<AgentTaskResult> =
        results.filter { it.purpose == ThumbnailBindPolicy.JOB_KIND_IMAGE }
}
