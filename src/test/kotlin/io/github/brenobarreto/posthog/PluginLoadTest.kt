package io.github.brenobarreto.posthog

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.extensions.PluginId
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class PluginLoadTest : BasePlatformTestCase() {

    fun testPluginIsLoaded() {
        assertNotNull(PluginManagerCore.getPlugin(PluginId.getId("io.github.brenobarreto.posthog")))
    }

    fun testBundleResolves() {
        assertEquals("PostHog Toolkit (Community)", PostHogBundle.message("plugin.name"))
    }
}
