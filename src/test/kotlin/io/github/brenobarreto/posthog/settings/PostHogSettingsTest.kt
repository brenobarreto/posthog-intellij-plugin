package io.github.brenobarreto.posthog.settings

import com.intellij.testFramework.fixtures.BasePlatformTestCase

class PostHogSettingsTest : BasePlatformTestCase() {
    private val settings get() = PostHogSettings.getInstance()

    override fun tearDown() {
        try {
            settings.loadState(PostHogSettings.State())
        } finally {
            super.tearDown()
        }
    }

    fun testNamesAreSplitCleanedAndDeduplicated() {
        assertEquals(listOf("analytics", "flags", "\$ph", "_x1"), PostHogSettings.parseNames("analytics, flags;\$ph\n_x1  analytics"))
        assertEquals(listOf("a"), PostHogSettings.parseNames(" a ,, , "))
    }

    fun testOnlyPlainIdentifiersAreKept() {
        assertEquals(listOf("ok"), PostHogSettings.parseNames("ok, 1bad, with-dash, a.b, (x), \"q\""))
        assertTrue(PostHogSettings.parseNames("").isEmpty())
    }

    fun testDefaultsShowEveryHint() {
        val snapshot = settings.snapshot()
        assertTrue(snapshot.showHints)
        assertEquals(DisplayMode.ALWAYS, snapshot.mode)
        assertTrue(snapshot.extraClientNames.isEmpty() && snapshot.extraFlagFunctions.isEmpty())
    }

    fun testSnapshotIsACopy() {
        settings.state.extraClientNames = mutableListOf("a")
        val snapshot = settings.snapshot()
        settings.state.extraClientNames.add("b")
        assertEquals(setOf("a"), snapshot.extraClientNames)
    }

    fun testStateSurvivesLoadState() {
        val loaded = PostHogSettings.State().apply {
            showHints = false
            mode = DisplayMode.CURRENT_LINE
            extraClientNames = mutableListOf("analytics")
        }
        settings.loadState(loaded)
        assertFalse(settings.state.showHints)
        assertEquals(DisplayMode.CURRENT_LINE, settings.state.mode)
        assertEquals(listOf("analytics"), settings.state.extraClientNames)
    }

    fun testTheSettingsPageStartsUnmodifiedAndApplyAnnouncesTheChange() {
        val configurable = PostHogSettingsConfigurable()
        try {
            configurable.createComponent()
            configurable.reset()
            assertFalse(configurable.isModified)

            var announced = 0
            com.intellij.openapi.application.ApplicationManager.getApplication().messageBus.connect(testRootDisposable)
                .subscribe(PostHogSettingsListener.TOPIC, PostHogSettingsListener { announced++ })
            configurable.apply()
            assertEquals(1, announced)
        } finally {
            configurable.disposeUIResources()
        }
    }

    fun testResetShowsTheStoredValues() {
        val configurable = PostHogSettingsConfigurable()
        try {
            configurable.createComponent()
            settings.state.showHints = false
            settings.state.extraFlagFunctions = mutableListOf("useFlag")
            assertTrue("the page notices that the stored values differ", configurable.isModified)
            configurable.reset()
            assertFalse(configurable.isModified)
        } finally {
            configurable.disposeUIResources()
        }
    }
}
