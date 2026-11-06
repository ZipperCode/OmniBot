package cn.com.omnimind.baselib.llm.chatgpt

/** Opaque native authorization binding. Contains no token and is never serialized. */
class ChatGptOAuthCredentialBinding internal constructor(
    internal val profileId: String,
    internal val generation: Long,
)
