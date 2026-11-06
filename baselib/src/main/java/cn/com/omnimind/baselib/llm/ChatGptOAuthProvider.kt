package cn.com.omnimind.baselib.llm

/** Provider metadata only. OAuth credentials are resolved at the HTTP boundary. */
object ChatGptOAuthProvider {
    const val SOURCE_TYPE = "chatgpt_oauth"
    const val BASE_URL = "https://api.openai.com/v1"

    fun isOAuth(sourceType: String?): Boolean = sourceType == SOURCE_TYPE

    /** Both metadata edits and account changes invalidate catalog snapshots. */
    fun withAccountRevision(profile: ModelProviderProfile, accountRevision: Long): ModelProviderProfile {
        val metadataRevision = profile.revision - profile.credentialRevision
        return profile.copy(
            revision = metadataRevision + accountRevision,
            credentialRevision = accountRevision,
        )
    }

    fun normalize(profile: ModelProviderProfile): ModelProviderProfile {
        if (!isOAuth(profile.sourceType)) return profile
        return profile.copy(
            baseUrl = BASE_URL,
            apiKey = "",
            customHeaders = emptyMap(),
            protocolType = "openai_compatible",
            wireApi = OpenAiWireApi.RESPONSES,
        )
    }
}
