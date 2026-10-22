package io.github.brenobarreto.posthog.auth

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe

/** Blocking storage for the serialized credentials; call off the EDT. */
interface CredentialStore {
    fun load(): String?

    /** Passing null removes the entry. */
    fun save(value: String?)
}

class PasswordSafeCredentialStore : CredentialStore {
    private val attributes = CredentialAttributes(generateServiceName("PostHog Toolkit", "credentials"))

    override fun load(): String? = PasswordSafe.instance.getPassword(attributes)

    override fun save(value: String?) = PasswordSafe.instance.setPassword(attributes, value)
}
