package com.poyal.yeobaek

import androidx.compose.ui.semantics.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.poyal.yeobaek.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Captures every scroll region of the actual settings screens on a dedicated AVD. */
class SettingsLayoutTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    private val inst get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = inst.targetContext.applicationContext as YeobaekApplication
    private val variant get() = InstrumentationRegistry.getArguments().getString("auditVariant") ?: "default"
    private fun capture(name: String) {
        val directory = File(app.filesDir, "settings-audit/$variant").apply { mkdirs() }
        val bitmap = inst.uiAutomation.takeScreenshot()
        File(directory, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
    private fun checkText(containerTag: String) {
        // Dialog semantics can coexist with the underlying library. Audit only the
        // selected settings region, not text fields hidden behind the modal scrim.
        val inSettings = hasAnyAncestor(hasTestTag(containerTag))
        val texts = ui.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult) and inSettings, useUnmergedTree = true).fetchSemanticsNodes()
        assertTrue("No text layouts in settings region: $containerTag", texts.isNotEmpty())
        texts.forEach { node ->
            if (node.boundsInRoot.width < 1f || node.boundsInRoot.height < 1f) return@forEach
            val results = mutableListOf<TextLayoutResult>()
            node.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(results)
            // A wrap-content Text can retain a wider paragraph constraint than its
            // measured width. Compare painted line extents, not paragraph width.
            for (result in results) for (line in 0 until result.lineCount) {
                assertTrue("Clipped settings text: ${result.layoutInput.text}; line=$line; size=${result.size}",
                    !result.isLineEllipsized(line) &&
                        result.getLineRight(line) - result.getLineLeft(line) <= result.size.width + 2f &&
                        result.getLineBottom(line) <= result.size.height + 2f)
            }
        }
    }
    private fun audit(tag: String, name: String) {
        ui.waitUntil(10_000) { ui.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
        val content = ui.onNodeWithTag(tag)
        content.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, -100_000f) }
        repeat(35) { index ->
            ui.waitForIdle(); capture("$name-${index.toString().padStart(2, '0')}"); checkText(tag)
            val range = content.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
            if (range.value() >= range.maxValue() - 1) return
            val step = content.fetchSemanticsNode().boundsInRoot.height * .75f
            content.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, step) }
        }
        fail("Could not reach settings end: $name")
    }
    @Test fun allSettingsScreensHaveReadableControlsAndReachableEnds() = runBlocking<Unit> {
        val vm = ViewModelProvider(ui.activity)[AppViewModel::class.java]
        val settings = app.readerSettings.data.first()
        val theme = app.preferences.app.first().theme
        try {
            app.preferences.setTheme(if (variant.contains("dark")) AppTheme.DARK else AppTheme.LIGHT)
            withContext(Dispatchers.Main) { vm.chooseResume(false); vm.settingsScope.value = SettingsScope.COMMON }
            for ((screen, tag) in listOf("settings" to "app_settings", "general_settings" to "general_settings_options",
                "txt_settings" to "txt_options", "pdf_settings" to "pdf_options", "epub_settings" to "epub_options",
                "image_settings" to "image_options", "about" to "about_screen", "updates" to "updates_screen")) {
                withContext(Dispatchers.Main) { vm.screen(screen) }
                audit(tag, screen)
            }
            app.readerSettings.fields("common:EPUB", mapOf("publisherStyles" to "false"))
            withContext(Dispatchers.Main) { vm.screen("epub_settings") }
            ui.waitUntil(5_000) { !vm.epub.value.publisherStyles }
            audit("epub_options", "epub-custom-format")
            ui.onNodeWithTag("epub_publisher_styles").performScrollTo().performClick()
            ui.waitUntil(5_000) { vm.epub.value.publisherStyles }
            ui.onNodeWithTag("epub_publisher_styles").assertIsOn()
            app.readerSettings.fields("common:EPUB", mapOf("mode" to "PAGED"))
            ui.waitUntil(5_000) { vm.epub.value.mode == EpubMode.PAGED }
            ui.onNodeWithTag("epub_chapter_buttons_ALWAYS").assertDoesNotExist()
            audit("epub_options", "epub-paged")
            app.readerSettings.fields("common:IMAGE", mapOf("mode" to "PAGED"))
            withContext(Dispatchers.Main) { vm.screen("image_settings") }
            audit("image_options", "image-paged")
            withContext(Dispatchers.Main) { vm.screen("folder"); vm.sheet("quick_settings") }
            audit("quick_options", "quick-settings")
        } finally {
            withContext(Dispatchers.Main) { vm.sheet(null); vm.screen("folder") }
            app.readerSettings.update { settings }; app.preferences.setTheme(theme)
        }
    }
}
