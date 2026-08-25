package com.example.kennys_dokidoki_wallpaper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TagVariantPolicyTest {

    private fun variant(name: String, text: String = "") = TagPromptVariant(name, text)

    @Test
    fun legacyPromptBecomesOriginal() {
        val parsed = TagVariantPolicy.parseLegacyPrompt("古い文章")
        assertEquals(1, parsed.size)
        assertEquals(TagVariantPolicy.ORIGINAL_NAME, parsed.first().name)
        assertEquals("古い文章", parsed.first().text)
    }

    @Test
    fun emptyListFallsBackToOriginal() {
        val ensured = TagVariantPolicy.ensureNonEmpty(emptyList())
        assertEquals(1, ensured.size)
        assertEquals(TagVariantPolicy.ORIGINAL_NAME, ensured.first().name)
        assertEquals("", ensured.first().text)
    }

    @Test
    fun blankNamesAreRepaired() {
        val ensured = TagVariantPolicy.ensureNonEmpty(listOf(variant("  ")))
        assertEquals(TagVariantPolicy.ORIGINAL_NAME, ensured.first().name)
    }

    @Test
    fun defaultVariantPrefersOriginal() {
        val variants = listOf(variant("設定 2"), variant(TagVariantPolicy.ORIGINAL_NAME))
        assertEquals(TagVariantPolicy.ORIGINAL_NAME, TagVariantPolicy.defaultVariantName(variants))
        // オリジナルが無ければ先頭
        assertEquals("設定 2", TagVariantPolicy.defaultVariantName(listOf(variant("設定 2"))))
    }

    @Test
    fun resolveFallsBackToFirstWhenMissing() {
        val variants = listOf(variant("優しい", "優しい文章"), variant("いじめっ子", "意地悪な文章"))
        assertEquals(0, TagVariantPolicy.resolveIndex(variants, "存在しない"))
        assertEquals("優しい文章", TagVariantPolicy.resolveText(variants, "存在しない"))
        assertEquals("優しい", TagVariantPolicy.resolveName(variants, "存在しない"))
        assertEquals(1, TagVariantPolicy.resolveIndex(variants, "いじめっ子"))
        assertEquals("意地悪な文章", TagVariantPolicy.resolveText(variants, "いじめっ子"))
    }

    @Test
    fun multipleDetection() {
        assertFalse(TagVariantPolicy.hasMultiple(emptyList()))
        assertFalse(TagVariantPolicy.hasMultiple(listOf(variant("オリジナル"))))
        assertTrue(TagVariantPolicy.hasMultiple(listOf(variant("A"), variant("B"))))
    }

    @Test
    fun multiVariantTagsKeepsOrderAndSkipsSingles() {
        val byTag: (String) -> List<TagPromptVariant> = { tag ->
            when (tag) {
                "性格" -> listOf(variant("優しい"), variant("いじめっ子"))
                "髪" -> listOf(variant("オリジナル"))
                "服" -> listOf(variant("A"), variant("B"), variant("C"))
                else -> emptyList()
            }
        }
        assertEquals(
            listOf("性格", "服"),
            TagVariantPolicy.multiVariantTags(listOf("性格", "髪", "服"), byTag)
        )
    }

    @Test
    fun nextVariantNameSkipsExisting() {
        assertEquals("設定 3", TagVariantPolicy.nextVariantName(listOf(variant("A"), variant("B"))))
        assertEquals("設定 4", TagVariantPolicy.nextVariantName(listOf(variant("設定 3"), variant("B"))))
        assertEquals("設定 4", TagVariantPolicy.nextVariantName(listOf(variant("設定 2"), variant("B"), variant("設定 3"))))
        assertEquals("設定 1", TagVariantPolicy.nextVariantName(emptyList()))
    }

    @Test
    fun lastVariantCannotBeDeleted() {
        assertFalse(TagVariantPolicy.canDelete(emptyList()))
        assertFalse(TagVariantPolicy.canDelete(listOf(variant("A"))))
        assertTrue(TagVariantPolicy.canDelete(listOf(variant("A"), variant("B"))))

        val two = listOf(variant("A"), variant("B"))
        assertEquals(listOf(variant("B")), TagVariantPolicy.removeAt(two, 0))
        // 最後の1つは守られる
        assertEquals(two, TagVariantPolicy.removeAt(two, -1))
        assertEquals(two, TagVariantPolicy.removeAt(two, 5))
    }
}
