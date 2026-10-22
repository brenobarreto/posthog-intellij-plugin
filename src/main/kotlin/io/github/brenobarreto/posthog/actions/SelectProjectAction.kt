package io.github.brenobarreto.posthog.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.SimpleListCellRenderer
import io.github.brenobarreto.posthog.PostHogBundle
import io.github.brenobarreto.posthog.api.PostHogApiException
import io.github.brenobarreto.posthog.cache.ProjectDataService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.swing.JList
import io.github.brenobarreto.posthog.api.Project as PostHogProject

class SelectProjectAction : AnAction(), DumbAware {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val service = project.service<ProjectDataService>()
        service.scope.launch {
            val projects = try {
                service.availableProjects()
            } catch (ex: PostHogApiException) {
                if (ex.isAuthFailure) PostHogNotifications.notSignedIn(project)
                else PostHogNotifications.error(project, PostHogBundle.message("notification.loadProjectsFailed", ex.message.orEmpty()))
                return@launch
            }
            if (projects.isEmpty()) {
                PostHogNotifications.warning(project, PostHogBundle.message("notification.noProjects"))
                return@launch
            }
            val currentId = service.selectedProject?.id
            withContext(Dispatchers.EDT) {
                JBPopupFactory.getInstance().createPopupChooserBuilder(projects)
                    .setTitle(PostHogBundle.message("popup.selectProject"))
                    .setRenderer(object : SimpleListCellRenderer<PostHogProject>() {
                        override fun customize(
                            list: JList<out PostHogProject>, value: PostHogProject?, index: Int, selected: Boolean, hasFocus: Boolean,
                        ) {
                            text = if (value?.id == currentId) "${value?.name}  ✓" else value?.name.orEmpty()
                        }
                    })
                    .setItemChosenCallback { service.selectProject(it) }
                    .createPopup()
                    .showCenteredInCurrentWindow(project)
            }
        }
    }
}
