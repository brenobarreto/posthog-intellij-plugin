package io.github.brenobarreto.posthog.cache

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.project.Project

/** The PostHog project chosen for this IDE project. Kept in the workspace file, so it isn't committed. */
@Service(Service.Level.PROJECT)
@State(name = "PostHogProjectSettings", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class ProjectSettings : PersistentStateComponent<ProjectSettings.State> {
    class State {
        var projectId: Long = 0
        var projectName: String = ""
    }

    private var state = State()

    override fun getState() = state

    override fun loadState(state: State) {
        this.state = state
    }

    val selectedId: Long? get() = state.projectId.takeIf { it > 0 }

    val selectedName: String get() = state.projectName

    fun select(id: Long?, name: String = "") {
        state.projectId = id ?: 0
        state.projectName = if (id == null) "" else name
    }

    companion object {
        fun getInstance(project: Project): ProjectSettings = project.getService(ProjectSettings::class.java)
    }
}
