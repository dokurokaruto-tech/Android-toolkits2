package com.example.kennys_dokidoki_wallpaper

fun main() {
    val asIs = GeneratedImagePresetPolicy.FromImageMode.INDIVIDUAL_CARDS
    val keep = GeneratedImagePresetPolicy.FromImageMode.KEEP_RANDOMIZER
    val source = GeneratedImagePresetPolicy.Source(
        cardStates = linkedMapOf("a" to 1, "b" to 2, "gone" to 3),
        randomPickedIds = setOf("b"),
        randomEnabledCategories = setOf("背景"),
        width = 720,
        height = 1280,
        steps = 20,
        sampler = "Euler a",
        thumbnail = "http://pc/x.png"
    )

    // そのまま復元: 現存カードだけレベルごと、当たりも固定選択、分類は必ずオフ。
    val selection = BuilderCardSetApplyPolicy.selectionFor(source, asIs, setOf("a", "b", "c"))
    check(selection == mapOf("a" to 1, "b" to 2)) { "Unknown ids must be dropped, levels kept: $selection" }
    check(BuilderCardSetApplyPolicy.randomCategoriesFor(source, asIs).isEmpty())

    // ランダマイザー再現: 分類で当たったカードだけ選択を外し、分類はオンのまま。
    check(BuilderCardSetApplyPolicy.selectionFor(source, keep, setOf("a", "b", "c")) == mapOf("a" to 1))
    check(BuilderCardSetApplyPolicy.randomCategoriesFor(source, keep) == setOf("背景"))

    // 閲覧画面→ビルダーの受け渡しは最後の1件だけ残し、選んだ方式も一緒に運ぶ。
    check(BuilderCardSetHandoff.consume() == null)
    BuilderCardSetHandoff.offer(BuilderCardSetHandoff.Request(source, asIs))
    val newer = BuilderCardSetHandoff.Request(source.copy(cardStates = mapOf("c" to 1)), keep)
    BuilderCardSetHandoff.offer(newer)
    check(BuilderCardSetHandoff.consume() === newer) { "Last offer wins with its mode" }
    check(BuilderCardSetHandoff.consume() == null) { "Consume must clear the pending request" }

    check(BuilderCardSetApplyPolicy.appliedLabel(3, emptySet()).contains("3"))
    check(BuilderCardSetApplyPolicy.appliedLabel(3, setOf("背景")).contains("1"))
    check(BuilderCardSetApplyPolicy.requestedLabel(3).contains("3"))
    check(BuilderCardSetApplyPolicy.MODE_AS_IS_LABEL.isNotEmpty())
    check(BuilderCardSetApplyPolicy.MODE_RANDOMIZER_LABEL.isNotEmpty())
    check(BuilderCardSetApplyPolicy.MISSING_CARDS.isNotEmpty())
    check(BuilderCardSetApplyPolicy.EMPTY_CARDS.isNotEmpty())

    println("PASS: mode-aware selection/categories, handoff request, labels")
}
