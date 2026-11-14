package cn.com.omnimind.bot.mcp

import cn.com.omnimind.bot.webchat.AgentRunService
import cn.com.omnimind.bot.webchat.BrowserMirrorService
import cn.com.omnimind.bot.webchat.ConversationDomainService
import cn.com.omnimind.bot.webchat.RealtimeHub
import cn.com.omnimind.bot.webchat.WebChatAvatarService
import cn.com.omnimind.bot.webchat.WorkspaceFileService
import cn.com.omnimind.bot.agent.conversation.OutboxMutation
import cn.com.omnimind.bot.agent.conversation.OutboxException
import cn.com.omnimind.bot.agent.runtime.AgentRuntimeManager
import cn.com.omnimind.bot.agent.SkillAssetManagementException
import cn.com.omnimind.bot.agent.manageAssets
import cn.com.omnimind.bot.agent.workspace.WorkspaceChangeException
import cn.com.omnimind.bot.agent.workspace.WorkspaceChangeService
import cn.com.omnimind.bot.agent.workspace.manageChanges
import cn.com.omnimind.bot.workspace.WorkspaceStorageAccess
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.google.gson.reflect.TypeToken
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.UnsupportedMediaTypeException
import io.ktor.server.request.contentType
import io.ktor.server.request.receiveText
import io.ktor.server.response.header
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondFile
import io.ktor.server.response.respondText
import io.ktor.server.response.respondTextWriter
import io.ktor.server.routing.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collect

/**
 * WebChat API 路由注册。
 *
 * 从 McpServerManager 拆分而来，包含对话管理、事件流、工作区文件、浏览器镜像等路由。
 */
object WebChatRoutes {

    private val gson by lazy { Gson() }
    private val requestBodyType = object : TypeToken<Map<String, Any?>>() {}.type

    /** Keep WebChat maps out of the MCP kotlinx converter, which cannot deserialize Any. */
    internal suspend fun ApplicationCall.receiveJson(): Map<String, Any?> {
        val contentType = request.contentType()
        if (!contentType.match(ContentType.Application.Json)) {
            throw UnsupportedMediaTypeException(contentType)
        }
        val text = receiveText()
        return try {
            val body = gson.fromJson(text, JsonObject::class.java)
                ?: throw BadRequestException("Expected a JSON object")
            gson.fromJson<Map<String, Any?>>(body, requestBodyType)
        } catch (error: JsonParseException) {
            throw BadRequestException("Invalid JSON request", error)
        }
    }

    /** Serialize heterogeneous WebChat payloads explicitly instead of relying on Ktor's map serializer. */
    internal suspend fun ApplicationCall.respondJson(
        payload: Any?,
        status: HttpStatusCode = HttpStatusCode.OK
    ) {
        respondText(gson.toJson(payload), ContentType.Application.Json, status)
    }

