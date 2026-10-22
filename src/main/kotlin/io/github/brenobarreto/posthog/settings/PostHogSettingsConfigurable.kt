package io.github.brenobarreto.posthog.settings

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.application.EDT
import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.NamedColorUtil
import io.github.brenobarreto.posthog.PostHogBundle
import io.github.brenobarreto.posthog.auth.AuthCredentials
import io.github.brenobarreto.posthog.auth.AuthException
import io.github.brenobarreto.posthog.auth.AuthMethod
import io.github.brenobarreto.posthog.auth.AuthService
import io.github.brenobarreto.posthog.auth.PostHogHosts
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.swing.JButton
import javax.swing.JComponent

/** Sign-in controls. Actions take effect immediately, so there is nothing to apply or reset. */
class PostHogSettingsConfigurable : SearchableConfigurable {
    private enum class Region(val bundleKey: String) {
        US("settings.region.us"), EU("settings.region.eu"), CUSTOM("settings.region.custom");

        override fun toString() = PostHogBundle.message(bundleKey)
    }

    private val auth get() = AuthService.getInstance()
    private var signInJob: Job? = null

    private val statusLabel = JBLabel()
    private val errorLabel = JBLabel().apply { foreground = NamedColorUtil.getErrorForeground() }
    private val signInButton = JButton(PostHogBundle.message("settings.button.signIn"))
    private val signOutButton = JButton(PostHogBundle.message("settings.button.signOut"))
    private val connectButton = JButton(PostHogBundle.message("settings.button.connect"))
    private val regionBox = com.intellij.openapi.ui.ComboBox(Region.entries.toTypedArray())
    private val customHostField = JBTextField(30)
    private val apiKeyField = JBPasswordField()

    override fun getId() = "io.github.brenobarreto.posthog.settings"

    override fun getDisplayName() = PostHogBundle.message("settings.displayName")

    override fun createComponent(): JComponent {
        signInButton.addActionListener { onSignInClicked() }
        signOutButton.addActionListener { onSignOutClicked() }
        connectButton.addActionListener { onConnectClicked() }
        regionBox.addActionListener { customHostField.isEnabled = regionBox.selectedItem == Region.CUSTOM }
        customHostField.isEnabled = false

        val component = panel {
            group(PostHogBundle.message("settings.account.title")) {
                row { cell(statusLabel) }
                row { cell(errorLabel) }
                row {
                    cell(signInButton)
                    cell(signOutButton)
                }
            }
            group(PostHogBundle.message("settings.apiKey.title")) {
                row { comment(PostHogBundle.message("settings.apiKey.comment")) }
                row(PostHogBundle.message("settings.apiKey.region")) { cell(regionBox) }
                row(PostHogBundle.message("settings.apiKey.customHost")) { cell(customHostField) }
                row(PostHogBundle.message("settings.apiKey.key")) { cell(apiKeyField).resizableColumn() }
                row { cell(connectButton) }
            }
        }
        auth.scope.launch { refreshStatus() }
        return component
    }

    override fun isModified() = false

    override fun apply() {}

    override fun disposeUIResources() {
        signInJob?.cancel()
    }

    private fun onSignInClicked() {
        if (signInJob?.isActive == true) {
            signInJob?.cancel()
            return
        }
        errorLabel.text = ""
        statusLabel.text = PostHogBundle.message("settings.status.waiting")
        signInButton.text = PostHogBundle.message("settings.button.cancel")
        signInJob = auth.scope.launch {
            runReportingErrors { auth.manager.signInWithOAuth { BrowserUtil.browse(it) } }
        }
    }

    private fun onSignOutClicked() {
        auth.scope.launch {
            auth.manager.signOut()
            refreshStatus()
        }
    }

    private fun onConnectClicked() {
        val host = when (regionBox.selectedItem as Region) {
            Region.US -> PostHogHosts.US
            Region.EU -> PostHogHosts.EU
            Region.CUSTOM -> customHostField.text
        }
        val key = String(apiKeyField.password)
        errorLabel.text = ""
        statusLabel.text = PostHogBundle.message("settings.status.working")
        connectButton.isEnabled = false
        auth.scope.launch {
            runReportingErrors {
                auth.manager.signInWithApiKey(host, key)
                withContext(Dispatchers.EDT) { apiKeyField.text = "" }
            }
        }
    }

    private suspend fun runReportingErrors(action: suspend () -> Unit) {
        var error: String? = null
        try {
            action()
        } catch (e: CancellationException) {
            throw e
        } catch (e: AuthException) {
            error = PostHogBundle.message("settings.error", e.message.orEmpty())
        } finally {
            withContext(NonCancellable) { refreshStatus(error) }
        }
    }

    private suspend fun refreshStatus(error: String? = null) {
        val credentials = auth.manager.current()
        withContext(Dispatchers.EDT) {
            statusLabel.text = describe(credentials)
            errorLabel.text = error.orEmpty()
            signInButton.text = PostHogBundle.message("settings.button.signIn")
            signOutButton.isEnabled = credentials != null
            connectButton.isEnabled = true
        }
    }

    private fun describe(credentials: AuthCredentials?): String = when (credentials?.method) {
        null -> PostHogBundle.message("settings.status.signedOut")
        AuthMethod.OAUTH -> PostHogBundle.message("settings.status.oauth", credentials.accountLabel ?: "?", credentials.host)
        AuthMethod.API_KEY -> PostHogBundle.message("settings.status.apiKey", credentials.accountLabel ?: "?", credentials.host)
    }
}
