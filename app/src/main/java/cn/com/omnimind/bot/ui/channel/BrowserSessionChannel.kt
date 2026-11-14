package cn.com.omnimind.bot.ui.channel

import cn.com.omnimind.bot.App
import cn.com.omnimind.bot.agent.LiveAgentBrowserSessionManager
import cn.com.omnimind.bot.agent.browser.AgentBrowserOverlayHost
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class BrowserSessionChannel {
    companion object {
        private const val CHANNEL = "cn.com.omnimind.bot/AgentBrowserSession"
    }

    private var methodChannel: MethodChannel? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    fun setChannel(flutterEngine: FlutterEngine) {
        methodChannel = MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL)
        methodChannel?.setMethodCallHandler(::handleMethodCall)
    }

    private fun handleMethodCall(
        call: MethodCall,
        result: MethodChannel.Result
    ) {
        val arguments = (call.arguments as? Map<*, *>)
            ?.entries
            ?.associate { (key, value) -> key.toString() to value }
            .orEmpty()
        scope.launch {
            runCatching {
                when (call.method) {
                    "isOverlayPermitted" ->
                        AgentBrowserOverlayHost.isOverlayPermitted(App.instance)
                    "requestOverlayPermission" -> {
                        // Launches the system overlay-permission settings screen;
                        // the permission itself is never auto-granted.
                        AgentBrowserOverlayHost.requestOverlayPermission(App.instance)
                        true
                    }
                    "openOverlayHost" -> openOverlayHost(arguments)
                    else -> routeBrowserCall(call.method, arguments)
                }
            }.onSuccess {
                result.success(it)
            }.onFailure { error ->
                if (error is NoSuchMethodError) {
                    result.notImplemented()
                } else {
                    result.error(
                        "BROWSER_SESSION_CALL_FAILED",
                        error.message ?: "browser_session_call_failed",
                        null
                    )
                }
            }
        }
    }

    /**
     * Opens the system overlay browser host for the conversation workspace the
     * caller addresses. The failure cause is distinguished so Flutter can show
     * either a "no session" hint or the overlay permission sheet.
     */
    private fun openOverlayHost(arguments: Map<String, Any?>): Map<String, Any?> {
        val workspaceId = arguments["workspaceId"]?.toString()?.trim().orEmpty()
        if (workspaceId.isEmpty()) {
            throw IllegalArgumentException("openOverlayHost requires workspaceId")
        }
        // An engine may still exist with every tab closed; only a live
        // snapshot means the workspace has a browsable session.
        val sessionAvailable =
            LiveAgentBrowserSessionManager.snapshotFor(workspaceId)["available"] == true
        if (!sessionAvailable) {
            return linkedMapOf(
                "opened" to false,
                "sessionAvailable" to false,
                "reason" to "no_browser_session"
            )
        }
        if (!AgentBrowserOverlayHost.isOverlayPermitted(App.instance)) {
            return linkedMapOf(
                "opened" to false,
                "sessionAvailable" to true,
                "reason" to "overlay_permission_missing"
            )
        }
        val opened = AgentBrowserOverlayHost.openForWorkspace(
            context = App.instance,
            workspaceId = workspaceId,
            expandImmediately = true
        )
        return linkedMapOf(
            "opened" to opened,
            "sessionAvailable" to true,
            "reason" to if (opened) null else "overlay_open_failed"
        )
    }

    private suspend fun routeBrowserCall(
        method: String,
        arguments: Map<String, Any?>
    ): Any? {
        // An explicitly supplied workspaceId makes the call workspace-owned:
        // it must be addressed to that workspace and never to the global
        // "current" session, otherwise a stale current from another
        // conversation could receive it. A blank-but-present workspaceId is an
        // addressing error, not a fallback. Calls with no workspace identity
        // at all keep the legacy current-session route.
        if (!arguments.containsKey("workspaceId")) {
            return LiveAgentBrowserSessionManager.handleCurrentCall(method, arguments)
        }
        val workspaceId = arguments["workspaceId"]?.toString()?.trim().orEmpty()
        if (workspaceId.isEmpty()) {
            throw IllegalArgumentException("workspaceId must not be blank")
        }
        return LiveAgentBrowserSessionManager.handleCall(workspaceId, method, arguments)
    }

    fun clear() {
        methodChannel?.setMethodCallHandler(null)
        methodChannel = null
    }
}