    fun Route.registerWebChatRoutes(
        conversationService: ConversationDomainService,
        workspaceFileService: WorkspaceFileService,
        browserMirrorService: BrowserMirrorService,
        agentRunService: AgentRunService,
        webChatAvatarService: WebChatAvatarService
    ) {
        route("/webchat/api") {
            registerAssetRoutes(conversationService)
            get("/bootstrap") {
                if (!McpServerManager.requireWebChatAuth(call)) return@get
                call.respondJson(
                    mapOf(
                        "server" to McpServerManager.currentState().toMap(),
                        "capabilities" to mapOf(
                            "conversations" to true,
                            "streaming" to true,
                            "workspace" to true,
                            "browserMirror" to true
                        ),
                        "agentProfiles" to conversationService.listWebAgentProfiles(),
                        "retiredAgentIds" to conversationService.retiredAgentIds(),
                        "routes" to mapOf(
                            "events" to "/webchat/api/events",
                            "browserFrame" to "/webchat/api/browser/frame",
                            "workspaceDownload" to "/webchat/api/workspaces/download"
                        ),
                        "workspace" to workspaceFileService.bootstrapPayload(),
                        "browser" to browserMirrorService.snapshot()
                    )
                )
            }

            get("/avatar") {
                if (!McpServerManager.requireWebChatAuth(call)) return@get
                val avatar = webChatAvatarService.load()
                call.response.header(HttpHeaders.CacheControl, "private, max-age=300")
                call.respondBytes(avatar.bytes, avatar.contentType)
            }

            get("/conversations") {
                if (!McpServerManager.requireWebChatAuth(call)) return@get
                val includeArchived = call.request.queryParameters.boolean("includeArchived", true)
                val archivedOnly = call.request.queryParameters.boolean("archivedOnly", false)
                call.respondJson(
                    conversationService.listConversationPayloads(
                        includeArchived = includeArchived,
                        archivedOnly = archivedOnly
                    )
                )
            }

            post("/conversations") {
                if (!McpServerManager.requireWebChatAuth(call)) return@post
                val body = call.receiveJson()
                call.respondJson(
                    conversationService.createConversation(
                        title = body["title"]?.toString() ?: "新对话",
                        mode = body["mode"]?.toString() ?: "agent",
                        summary = body["summary"]?.toString(),
                        parentConversationId = (body["parentConversationId"] as? Number)
                            ?.toLong(),
                        parentConversationMode = body["parentConversationMode"]?.toString(),
                        scheduledTaskId = body["scheduledTaskId"]?.toString(),
                        agentId = body["agentId"]?.toString()
                    ),
                    HttpStatusCode.Created
                )
            }

            patch("/conversations/{conversationId}") {
                if (!McpServerManager.requireWebChatAuth(call)) return@patch
                val conversationId = call.parameters["conversationId"]?.toLongOrNull()
                if (conversationId == null || conversationId <= 0L) {
                    call.respondJson(mapOf("error" to "INVALID_CONVERSATION_ID"), HttpStatusCode.BadRequest)
                    return@patch
                }
                val body = call.receiveJson().toMutableMap()
                body["id"] = conversationId
                call.respondJson(
                    conversationService.updateConversationFromPayload(body)
                )
            }

            delete("/conversations/{conversationId}") {
                if (!McpServerManager.requireWebChatAuth(call)) return@delete
                val conversationId = call.parameters["conversationId"]?.toLongOrNull()
                if (conversationId == null || conversationId <= 0L) {
                    call.respondJson(mapOf("error" to "INVALID_CONVERSATION_ID"), HttpStatusCode.BadRequest)
                    return@delete
                }
                conversationService.deleteConversation(conversationId)
                call.respondJson(mapOf("success" to true))
            }

            get("/conversations/{conversationId}/messages") {
                if (!McpServerManager.requireWebChatAuth(call)) return@get
                val conversationId = call.parameters["conversationId"]?.toLongOrNull()
                if (conversationId == null || conversationId <= 0L) {
                    call.respondJson(mapOf("error" to "INVALID_CONVERSATION_ID"), HttpStatusCode.BadRequest)
                    return@get
                }
                val mode = call.request.queryParameters["mode"] ?: "agent"
                val finalizeInterruptedEntries = !agentRunService.hasActiveConversationRun(
                    conversationId = conversationId,
                    conversationMode = mode
                )
                call.respondJson(
                    conversationService.listConversationMessages(
                        conversationId = conversationId,
                        conversationMode = mode,
                        finalizeInterruptedEntries = finalizeInterruptedEntries
                    )
                )
            }

            get("/conversations/{conversationId}/outbox") {
                if (!McpServerManager.requireWebChatAuth(call)) return@get
                val conversationId = (call.parameters["conversationId"] ?: call.parameters["id"])?.toLongOrNull()
                if (conversationId == null || conversationId <= 0L) {
                    call.respondJson(mapOf("error" to "INVALID_CONVERSATION_ID"), HttpStatusCode.BadRequest)
                    return@get
                }
                if (!conversationService.canReadConversation(conversationId)) {
                    call.respondJson(mapOf("error" to "CONVERSATION_NOT_FOUND"), HttpStatusCode.NotFound)
                    return@get
                }
                val runtime = AgentRuntimeManager.getInstance(conversationService.context)
                val snapshot = runtime.conversationOutboxService.list(conversationId)
                call.respondJson(snapshot.toMap())
            }

            post("/conversations/{conversationId}/outbox/attachments") {
                if (!McpServerManager.requireWebChatAuth(call)) return@post
                val conversationId = call.parameters["conversationId"]?.toLongOrNull()
                if (conversationId == null || conversationId <= 0L) {
                    call.respondJson(mapOf("error" to "INVALID_CONVERSATION_ID"), HttpStatusCode.BadRequest)
                    return@post
                }
                val body = call.receiveJson()
                val attachments = (body["attachments"] as? List<*>)?.map { item ->
                    @Suppress("UNCHECKED_CAST")
                    (item as? Map<String, Any?>) ?: throw BadRequestException("附件格式无效")
                } ?: throw BadRequestException("缺少附件")
                try {
                    val imported = conversationService.importOutboxAttachments(
                        conversationId, attachments, importedOrDataOnly = true
                    )
                    call.respondJson(mapOf("attachments" to imported))
                } catch (error: OutboxException) {
                    call.respondJson(
                        mapOf("error" to error.errorCode, "message" to error.message),
                        HttpStatusCode.BadRequest
                    )
                }
            }

            post("/conversations/{conversationId}/outbox") {
                if (!McpServerManager.requireWebChatAuth(call)) return@post
                val conversationId = (call.parameters["conversationId"] ?: call.parameters["id"])?.toLongOrNull()
                if (conversationId == null || conversationId <= 0L) {
                    call.respondJson(mapOf("error" to "INVALID_CONVERSATION_ID"), HttpStatusCode.BadRequest)
                    return@post
                }
                if (!conversationService.canReadConversation(conversationId)) {
                    call.respondJson(mapOf("error" to "CONVERSATION_NOT_FOUND"), HttpStatusCode.NotFound)
                    return@post
                }
                val body = call.receiveJson()
                val operation = (body["operation"] as? String)?.trim().orEmpty()
                val requestMap = (body["request"] as? Map<String, Any?>) ?: body
                val mutation = OutboxMutation(
                    id = requestMap["id"] as? String,
                    expectedRevision = (requestMap["expectedRevision"] as? Number)?.toLong(),
                    payload = (requestMap["payload"] as? Map<String, Any?>)
                        ?: (requestMap["promptSession"] as? Map<String, Any?>),
                    expectedSessionId = requestMap["expectedSessionId"] as? String,
                    expectedTurnId = requestMap["expectedTurnId"] as? String
                )
                val runtime = AgentRuntimeManager.getInstance(conversationService.context)
                try {
                    val snapshot = runtime.conversationOutboxService.mutate(conversationId, operation, mutation)
                    call.respondJson(snapshot.toMap())
                } catch (e: OutboxException) {
                    val status = when (e.errorCode) {
                        "outbox_revision_conflict", "outbox_target_changed" -> HttpStatusCode.Conflict
                        "outbox_item_not_found" -> HttpStatusCode.NotFound
                        "outbox_limit_exceeded", "outbox_empty", "outbox_payload_too_large",
                        "outbox_attachment_unavailable", "outbox_invalid_operation", "outbox_immutable_state" -> HttpStatusCode.BadRequest
                        else -> HttpStatusCode.BadRequest
                    }
                    val errPayload = mutableMapOf<String, Any?>(
                        "error" to e.errorCode,
                        "message" to e.message
                    )
                    e.snapshot?.let { errPayload["snapshot"] = it.toMap() }
                    call.respondJson(errPayload, status)
                } catch (e: Exception) {
                    call.respondJson(
                        mapOf("error" to (e.message ?: "OUTBOX_ERROR")),
                        HttpStatusCode.InternalServerError
                    )
                }
            }

            post("/conversations/{conversationId}/runs") {
                if (!McpServerManager.requireWebChatAuth(call)) return@post
                val conversationId = call.parameters["conversationId"]?.toLongOrNull()
                if (conversationId == null || conversationId <= 0L) {
                    call.respondJson(mapOf("error" to "INVALID_CONVERSATION_ID"), HttpStatusCode.BadRequest)
                    return@post
                }
                val body = call.receiveJson()
                val accepted = runCatching {
                    agentRunService.startConversationRun(conversationId, body)
                }.getOrElse { error ->
                    call.respondJson(mapOf("error" to (error.message ?: "RUN_START_FAILED")), HttpStatusCode.Conflict)
                    return@post
                }
                call.respondJson(accepted, HttpStatusCode.Accepted)
            }

            post("/tasks/{taskId}/cancel") {
                if (!McpServerManager.requireWebChatAuth(call)) return@post
                val taskId = call.parameters["taskId"]?.trim().takeUnless { it.isNullOrEmpty() }
                call.respondJson(agentRunService.cancelTask(taskId))
            }

            post("/tasks/{taskId}/clarify") {
                if (!McpServerManager.requireWebChatAuth(call)) return@post
                val taskId = call.parameters["taskId"]?.trim().takeUnless { it.isNullOrEmpty() }
                val body = call.receiveJson()
                val reply = body["reply"]?.toString() ?: body["userInput"]?.toString().orEmpty()
                if (reply.isBlank()) {
                    call.respondJson(mapOf("error" to "EMPTY_REPLY"), HttpStatusCode.BadRequest)
                    return@post
                }
                call.respondJson(agentRunService.clarifyTask(taskId, reply))
            }

            get("/events") {
                if (!McpServerManager.requireWebChatAuth(call)) return@get
                call.response.header(HttpHeaders.CacheControl, "no-cache")
                call.response.header(HttpHeaders.Connection, "keep-alive")
                call.respondTextWriter(contentType = ContentType.Text.EventStream) {
                    write(": connected\n\n")
                    flush()
                    RealtimeHub.stream().collect { event ->
                        val conversationId = (event.data["conversationId"] as? Number)?.toLong()
                        if (conversationId != null && !conversationService.canReadConversation(conversationId)) {
                            return@collect
                        }
                        write("id: ${event.id}\n")
                        write("event: ${event.event}\n")
                        write("data: ${gson.toJson(event.data)}\n\n")
                        flush()
                    }
                }
            }

            registerWorkspaceRoutes(workspaceFileService)
            registerBrowserRoutes(browserMirrorService)
        }
    }

