package com.example.kennys_dokidoki_wallpaper

import com.example.kennys_dokidoki_wallpaper.GeneratedImageReplayPolicy.SeedMode

fun main() {
    val seeded = GeneratedImageReplayPolicy.recipeFrom(
        GeneratedImageLifecycle.Draft(prompt = "1girl", negativePrompt = "blur", seed = 777L, steps = 20)
    )!!

    // 同じシード: バッチ指定があっても1枚だけ、シードはそのまま。
    val same = GeneratedImageReplayPolicy.requests(seeded, 20, SeedMode.SAME, 4)
    check(same.size == 1) { "SAME must stay a single request" }
    check(same.single().seed == 777L) { "SAME must keep the stored seed" }

    // シードを変える: バッチ枚数ぶん、通常生成と同じくシードなし(PC側が毎回ランダム)。
    val fresh = GeneratedImageReplayPolicy.requests(seeded, 20, SeedMode.NEW, 4)
    check(fresh.size == 4) { "NEW must fan out to the batch count" }
    check(fresh.all { it.seed == null }) { "NEW must drop the seed like normal generation" }
    check(fresh.all { it.prompt == "1girl" && it.negativePrompt == "blur" }) { "NEW must keep the prompt" }

    // バッチはビルダーの枚数規則で丸める。
    check(GeneratedImageReplayPolicy.clampBatch(0) == 1)
    check(GeneratedImageReplayPolicy.clampBatch(null) == 1)
    check(GeneratedImageReplayPolicy.clampBatch(-3) == 1)
    check(GeneratedImageReplayPolicy.clampBatch(12) == 12)

    // シードが残っていない画像でも再生成は開ける。SAME指定でもランダム扱い。
    val seedless = GeneratedImageReplayPolicy.recipeFrom(
        GeneratedImageLifecycle.Draft(prompt = "1girl")
    )!!
    check(seedless.seed == null)
    check(GeneratedImageReplayPolicy.missingReason(GeneratedImageLifecycle.Draft(prompt = "1girl")) == null) {
        "A missing seed must not block replay anymore"
    }
    check(GeneratedImageReplayPolicy.missingReason(GeneratedImageLifecycle.Draft(seed = 5L)) ==
        GeneratedImageReplayPolicy.MISSING_PROMPT)
    val seedlessSame = GeneratedImageReplayPolicy.requests(seedless, 20, SeedMode.SAME, 1)
    check(seedlessSame.single().seed == null)

    // 1枚用requestは既定でシード維持(既存呼び出しの互換)。
    check(GeneratedImageReplayPolicy.request(seeded, 48).seed == 777L)
    check(GeneratedImageReplayPolicy.request(seeded, 48).steps == 48)

    // 概要表示はシードモードを反映する。
    check(GeneratedImageReplayPolicy.summary(seeded).contains("Seed 777"))
    check(GeneratedImageReplayPolicy.summary(seeded, SeedMode.NEW).contains("ランダム"))
    check(GeneratedImageReplayPolicy.summary(seedless).contains("ランダム"))

    println("PASS: same-seed replay, seed-varying batch, batch clamp, seedless replay, summary labels")
}
