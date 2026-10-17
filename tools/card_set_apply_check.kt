package com.example.kennys_dokidoki_wallpaper

fun main() {
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

    // 使われたカードはレベルごと、現存するカードだけ上書き対象になる。
    val selection = BuilderCardSetApplyPolicy.selectionFor(source, setOf("a", "b", "c"))
    check(selection == mapOf("a" to 1, "b" to 2)) { "Unknown ids must be dropped, levels kept: $selection" }

    // ランダマイザーの当たりも固定選択になり、カテゴリは必ずオフへ上書きする。
    check(selection.containsKey("b")) { "Random picks must become fixed selections" }
    check(BuilderCardSetApplyPolicy.randomCategoriesFor(source).isEmpty())

    // 閲覧画面→ビルダーの受け渡しは最後の1件だけ残し、消費したら消える。
    check(BuilderCardSetHandoff.consume() == null)
    BuilderCardSetHandoff.offer(source)
    val other = source.copy(cardStates = mapOf("c" to 1))
    BuilderCardSetHandoff.offer(other)
    check(BuilderCardSetHandoff.consume() === other) { "Last offer wins" }
    check(BuilderCardSetHandoff.consume() == null) { "Consume must clear the pending request" }

    check(BuilderCardSetApplyPolicy.appliedLabel(3).contains("3"))
    check(BuilderCardSetApplyPolicy.requestedLabel(3).contains("3"))
    check(BuilderCardSetApplyPolicy.MISSING_CARDS.isNotEmpty())
    check(BuilderCardSetApplyPolicy.EMPTY_CARDS.isNotEmpty())

    println("PASS: selection filter, randomizer off, handoff last-wins/consume, labels")
}
