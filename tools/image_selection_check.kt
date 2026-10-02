package com.example.kennys_dokidoki_wallpaper

fun main() {
    val selection = ImageSelection()
    val original = listOf("A", "B", "C", "D")
    selection.start(original, 1)
    val inserted = listOf("NEW") + original
    selection.reconcile(inserted)
    check(selection.indices(inserted).map { inserted[it] } == listOf("B")) {
        "Insertion moved the selection away from B"
    }
    selection.range(inserted, 4)
    check(selection.indices(inserted).map { inserted[it] } == listOf("B", "C", "D"))
    val reversed = inserted.reversed()
    selection.reconcile(reversed)
    check(selection.indices(reversed).map { reversed[it] } == listOf("D", "C", "B"))
    selection.reconcile(listOf("NEW", "A", "C"))
    check(selection.indices(listOf("NEW", "A", "C")) == listOf(2))
    selection.range(listOf("NEW", "A", "C", "E"), 3)
    check(selection.indices(listOf("NEW", "A", "C", "E")) == listOf(2, 3))
    selection.reconcile(emptyList())
    check(selection.size == 0)
    selection.reconcile(original)
    check(selection.size == 0) { "Removed selections must not reappear" }
    selection.all(original)
    selection.reconcile(inserted)
    check(!selection.contains("NEW")) { "Select-all must not select future arrivals" }
    selection.toggle(inserted, 2)
    check(!selection.contains("B"))
    selection.clear()
    selection.start(original, -1)
    selection.range(original, 100)
    selection.toggle(original, 100)
    check(selection.size == 0)
    val url = "http://pc/api/v1/files/2026-10-02/a.png?token=old"
    val renewed = "https://other-pc/api/v1/files/2026-10-02/a.png?token=new"
    check(ImageSelection.key(url) == ImageSelection.key(renewed))
    check(ImageSelection.key(url) != ImageSelection.key(url.replace("10-02", "10-03")))
    check(ImageSelection.key("content://media/1?variant=a") != ImageSelection.key("content://media/1?variant=b"))
    selection.start(listOf(ImageSelection.key(url)), 0)
    selection.reconcile(listOf(ImageSelection.key(renewed)))
    check(selection.size == 1)
    selection.all(listOf("A", "A", "B"))
    check(selection.size == 2)
    println("PASS: insertion, range anchor, reorder, removal, empty list, select-all, toggle, invalid positions, token renewal, local identity, duplicates")
}
