package io.github.brenobarreto.posthog

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.options.Configurable
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.brenobarreto.posthog.settings.PostHogSettingsConfigurable

/** plugin.xml is not checked by the compiler or the verifier, so a typo there only shows up in a running IDE. */
class PluginRegistrationTest : BasePlatformTestCase() {
    fun `test settings page is registered under Tools and can be created`() {
        val ep = Configurable.APPLICATION_CONFIGURABLE.extensionList
            .single { it.id == "io.github.brenobarreto.posthog.settings" }
        assertEquals("tools", ep.parentId)
        assertTrue(ep.createConfigurable() is PostHogSettingsConfigurable)
    }

    fun `test actions are registered`() {
        val manager = ActionManager.getInstance()
        assertNotNull(manager.getAction("io.github.brenobarreto.posthog.SelectProject"))
        assertNotNull(manager.getAction("io.github.brenobarreto.posthog.RefreshData"))
    }
}
