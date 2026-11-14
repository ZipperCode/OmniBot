package cn.com.omnimind.bot.agent

import android.content.Context
import android.view.ViewGroup
import cn.com.omnimind.bot.agent.browser.AgentBrowserOverlayHost

interface AgentBrowserLiveSessionHandle {
    val workspaceId: String

    fun closeSession()
}

class LiveAgentBrowserSessionStore<T : AgentBrowserLiveSessionHandle> {
    private val sessions = linkedMapOf<String, T>()
    @Volatile
    private var currentWorkspaceId: String? = null

    @Synchronized
    fun acquire(workspaceId: String, create: () -> T): T {
        val existing = sessions[workspaceId]
        currentWorkspaceId = workspaceId
        return existing ?: create().also { sessions[workspaceId] = it }
    }

    @Synchronized
    fun current(): T? = currentWorkspaceId?.let(sessions::get)

    @Synchronized
    fun get(workspaceId: String): T? = sessions[workspaceId]

    @Synchronized
    fun clear() {
        sessions.values.toList().forEach { it.closeSession() }
        sessions.clear()
        currentWorkspaceId = null
    }
}

object LiveAgentBrowserSessionManager {
    private val store = LiveAgentBrowserSessionStore<BrowserUseEngine>()

    fun acquireEngine(
        context: Context,
        workspaceManager: AgentWorkspaceManager,
        agentRunId: String,
        workspace: AgentWorkspaceDescriptor
    ): BrowserUseEngine {
        val engine = store.acquire(workspace.id) {
            BrowserUseEngine(
                context = context,
                workspaceManager = workspaceManager,
                agentRunId = agentRunId,
                workspace = workspace
            )
        }
        // The Operit-style system overlay ball is bound to the live session, so
        // it appears exactly when a browser session exists and follows the same
        // snapshot contract as the Flutter in-app surface.
        AgentBrowserOverlayHost.onSessionAvailable(context, workspace.id)
        return engine
    }

    fun currentSnapshot(): Map<String, Any?> {
        return store.current()?.liveSessionSnapshot() ?: BrowserUseEngine.unavailableSnapshot()
    }

    suspend fun handleCurrentCall(
        method: String,
        arguments: Map<String, Any?> = emptyMap()
    ): Any? {
        val engine = store.current()
        return if (engine == null) {
            if (
                method == "getSnapshot" ||
                method == "getLiveBrowserSessionSnapshot" ||
                method == "capturePreviewFrame"
            ) {
                BrowserUseEngine.unavailableSnapshot()
            } else {
                throw IllegalStateException("当前没有可用的浏览器会话")
            }
        } else {
            engine.handleHostCall(method = method, arguments = arguments)
        }
    }

    suspend fun executeCurrent(request: BrowserUseRequest): BrowserUseOutcome? {
        return store.current()?.execute(request)
    }

    suspend fun captureCurrentFramePng(): ByteArray? {
        return store.current()?.captureActiveFramePng()
    }

    fun attachActiveTabTo(
        container: ViewGroup,
        hostContext: Context,
        workspaceId: String
    ): Boolean {
        val engine = store.current()
        if (engine == null || engine.workspaceId != workspaceId) {
            return false
        }
        return engine.attachActiveTabTo(container = container, hostContext = hostContext)
    }

    fun detachActiveTabFrom(container: ViewGroup? = null) {
        store.current()?.detachActiveTabFrom(container)
    }

    // ---- Workspace-scoped API -------------------------------------------------
    // The system overlay host (and any native surface) must address browser
    // commands by workspaceId through these helpers. The current-session API
    // above is legacy for paths that have no workspace identity; never route a
    // workspace-owned operation through it, or a stale "current" session from
    // another conversation would receive the command.

    fun hasSession(workspaceId: String): Boolean =
        store.get(workspaceId) != null

    fun snapshotFor(workspaceId: String): Map<String, Any?> {
        return store.get(workspaceId)?.liveSessionSnapshot()
            ?: BrowserUseEngine.unavailableSnapshot(workspaceId = workspaceId)
    }

    suspend fun handleCall(
        workspaceId: String,
        method: String,
        arguments: Map<String, Any?> = emptyMap()
    ): Any? {
        val engine = store.get(workspaceId)
        return if (engine == null) {
            if (
                method == "getSnapshot" ||
                method == "getLiveBrowserSessionSnapshot" ||
                method == "capturePreviewFrame"
            ) {
                BrowserUseEngine.unavailableSnapshot(workspaceId = workspaceId)
            } else {
                throw IllegalStateException("当前会话没有可用的浏览器会话")
            }
        } else {
            engine.handleHostCall(method = method, arguments = arguments)
        }
    }

    fun attachTabTo(
        workspaceId: String,
        container: ViewGroup,
        hostContext: Context
    ): Boolean {
        val engine = store.get(workspaceId) ?: return false
        return engine.attachActiveTabTo(container = container, hostContext = hostContext)
    }

    fun detachTabFrom(workspaceId: String, container: ViewGroup? = null) {
        store.get(workspaceId)?.detachActiveTabFrom(container)
    }

    private val snapshotListeners =
        java.util.concurrent.CopyOnWriteArrayList<(Map<String, Any?>) -> Unit>()

    fun addSnapshotListener(listener: (Map<String, Any?>) -> Unit) {
        snapshotListeners.addIfAbsent(listener)
    }

    fun removeSnapshotListener(listener: (Map<String, Any?>) -> Unit) {
        snapshotListeners.remove(listener)
    }

    /**
     * Fans a browser snapshot out to native surfaces that mirror the live
     * session (the system overlay browser host). Flutter keeps using
     * [cn.com.omnimind.bot.webchat.FlutterChatSyncBridge]; this is the same
     * snapshot, not a second protocol.
     */
    fun dispatchSnapshot(snapshot: Map<String, Any?>) {
        snapshotListeners.forEach { listener ->
            runCatching { listener(snapshot) }
        }
    }

    fun releaseRunOwnership() {
        // 浏览器会话改为全局保活；run 结束时不销毁会话。
    }
}
