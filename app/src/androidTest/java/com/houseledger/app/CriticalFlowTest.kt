package com.houseledger.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.houseledger.app.data.*
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CriticalFlowTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    private fun text(value: String): SemanticsNodeInteraction {
        ui.waitUntil(10000) { ui.onAllNodesWithText(value, useUnmergedTree = false).fetchSemanticsNodes().size == 1 }
        return ui.onNodeWithText(value, useUnmergedTree = false)
    }
    private fun addMenu() {
        ui.waitUntil(10000) { ui.onAllNodesWithContentDescription("Add").fetchSemanticsNodes().size == 1 }
        ui.onNodeWithContentDescription("Add").performClick()
    }
    @Test fun setupPersonalBillShareAndRestart() {
        ui.waitUntil(15000) { ui.onAllNodesWithText("My Name").fetchSemanticsNodes().isNotEmpty() }
        text("My Name").performTextInput("Gohar"); text("Household Name").performTextInput("Our Home"); text("Other members (one name per line)").performTextInput("Ali\nSara"); text("Save").performClick()
        ui.waitUntil(10000) { ui.onAllNodesWithText("Your month, at a glance").fetchSemanticsNodes().isNotEmpty() }
        addMenu(); text("Personal Income").performClick()
        text("Amount (PKR)").performTextInput("50000"); text("What was it for?").performTextInput("Salary for testing"); text("Save").performClick()
        ui.waitUntil(10000) { ui.onAllNodesWithText("Your month, at a glance").fetchSemanticsNodes().isNotEmpty() }
        addMenu(); text("Household Expense").performClick()
        text("Amount (PKR)").performTextInput("100"); text("What was it for?").performTextInput("Internet test bill")
        text("Select All Active Members").performScrollTo().performClick(); text("Save").performClick()
        ui.waitUntil(10000) { ui.onAllNodesWithText("Your month, at a glance").fetchSemanticsNodes().isNotEmpty() }
        val repo = (ui.activity.application as LedgerApplication).container.repository
        val saved = runBlocking { repo.snapshot() }
        assertEquals(1, saved.personal.size); assertEquals(listOf(3334L, 3333L, 3333L), saved.shares.map { it.amount })
        ui.activityRule.scenario.recreate()
        ui.waitUntil(15000) { ui.onAllNodesWithText("Your month, at a glance").fetchSemanticsNodes().isNotEmpty() }
        text("Household").performClick(); text("Internet test bill").performScrollTo().performClick()
        text("Preview bill / correction").performScrollTo().performClick()
        text("Share preview").assertIsDisplayed(); text("Copy Message").performClick()
        val clipboard = ui.activity.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        ui.runOnIdle { assertTrue(clipboard.primaryClip!!.getItemAt(0).text.toString().contains("100.00")) }
        text("Close").performClick()
        // Android's clipboard overlay temporarily covers controls at the bottom of the display.
        android.os.SystemClock.sleep(7000)
        text("Edit").performScrollTo().performClick(); text("What was it for?").performTextReplacement("Corrected internet bill"); text("Save").performClick()
        ui.waitUntil(10000) { ui.onAllNodesWithText("Corrected internet bill").fetchSemanticsNodes().isNotEmpty() }
        text("Delete").performScrollTo().performClick(); ui.onAllNodesWithText("Delete").onLast().performClick()
        ui.waitUntil(10000) { runBlocking { repo.snapshot().expenses.isEmpty() } }
        assertEquals(1, runBlocking { repo.snapshot().personal.size })
        text("Personal").performClick(); text("Salary for testing").performScrollTo().performClick()
        text("Edit").performScrollTo().performClick(); text("Amount (PKR)").performTextReplacement("51000"); text("Save").performClick()
        ui.waitUntil(10000) { runBlocking { repo.snapshot().personal.single().amount == 5100000L } }
        ui.onNodeWithContentDescription("Back").performClick()
        text("Reports").performClick(); text("Monthly summary").assertIsDisplayed()
        text("Preview household summary").performScrollTo().performClick(); text("Share preview").assertIsDisplayed(); text("Close").performClick()
        ui.onNodeWithContentDescription("Settings").performClick(); text("Profile").assertIsDisplayed()
        text("Restore JSON backup").performScrollTo().assertIsDisplayed()
    }
}
