package io.github.brenobarreto.posthog.auth

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.util.messages.Topic
import kotlinx.coroutines.CoroutineScope

fun interface AuthListener {
    /** Called after sign-in, sign-out or a token refresh; null means signed out. */
    fun authChanged(credentials: AuthCredentials?)

    companion object {
        @Topic.AppLevel
        val TOPIC: Topic<AuthListener> = Topic.create("PostHog authentication", AuthListener::class.java)
    }
}

@Service(Service.Level.APP)
class AuthService(val scope: CoroutineScope) {
    val manager = AuthManager(
        store = PasswordSafeCredentialStore(),
        oauth = OAuthFlow(),
        fetchUser = AccountApi()::fetchUser,
        onChange = { ApplicationManager.getApplication().messageBus.syncPublisher(AuthListener.TOPIC).authChanged(it) },
    )

    companion object {
        fun getInstance(): AuthService = service()
    }
}
