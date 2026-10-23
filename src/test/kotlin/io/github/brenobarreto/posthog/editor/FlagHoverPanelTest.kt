package io.github.brenobarreto.posthog.editor

import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.awt.datatransfer.DataFlavor
import javax.swing.JEditorPane
import javax.swing.event.HyperlinkEvent

class FlagHoverPanelTest : BasePlatformTestCase() {
    private val opened = mutableListOf<String>()
    private val copied = mutableListOf<String>()

    private fun handle(href: String) = FlagHoverPanel.handleLink(href, browse = { opened += it }, copy = { copied += it })

    fun testHttpsAndHttpLinksOpenTheBrowser() {
        assertTrue(handle("https://eu.posthog.com/project/7/feature_flags/42"))
        assertTrue(handle("http://localhost:8010/project/1/feature_flags"))
        assertEquals(listOf("https://eu.posthog.com/project/7/feature_flags/42", "http://localhost:8010/project/1/feature_flags"), opened)
        assertTrue(copied.isEmpty())
    }

    fun testCopyLinkDecodesTheKey() {
        assertTrue(handle("posthog-copy:a+b%26c"))
        assertEquals(listOf("a b&c"), copied)
        assertTrue(opened.isEmpty())
    }

    fun testEveryOtherSchemeIsIgnored() {
        listOf("javascript:alert(1)", "file:///etc/passwd", "ftp://x.test", "jar:file:/a.jar!/b", "command:run", "HTTPS://x.test", "", "//x.test", "mailto:a@b.c")
            .forEach { assertFalse(it, handle(it)) }
        assertTrue(opened.isEmpty())
        assertTrue(copied.isEmpty())
    }

    fun testTheDefaultCopyPutsTheKeyOnTheClipboard() {
        FlagHoverPanel.handleLink("posthog-copy:my-flag")
        assertEquals("my-flag", CopyPasteManager.getInstance().getContents<String>(DataFlavor.stringFlavor))
    }

    fun testThePanelRendersTheHtmlAndForwardsClickedLinks() {
        val clicked = mutableListOf<String>()
        val html = FlagHoverContent.html("beta", flag("beta", rollout = 50.0), null, null)
        val panel = FlagHoverPanel.create(html) { clicked += it }
        val pane = panel.components.filterIsInstance<JEditorPane>().single()

        assertTrue(pane.text, "beta" in pane.text && "Copy key" in pane.text)
        assertTrue(panel.preferredSize.width > 0 && panel.preferredSize.height > 0)

        val link = HyperlinkEvent(pane, HyperlinkEvent.EventType.ACTIVATED, null, "posthog-copy:beta")
        pane.hyperlinkListeners.forEach { it.hyperlinkUpdate(link) }
        pane.hyperlinkListeners.forEach { it.hyperlinkUpdate(HyperlinkEvent(pane, HyperlinkEvent.EventType.ENTERED, null, "https://x.test")) }
        assertEquals(listOf("posthog-copy:beta"), clicked)
    }

    fun testALongFlagKeyStillGivesABoundedPanel() {
        val key = "very-long-flag-key-".repeat(20)
        val panel = FlagHoverPanel.create(FlagHoverContent.html(key, flag(key), null, null)) {}
        assertTrue(panel.preferredSize.width < 1_000)
    }
}