    private fun Route.registerAssetRoutes(conversationService: ConversationDomainService) {
        val context = conversationService.context
        suspend fun ApplicationCall.skillAssets(operation: String, arguments: Map<String, Any?>) {
            try {
                if (!WorkspaceStorageAccess.isGranted(context)) {
                    respondJson(mapOf("code" to "forbidden", "message" to "请先授予工作区访问权限"), HttpStatusCode.Forbidden)
                    return
                }
                val conversationId = (arguments["conversationId"] as? Number)?.toLong()
                if (conversationId != null && conversationService.getConversationPayload(conversationId) == null) {
                    respondJson(mapOf("code" to "not_found", "message" to "来源对话已不可用"), HttpStatusCode.NotFound)
                    return
                }
                respondJson(AgentRuntimeManager.getInstance(context).skillSuggestionService.manageAssets(operation, arguments))
            } catch (error: CancellationException) {
                throw error
            } catch (error: SkillAssetManagementException) {
                val status = when (error.code) {
                    "skill_candidate_not_found" -> HttpStatusCode.NotFound
                    "skill_revision_conflict", "skill_name_conflict", "skill_content_conflict",
                    "skill_content_modified", "skill_feedback_ineligible", "skill_revision_retired" -> HttpStatusCode.Conflict
                    "skill_asset_invalid" -> HttpStatusCode.InternalServerError
                    else -> HttpStatusCode.BadRequest
                }
                respondJson(mapOf("code" to error.code, "message" to error.message), status)
            }
        }
        suspend fun ApplicationCall.workspaceChanges(operation: String, arguments: Map<String, Any?>) {
            try {
                respondJson(WorkspaceChangeService.getInstance(context).manageChanges(context, operation, arguments))
            } catch (error: CancellationException) {
                throw error
            } catch (error: WorkspaceChangeException) {
                val status = when (error.errorCode) {
                    "not_found" -> HttpStatusCode.NotFound
                    "forbidden" -> HttpStatusCode.Forbidden
                    "workspace_change_busy", "workspace_change_conflict" -> HttpStatusCode.Conflict
                    else -> HttpStatusCode.BadRequest
                }
                respondJson(mapOf("code" to error.errorCode, "message" to error.message, "snapshot" to error.snapshot), status)
            }
        }
        fun ApplicationCall.assetQuery(): Map<String, Any?> {
            val query = request.queryParameters
            fun number(key: String): Long? = query[key]?.let {
                it.toLongOrNull() ?: throw BadRequestException("数值字段无效：$key")
            }
            return buildMap {
                number("conversationId")?.let { put("conversationId", it) }
                number("offset")?.let { put("offset", it) }
                number("limit")?.let { put("limit", it) }
                query["turnId"]?.let { put("turnId", it) }
                query["skillId"]?.let { put("skillId", it) }
                query["workspaceId"]?.let { put("workspaceId", it) }
            }
        }
        get("/skills/suggestions") {
            if (!McpServerManager.requireWebChatAuth(call)) return@get
            call.skillAssets("list", call.assetQuery())
        }
        post("/skills/suggestions") {
            if (!McpServerManager.requireWebChatAuth(call)) return@post
            val body = call.receiveJson()
            call.skillAssets(body["operation"] as? String ?: "", body)
        }
        get("/conversations/{conversationId}/workspace-changes") {
            if (!McpServerManager.requireWebChatAuth(call)) return@get
            val id = call.parameters["conversationId"]?.toLongOrNull()
                ?: throw BadRequestException("来源对话标识无效")
            call.workspaceChanges("list", call.assetQuery() + ("conversationId" to id))
        }
        get("/workspaces/{workspaceId}/changes") {
            if (!McpServerManager.requireWebChatAuth(call)) return@get
            val workspaceId = call.parameters["workspaceId"]
                ?: throw BadRequestException("缺少工作区标识")
            call.workspaceChanges("list_by_workspace", call.assetQuery() + ("workspaceId" to workspaceId))
        }
        get("/workspace-changes/{changeId}") {
            if (!McpServerManager.requireWebChatAuth(call)) return@get
            val id = call.parameters["changeId"] ?: throw BadRequestException("缺少变更标识")
            call.workspaceChanges("preview", call.assetQuery() + ("changeId" to id))
        }
        post("/workspace-changes/{changeId}") {
            if (!McpServerManager.requireWebChatAuth(call)) return@post
            val id = call.parameters["changeId"] ?: throw BadRequestException("缺少变更标识")
            val body = call.receiveJson()
            val operation = body["operation"] as? String ?: ""
            if (operation != "restore" && operation != "discard") {
                throw BadRequestException("未知变更管理操作")
            }
            call.workspaceChanges(operation, body + ("changeId" to id))
        }
    }

