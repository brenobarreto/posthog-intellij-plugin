package io.github.brenobarreto.posthog.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.util.messages.Topic
import io.github.brenobarreto.posthog.PostHogBundle

enum class DisplayMode(private val bundleKey: String) {
    ALWAYS("settings.hints.mode.always"),
    CURRENT_LINE("settings.hints.mode.currentLine");

    override fun toString() = PostHogBundle.message(bundleKey)
}

fun interface PostHogSettingsListener {
    fun settingsChanged()

    companion object {
        @Topic.AppLevel
        val TOPIC: Topic<PostHogSettingsListener> = Topic.create("PostHog settings", PostHogSettingsListener::class.java)
    }
}

/** Display preferences. Credentials are kept elsewhere (the password safe). */
@Service(Service.Level.APP)
@State(name = "PostHogToolkitSettings", storages = [Storage("posthog-toolkit.xml")])
class PostHogSettings : PersistentStateComponent<PostHogSettings.State> {
    class State {
        var showHints: Boolean = true
        var mode: DisplayMode = DisplayMode.ALWAYS
        var extraClientNames: MutableList<String> = mutableListOf()
        var extraFlagFunctions: MutableList<String> = mutableListOf()
    }

    private var state = State()

    override fun getState() = state

    override fun loadState(state: State) {
        this.state = state
    }

    /** An immutable copy, safe to hand to a background thread. */
    fun snapshot() = Snapshot(state.showHints, state.mode, state.extraClientNames.toSet(), state.extraFlagFunctions.toSet())

    class Snapshot(val showHints: Boolean, val mode: DisplayMode, val extraClientNames: Set<String>, val extraFlagFunctions: Set<String>)

    fun notifyChanged() {
        ApplicationManager.getApplication().messageBus.syncPublisher(PostHogSettingsListener.TOPIC).settingsChanged()
    }

    companion object {
        fun getInstance(): PostHogSettings = service()

        private val IDENTIFIER = Regex("[A-Za-z_$][A-Za-z0-9_$]*")

        /** "analytics, flags" -> [analytics, flags]; anything that isn't a plain identifier is dropped. */
        fun parseNames(text: String): MutableList<String> =
            text.split(',', ' ', ';', '\n').map(String::trim).filter(IDENTIFIER::matches).distinct().toMutableList()
    }
}
