package io.github.brenobarreto.posthog.actions

import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import io.github.brenobarreto.posthog.PostHogBundle
import io.github.brenobarreto.posthog.settings.PostHogSettingsConfigurable

internal object PostHogNotifications {
    private const val GROUP = "PostHog Toolkit"

    fun info(project: Project, message: String) = show(project, message, NotificationType.INFORMATION)

    fun warning(project: Project, message: String) = show(project, message, NotificationType.WARNING)

    fun error(project: Project, message: String) = show(project, message, NotificationType.ERROR)

    fun notSignedIn(project: Project) {
        val notification = NotificationGroupManager.getInstance().getNotificationGroup(GROUP)
            .createNotification(PostHogBundle.message("notification.notSignedIn"), NotificationType.WARNING)
        notification.addAction(
            NotificationAction.createSimple(PostHogBundle.message("notification.openSettings")) {
                ShowSettingsUtil.getInstance().showSettingsDialog(project, PostHogSettingsConfigurable::class.java)
            },
        )
        notification.notify(project)
    }

    private fun show(project: Project, message: String, type: NotificationType) {
        NotificationGroupManager.getInstance().getNotificationGroup(GROUP).createNotification(message, type).notify(project)
    }
}