    private fun Route.registerWorkspaceRoutes(
        workspaceFileService: WorkspaceFileService
    ) {
        route("/workspaces") {
            get {
                if (!McpServerManager.requireWebChatAuth(call)) return@get
                val path = call.request.queryParameters["path"]
                val recursive = call.request.queryParameters.boolean("recursive", false)
                val maxDepth = call.request.queryParameters["maxDepth"]?.toIntOrNull()
                val limit = call.request.queryParameters["limit"]?.toIntOrNull()
                call.respondJson(
                    if (path.isNullOrBlank()) {
                        workspaceFileService.bootstrapPayload()
                    } else {
                        workspaceFileService.list(
                            path = path,
                            recursive = recursive,
                            maxDepth = maxDepth,
                            limit = limit
                        )
                    }
                )
            }

            get("/file") {
                if (!McpServerManager.requireWebChatAuth(call)) return@get
                val path = call.request.queryParameters["path"]
                if (path.isNullOrBlank()) {
                    call.respondJson(mapOf("error" to "MISSING_PATH"), HttpStatusCode.BadRequest)
                    return@get
                }
                val maxChars = call.request.queryParameters["maxChars"]?.toIntOrNull()
                val offset = call.request.queryParameters["offset"]?.toIntOrNull() ?: 0
                val lineStart = call.request.queryParameters["lineStart"]?.toIntOrNull()
                val lineCount = call.request.queryParameters["lineCount"]?.toIntOrNull()
                call.respondJson(
                    workspaceFileService.readFile(
                        path = path,
                        maxChars = maxChars,
                        offset = offset,
                        lineStart = lineStart,
                        lineCount = lineCount
                    )
                )
            }

            put("/file") {
                if (!McpServerManager.requireWebChatAuth(call)) return@put
                val body = call.receiveJson()
                val path = body["path"]?.toString().orEmpty()
                if (path.isBlank()) {
                    call.respondJson(mapOf("error" to "MISSING_PATH"), HttpStatusCode.BadRequest)
                    return@put
                }
                call.respondJson(
                    workspaceFileService.writeFile(
                        path = path,
                        content = body["content"]?.toString() ?: "",
                        append = body["append"] == true
                    )
                )
            }

            post("/move") {
                if (!McpServerManager.requireWebChatAuth(call)) return@post
                val body = call.receiveJson()
                val sourcePath = body["sourcePath"]?.toString().orEmpty()
                val targetPath = body["targetPath"]?.toString().orEmpty()
                if (sourcePath.isBlank() || targetPath.isBlank()) {
                    call.respondJson(mapOf("error" to "MISSING_PATH"), HttpStatusCode.BadRequest)
                    return@post
                }
                call.respondJson(
                    workspaceFileService.move(
                        sourcePath = sourcePath,
                        targetPath = targetPath,
                        overwrite = body["overwrite"] == true
                    )
                )
            }

            delete("/file") {
                if (!McpServerManager.requireWebChatAuth(call)) return@delete
                val path = call.request.queryParameters["path"]
                if (path.isNullOrBlank()) {
                    call.respondJson(mapOf("error" to "MISSING_PATH"), HttpStatusCode.BadRequest)
                    return@delete
                }
                workspaceFileService.delete(
                    path = path,
                    recursive = call.request.queryParameters.boolean("recursive", false)
                )
                call.respondJson(mapOf("success" to true))
            }

            get("/download") {
                if (!McpServerManager.requireWebChatAuth(call)) return@get
                val path = call.request.queryParameters["path"]
                if (path.isNullOrBlank()) {
                    call.respondJson(mapOf("error" to "MISSING_PATH"), HttpStatusCode.BadRequest)
                    return@get
                }
                val (file, _) = workspaceFileService.resolveDownloadFile(path)
                call.response.header(
                    HttpHeaders.ContentDisposition,
                    io.ktor.http.ContentDisposition.Attachment.withParameter(
                        io.ktor.http.ContentDisposition.Parameters.FileName,
                        file.name
                    ).toString()
                )
                call.respondFile(file)
            }
        }
    }

    private fun Route.registerBrowserRoutes(
        browserMirrorService: BrowserMirrorService
    ) {
        route("/browser") {
            get("/snapshot") {
                if (!McpServerManager.requireWebChatAuth(call)) return@get
                call.respondJson(browserMirrorService.snapshot())
            }

            get("/frame") {
                if (!McpServerManager.requireWebChatAuth(call)) return@get
                val frame = browserMirrorService.frameBytes()
                if (frame == null) {
                    call.respondJson(mapOf("error" to "BROWSER_FRAME_UNAVAILABLE"), HttpStatusCode.NotFound)
                } else {
                    call.respondBytes(frame, ContentType.Image.PNG)
                }
            }

            post("/action") {
                if (!McpServerManager.requireWebChatAuth(call)) return@post
                val body = call.receiveJson()
                call.respondJson(browserMirrorService.executeAction(body))
            }
        }
    }

    private fun io.ktor.http.Parameters.boolean(
        key: String,
        defaultValue: Boolean
    ): Boolean {
        return when (this[key]?.trim()?.lowercase()) {
            "1", "true", "yes" -> true
            "0", "false", "no" -> false
            else -> defaultValue
        }
    }
}
