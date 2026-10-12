package com.opticalcenter.spatialnav.settings

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** External services the app needs a key for. */
enum class ApiService(
    val storageName: String,
    val title: String,
    val purpose: String,
    val signupUrl: String,
) {
    TOMTOM(
        storageName = "tomtom",
        title = "TomTom",
        purpose = "Place search, driving routes, live traffic and road conditions",
        signupUrl = "https://developer.tomtom.com",
    ),
    ANTHROPIC(
        storageName = "anthropic",
        title = "Claude (Anthropic)",
        purpose = "The AI voice assistant",
        signupUrl = "https://console.anthropic.com",
    ),
    ;

    /** Cheap local sanity check before any network call. Returns a problem, or null if it looks plausible. */
    fun formatProblem(key: String): String? = when {
        key.isBlank() -> "Enter a key."
        key.any { it.isWhitespace() } -> "Keys can't contain spaces or line breaks."
        this == ANTHROPIC && !key.startsWith("sk-ant-") -> "Claude API keys start with \"sk-ant-\"."
        this == TOMTOM && (key.length < 20 || !key.all { it.isLetterOrDigit() }) ->
            "TomTom keys are a long string of letters and numbers."
        else -> null
    }
}

/** Where the key currently in use came from. */
enum class KeySource { APP, BUILD, NONE }

sealed interface Verification {
    data object Unknown : Verification
    data object Checking : Verification
    data object Valid : Verification
    /** The service rejected the key (wrong, revoked, or lacking access). */
    data class Invalid(val message: String) : Verification
    /** Couldn't tell: offline, rate limited, or the service had an error. */
    data class Unreachable(val message: String) : Verification
}

data class KeyStatus(
    val service: ApiService,
    val source: KeySource,
    /** Masked form for display, e.g. "sk-ant-…9f2c". */
    val preview: String?,
    val verification: Verification,
)

/** Persistent secret storage. Implementations must encrypt at rest. */
interface SecretStore {
    fun get(name: String): String?
    fun put(name: String, value: String)
    fun remove(name: String)
}

/**
 * The app's API keys. A key saved in the app takes precedence over one compiled
 * in from local.properties; removing the app key falls back to the build key.
 */
class ApiKeyRepository(
    private val store: SecretStore,
    private val buildDefaults: Map<ApiService, String> = emptyMap(),
) {
    private val _status = MutableStateFlow(ApiService.entries.associateWith { statusFor(it, Verification.Unknown) })
    val status: StateFlow<Map<ApiService, KeyStatus>> = _status.asStateFlow()

    /** Bumped whenever an effective key changes, so dependants can rebuild their clients. */
    private val _revision = MutableStateFlow(0)
    val revision: StateFlow<Int> = _revision.asStateFlow()

    fun key(service: ApiService): String? =
        store.get(service.storageName)?.takeIf { it.isNotBlank() }
            ?: buildDefaults[service]?.takeIf { it.isNotBlank() }

    fun source(service: ApiService): KeySource = when {
        !store.get(service.storageName).isNullOrBlank() -> KeySource.APP
        !buildDefaults[service].isNullOrBlank() -> KeySource.BUILD
        else -> KeySource.NONE
    }

    /** Validate the format and save. Returns a problem message, or null on success. */
    fun save(service: ApiService, rawKey: String): String? {
        val key = rawKey.trim()
        service.formatProblem(key)?.let { return it }
        if (key == key(service) && source(service) == KeySource.APP) return null
        store.put(service.storageName, key)
        changed(service)
        return null
    }

    fun remove(service: ApiService) {
        if (store.get(service.storageName) == null) return
        store.remove(service.storageName)
        changed(service)
    }

    fun setVerification(service: ApiService, verification: Verification) {
        _status.update { it + (service to statusFor(service, verification)) }
    }

    private fun changed(service: ApiService) {
        _status.update { it + (service to statusFor(service, Verification.Unknown)) }
        _revision.update { it + 1 }
    }

    private fun statusFor(service: ApiService, verification: Verification) =
        KeyStatus(service, source(service), key(service)?.let(::mask), verification)

    companion object {
        /** Show enough to recognise a key without revealing it. */
        fun mask(key: String): String = when {
            key.length <= 8 -> "•".repeat(key.length)
            key.startsWith("sk-ant-") -> "sk-ant-…${key.takeLast(4)}"
            else -> "${key.take(3)}…${key.takeLast(4)}"
        }
    }
}

/** Non-persistent store for tests and previews. */
class InMemorySecretStore : SecretStore {
    private val values = mutableMapOf<String, String>()
    override fun get(name: String) = values[name]
    override fun put(name: String, value: String) {
        values[name] = value
    }
    override fun remove(name: String) {
        values.remove(name)
    }
}
