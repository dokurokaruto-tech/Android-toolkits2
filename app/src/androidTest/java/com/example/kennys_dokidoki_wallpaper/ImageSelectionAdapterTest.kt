package com.example.kennys_dokidoki_wallpaper

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ImageSelectionAdapterTest {
    private fun image(name: String) = ImageEntry(Uri.parse("content://images/$name"))

    @Test
    fun albumSelectionSurvivesInsert() = onMain {
        val b = image("B")
        val images = mutableListOf(image("A"), b, image("C"))
        var count = 0
        val adapter = ImageAdapter(images, { _, _, _ -> }, { _, _ -> }, {}, {},
            onSelectionModeChanged = {}, onSelectionCountChanged = { count = it })
        adapter.startSelectionMode(1)
        images.add(0, image("NEW"))
        adapter.notifyItemInserted(0)
        assertEquals(listOf(b), adapter.getSelectedEntries())
        assertEquals(1, count)
        images.remove(b)
        adapter.notifyItemRemoved(2)
        assertTrue(adapter.getSelectedEntries().isEmpty())
        assertFalse(adapter.isSelectionMode)
        assertEquals(0, count)
    }

    @Test
    fun albumReturnsRefreshedEntry() = onMain {
        val old = ImageEntry(Uri.parse("http://pc/api/v1/files/2026-10-02/a.png?token=old"))
        val images = mutableListOf(old)
        val adapter = ImageAdapter(images, { _, _, _ -> }, { _, _ -> }, {}, {},
            onSelectionModeChanged = {}, onSelectionCountChanged = {})
        adapter.startSelectionMode(0)
        val refreshed = ImageEntry(Uri.parse("http://pc/api/v1/files/2026-10-02/a.png?token=new"))
        images[0] = refreshed
        adapter.notifyDataSetChanged()
        assertSame(refreshed, adapter.getSelectedEntries().single())
        images.clear()
        // Even before notification, an action must never access an old index.
        assertTrue(adapter.getSelectedEntries().isEmpty())
        adapter.notifyDataSetChanged()
        assertFalse(adapter.isSelectionMode)
    }

    @Test
    fun allImagesRetainsVisibleKeys() = onMain {
        val a = image("A")
        val b = image("B")
        val adapter = AllImagesAdapter(listOf(a, b), { _, _ -> }, { _, _ -> }, {}, {}, {})
        adapter.startSelectionMode(1)
        adapter.updateList(listOf(image("NEW"), b, a))
        assertEquals(listOf(b), adapter.getSelectedEntries())
        adapter.selectAll()
        adapter.updateList(listOf(image("LATER"), b, a))
        assertEquals(listOf(b, a), adapter.getSelectedEntries())
        adapter.updateList(emptyList())
        assertFalse(adapter.isSelectionMode)
    }

    private fun onMain(action: () -> Unit) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(action)
    }
}
