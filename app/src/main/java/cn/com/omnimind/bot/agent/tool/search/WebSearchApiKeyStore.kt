package cn.com.omnimind.bot.agent.tool.search

import cn.com.omnimind.baselib.util.AppSecretStore

/** Keystore-backed API key storage for built-in web research tools. */
object WebSearchApiKeyStore {
    private const val KEY_PREFIX = "web_search_api_key."

    fun keyId(toolName: String): String = KEY_PREFIX + toolName

    fun put(toolName: String, apiKey: String): Boolean =
        AppSecretStore.write(keyId(toolName), apiKey.trim())

    fun get(toolName: String): String? =
        AppSecretStore.read(keyId(toolName))?.trim()?.takeIf { it.isNotEmpty() }

    fun delete(toolName: String): Boolean =
        AppSecretStore.delete(keyId(toolName))
}
