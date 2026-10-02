package com.example.kennys_dokidoki_wallpaper

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** FullScreenImageActivityの実テーマで、新規追加・再送の両ダイアログを確認する。 */
@RunWith(AndroidJUnit4::class)
class InsertionDialogThemeTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val prefs get() = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private var prepared = false

    @Before
    fun prepareIdleApp() {
        // 実ジョブが動いている端末の状態は変更しない。
        assumeFalse(GenerationAgentClient.hasPendingJob(context))
        assumeFalse(GenerationAgentClient.hasPendingInsertion(context))
        assumeFalse(GenerationProgressManager.state.value.isGenerating)
        prefs.edit().putString(ACTIVE_JOB, TEST_JOB).commit()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            GenerationProgressManager.startGeneration(batchMode = true, total = TOTAL)
        }
        prepared = true
    }

    @After
    fun restoreIdleApp() {
        if (!prepared) {
            return
        }
        prefs.edit().remove(ACTIVE_JOB).remove(PENDING_INSERTION).commit()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            GenerationProgressManager.endGeneration(force = true)
        }
    }

    @Test
    fun insertionOpensWithoutCrash() {
        checkDialog("次の生成に割り込み")
        assertFalse(prefs.contains(PENDING_INSERTION))
    }

    @Test
    fun retryOpensWithoutCrash() {
        prefs.edit().putString(PENDING_INSERTION, "{}").commit()
        checkDialog("未確認の割り込みがあります")
        assertEquals("{}", prefs.getString(PENDING_INSERTION, null))
    }

    private fun checkDialog(title: String) {
        val intent = Intent(context, FullScreenImageActivity::class.java).apply {
            putExtra("FROM_GENERATED_VIEWER", true)
            putStringArrayListExtra("VIRTUAL_ALBUM_URIS", arrayListOf<String>())
        }
        ActivityScenario.launch<FullScreenImageActivity>(intent).use { scenario ->
            scenario.onActivity { activity ->
                assertTrue(GenerationInsertionCoordinator.offer(activity, listOf(
                    AgentGenerationRequest("test", "", 512, 512, 30, "Euler a", seed = 42L)
                )))
            }
            onView(withText(title)).check(matches(isDisplayed()))
            onView(withText(android.R.string.cancel)).perform(click())
            scenario.onActivity { activity ->
                assertFalse(activity.isFinishing)
                assertTrue(GenerationProgressManager.state.value.isGenerating)
                assertEquals(TOTAL, GenerationProgressManager.state.value.totalBatch)
                assertEquals(TEST_JOB, GenerationAgentClient.activeJobId(activity))
                assertFalse(GenerationProgressManager.shouldInterrupt)
                assertFalse(GenerationProgressManager.shouldStopGracefully)
            }
        }
    }

    private companion object {
        const val ACTIVE_JOB = "generation_agent_active_job_id"
        const val PENDING_INSERTION = "generation_agent_pending_insertion"
        const val TEST_JOB = "00000000000000000000000000000000"
        const val TOTAL = 100
    }
}
