package io.github.brenobarreto.posthog

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.notification.NotificationGroupManager
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.components.service
import com.intellij.openapi.extensions.PluginId
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.brenobarreto.posthog.auth.AuthService
import io.github.brenobarreto.posthog.cache.ProjectDataService
import io.github.brenobarreto.posthog.cache.ProjectSettings

class PluginLoadTest : BasePlatformTestCase() {

    fun testPluginIsLoaded() {
        assertNotNull(PluginManagerCore.getPlugin(PluginId.getId("io.github.brenobarreto.posthog")))
    }

    fun testBundleResolves() {
        assertEquals("PostHog Toolkit (Community)", PostHogBundle.message("plugin.name"))
    }

    fun testActionsAreRegisteredWithText() {
        for (id in listOf("SelectProject", "RefreshData")) {
            val action = ActionManager.getInstance().getAction("io.github.brenobarreto.posthog.$id")
            assertNotNull(id, action)
            assertFalse(id, action.templateText.isNullOrBlank())
        }
    }

    fun testNotificationGroupIsRegistered() {
        assertNotNull(NotificationGroupManager.getInstance().getNotificationGroup("PostHog Toolkit"))
    }

    fun testServicesCreateAndStartSignedOut() {
        assertNotNull(service<AuthService>())
        val data = project.service<ProjectDataService>()
        assertNull(data.selectedProject)
        assertFalse(data.flags.isLoaded)
    }

    fun testSelectedProjectSurvivesInSettings() {
        val settings = ProjectSettings.getInstance(project)
        settings.select(42, "Web")
        assertEquals(42L, settings.selectedId)
        assertEquals(42L, project.service<ProjectDataService>().selectedProject?.id)
        settings.select(null)
        assertNull(settings.selectedId)
    }
}
