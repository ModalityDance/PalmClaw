package com.palmclaw.providers

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal class ProviderResolutionStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE
    )
    private val json = Json { ignoreUnknownKeys = true }

    init {
        defaultInstance = this
    }

    @Synchronized
    fun load(cacheKey: String): ProviderExecutionTarget? {
        if (cacheKey.isBlank()) return null
        val record = readEntries()[cacheKey] ?: return null
        return ProviderExecutionTarget(
            protocol = record.protocol,
            endpointUrl = record.endpointUrl
        )
    }

    @Synchronized
    fun remember(cacheKey: String, target: ProviderExecutionTarget) {
        if (cacheKey.isBlank()) return
        val updated = readEntries().toMutableMap()
        updated[cacheKey] = PersistedTarget(
            protocol = target.protocol,
            endpointUrl = target.endpointUrl
        )
        writeEntries(updated)
    }

    @Synchronized
    fun clearByPrefix(prefix: String) {
        val normalizedPrefix = prefix.trim()
        if (normalizedPrefix.isBlank()) return
        val updated = readEntries()
            .filterKeys { !it.startsWith(normalizedPrefix) }
        writeEntries(updated)
    }

    private fun readEntries(): Map<String, PersistedTarget> {
        val raw = prefs.getString(KEY_TARGETS_JSON, null)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: return emptyMap()
        return runCatching {
            json.decodeFromString<Map<String, PersistedTarget>>(raw)
        }.getOrDefault(emptyMap())
    }

    private fun writeEntries(entries: Map<String, PersistedTarget>) {
        if (entries.isEmpty()) {
            prefs.edit().remove(KEY_TARGETS_JSON).apply()
            return
        }
        prefs.edit()
            .putString(KEY_TARGETS_JSON, json.encodeToString(entries))
            .apply()
    }

    @Serializable
    private data class PersistedTarget(
        val protocol: ProviderProtocol,
        val endpointUrl: String
    )

    companion object {
        private const val PREFS_NAME = "palmclaw_provider_resolution"
        private const val KEY_TARGETS_JSON = "targets_json"
        @Volatile
        private var defaultInstance: ProviderResolutionStore? = null

        fun cacheKeyFor(
            configId: String?,
            providerName: String,
            baseUrl: String,
            model: String
        ): String {
            val normalizedConfigId = configId?.trim().orEmpty()
            val normalizedBaseUrl = baseUrl.trim()
            val normalizedModel = model.trim()
            val normalizedProvider = providerName.trim()
            return if (normalizedConfigId.isNotBlank()) {
                "cfg:$normalizedConfigId|$normalizedProvider|$normalizedBaseUrl|$normalizedModel"
            } else {
                "adhoc:$normalizedProvider|$normalizedBaseUrl|$normalizedModel"
            }
        }

        fun cachePrefixForProviderConfig(configId: String): String {
            return "cfg:${configId.trim()}|"
        }

        fun default(): ProviderResolutionStore? = defaultInstance
    }
}
