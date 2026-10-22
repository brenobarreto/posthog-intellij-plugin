package io.github.brenobarreto.posthog.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbAware
import io.github.brenobarreto.posthog.PostHogBundle
import io.github.brenobarreto.posthog.cache.ProjectDataService
import kotlinx.coroutines.launch

class RefreshDataAction : AnAction(), DumbAware {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val service = project.service<ProjectDataService>()
        service.scope.launch {
            if (service.data.projectId == null) {
                PostHogNotifications.warning(project, PostHogBundle.message("notification.noProjectSelected"))
                return@launch
            }
            val errors = service.refreshNow()
            val failure = errors.firstOrNull()
            when {
                failure == null -> PostHogNotifications.info(
                    project,
                    PostHogBundle.message(
                        "notification.refreshed",
                        service.flags.items.size, service.experiments.items.size, service.selectedProject?.name.orEmpty(),
                    ),
                )
                failure.isAuthFailure && failure.status == 401 -> PostHogNotifications.notSignedIn(project)
                else -> PostHogNotifications.error(project, PostHogBundle.message("notification.refreshFailed", failure.message.orEmpty()))
            }
        }
    }
}
