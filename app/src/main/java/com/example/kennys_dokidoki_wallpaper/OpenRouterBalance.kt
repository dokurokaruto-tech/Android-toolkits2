package com.example.kennys_dokidoki_wallpaper

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal object OpenRouterBalance {
    fun initialLine(context: Context, modelId: String): String {
        if (OpenRouterManager.isModelFree(context, modelId)) {
            return TagAiGenerateCopy.usageLine(
                OpenRouterManager.getTotalUsage(context), OpenRouterManager.getTotalDailyMax(context)
            )
        }
        return OpenRouterBalancePolicy.CREDIT_LOADING
    }

    suspend fun load(context: Context, modelId: String): String {
        if (OpenRouterManager.isModelFree(context, modelId)) {
            return initialLine(context, modelId)
        }
        var key = OpenRouterManager.getActiveApiKey(context)
        while (true) {
            currentCoroutineContext().ensureActive()
            val activeKey = key ?: return OpenRouterBalancePolicy.KEY_MISSING
            val info = withContext(Dispatchers.IO) { OpenRouterManager.fetchCreditInfo(activeKey) }
            val currentKey = OpenRouterManager.getActiveApiKey(context)
            // キー切替中に届いた旧アカウントの残高は表示しない。
            if (currentKey != activeKey) {
                key = currentKey
                continue
            }
            return OpenRouterBalancePolicy.creditLine(info?.remaining)
        }
    }
}
