package io.github.brenobarreto.posthog.cache

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.util.messages.Topic
import io.github.brenobarreto.posthog.api.PostHogApiException
import io.github.brenobarreto.posthog.api.PostHogClient
import io.github.brenobarreto.posthog.auth.AuthCredentials
import io.github.brenobarreto.posthog.auth.AuthListener
import io.github.brenobarreto.posthog.auth.AuthMethod
import io.github.brenobarreto.posthog.auth.AuthService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import io.github.brenobarreto.posthog.api.Project as PostHogProject

fun interface ProjectDataListener {
    /** Called on a background thread after the flags, the experiments or the load status changed. */
    fun dataChanged(kind: DataKind)

    companion object {
        @Topic.ProjectLevel
        val TOPIC: Topic<ProjectDataListener> = Topic.create("PostHog project data", ProjectDataListener::class.java)
    }
}

/** Per IDE project: the selected PostHog project and its cached flags and experiments. */
@Service(Service.Level.PROJECT)
class ProjectDataService(private val project: Project, val scope: CoroutineScope) : Disposable {
    private val auth = AuthService.getInstance().manager
    private val settings = ProjectSettings.getInstance(project)
    private val api = PostHogClient(auth)

    val data = ProjectData(api, scope, onChange = { project.messageBus.syncPublisher(ProjectDataListener.TOPIC).dataChanged(it) })

    private var lastCredentials: AuthCredentials? = null

    init {
        ApplicationManager.getApplication().messageBus.connect(this)
            .subscribe(AuthListener.TOPIC, AuthListener { credentials -> scope.launch { onAuthChanged(credentials) } })
        scope.launch { onAuthChanged(auth.current()) }
    }

    val flags get() = data.flags
    val experiments get() = data.experiments

    /** The PostHog project chosen for this IDE project, if any. */
    val selectedProject: PostHogProject?
        get() = settings.selectedId?.let { PostHogProject(it, settings.selectedName) }

    /** Throws [PostHogApiException], with `isAuthFailure` set when nobody is signed in. */
    suspend fun availableProjects(): List<PostHogProject> = api.projects()

    fun selectProject(chosen: PostHogProject) {
        settings.select(chosen.id, chosen.name)
        if (data.projectId == chosen.id) data.start() else data.setProject(chosen.id)
    }

    /** Fetches everything now and returns what failed; resumes polling if it had stopped. */
    suspend fun refreshNow(): List<PostHogApiException> {
        val errors = listOfNotNull(data.refreshFlags(), data.refreshExperiments())
        if (errors.isEmpty() && !data.isPolling) data.start()
        return errors
    }

    override fun dispose() {}

    private suspend fun onAuthChanged(credentials: AuthCredentials?) {
        val previous = lastCredentials
        lastCredentials = credentials
        if (credentials == null) {
            data.clear()
            return
        }
        if (isRefreshOnly(previous, credentials)) return // an hourly token renewal; polling carries on
        val id = settings.selectedId ?: autoSelect()
        if (data.projectId == id) data.start() else data.setProject(id)
    }

    private fun isRefreshOnly(previous: AuthCredentials?, current: AuthCredentials) =
        previous != null && current.method == AuthMethod.OAUTH && previous.method == current.method &&
            previous.host == current.host && previous.accountLabel == current.accountLabel

    /** With exactly one project there is nothing to choose; otherwise the user picks via the action. */
    private suspend fun autoSelect(): Long? {
        val projects = try {
            api.projects()
        } catch (_: PostHogApiException) {
            return null
        }
        val only = projects.singleOrNull() ?: return null
        settings.select(only.id, only.name)
        return only.id
    }
}

/** Creates the service when a project opens, so polling starts without anyone asking for the data. */
class PostHogStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        project.service<ProjectDataService>()
    }
}
