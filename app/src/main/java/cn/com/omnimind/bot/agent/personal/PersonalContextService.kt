package cn.com.omnimind.bot.agent.personal

import android.content.Context
import android.net.Uri
import cn.com.omnimind.bot.agent.*
import cn.com.omnimind.bot.mcp.McpFileInbox
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal class PersonalContextService(private val context: Context) {
    suspend fun execute(name: String, args: JsonObject, rawPayloadBytes: Int? = null): Map<String, Any?> = withContext(Dispatchers.IO) {
        try {
            currentCoroutineContext().ensureActive()
            val definition = PersonalContextTools.definitions(cn.com.omnimind.baselib.i18n.PromptLocale.EN_US)
                .firstOrNull { it["function"]?.jsonObject?.get("name")?.jsonPrimitive?.content == name }
                ?: throw IllegalArgumentException("Unknown personal context tool")
            val properties = definition.getValue("function").jsonObject.getValue("parameters").jsonObject.getValue("properties").jsonObject
            require(args.keys.all { it in properties }) { "Unsupported tool argument" }
            when {
                name.startsWith("calendar_") -> calendar(name, args, rawPayloadBytes)
                name == "context_capabilities" -> mapOf("success" to true, "sources" to PersonalContextAccess(context).all().map { it.payload() }, "permissionSettings" to "App permissions > Personal context")
                name == "contacts_query" -> PersonalContacts(context).query(args)
                name in setOf("contacts_create", "contacts_update", "contacts_delete") -> PersonalContacts(context).write(name, args)
                name == "context_notifications_query" -> notifications(args)
                name == "context_app_usage_query" -> PersonalDeviceContext(context).usage(args)
                name == "context_location_get" -> PersonalDeviceContext(context).location()
                name == "context_environment_get" -> PersonalDeviceContext(context).environment()
                name == "context_media_query" -> PersonalContextProviders(context).media(args)
                name == "context_documents_query" -> PersonalContextDocuments(context).query(args)
                name == "context_file_import" -> importFile(args)
                name == "context_sms_query" -> PersonalContextProviders(context).messages(args)
                name == "context_call_log_query" -> PersonalContextProviders(context).calls(args)
                else -> throw IllegalArgumentException("Unknown personal context tool")
            }
        } catch (error: CancellationException) { throw error }
        catch (error: PersonalContextException) { failure(error.code, error.message, error.capability) }
        catch (_: SecurityException) { failure("permission_changed", "Access is no longer available. Recheck context_capabilities and App permissions.") }
        catch (error: IllegalArgumentException) { failure("invalid_argument", error.message ?: "Invalid tool arguments.") }
        catch (_: Exception) { failure("provider_error", "The Android provider could not complete this operation. A write may have an uncertain outcome; query before deciding what to do next.") }
    }

    private fun failure(code: String, message: String, capability: String? = null) = mapOf("success" to false, "code" to code, "message" to message,
        "capabilityId" to capability, "settingsPage" to "app_permissions", "settingsRoute" to "/home/authorize_setting", "retryable" to false)

    private suspend fun calendar(name: String, args: JsonObject, rawPayloadBytes: Int? = null): Map<String, Any?> = suspendCancellableCoroutine { continuation ->
        val signal = android.os.CancellationSignal()
        continuation.invokeOnCancellation { signal.cancel() }
        Dispatchers.IO.dispatch(continuation.context, Runnable {
            try {
                signal.throwIfCanceled()
                val result = executeCalendar(name, args, signal, rawPayloadBytes)
                if (continuation.isActive) continuation.resume(result)
            } catch (error: Exception) {
                if (continuation.isActive) continuation.resumeWithException(error)
            }
        })
    }

    internal fun executeCalendar(
        name: String,
        args: JsonObject,
        signal: android.os.CancellationSignal = android.os.CancellationSignal(),
        rawPayloadBytes: Int? = null
    ): Map<String, Any?> {
        val access = PersonalContextAccess(context)
        access.requireAccess("calendar_read")
        if (name !in setOf("calendar_list", "calendar_event_list")) access.requireAccess("calendar_write")
        val service = AgentCalendarToolService(context, signal)
        fun reminders(): List<Int> = (args["reminderMinutes"] as? JsonArray)?.map { it.jsonPrimitive.int } ?: emptyList()
        return when (name) {
            "calendar_list" -> service.listCalendars(args.flag("writableOnly", true), args.flag("visibleOnly", true)).let { mapOf("success" to true, "source" to "calendar", "items" to it, "count" to it.size) }
            "calendar_event_list" -> service.listEvents(CalendarEventListRequest(args.text("calendarId"), args.text("startAt"), args.text("endAt"), args.text("query"), if (args.containsKey("limit")) resolveCalendarListLimit(args.number("limit", 0)) else null, args.number("offset", 0)))
            "calendar_event_create" -> service.createEvent(CalendarEventCreateRequest(args.text("title", true)!!, args.text("startAt", true)!!, args.text("endAt", true)!!, args.text("calendarId"), args.text("description"), args.text("location"), args.text("timezone"), args.flag("allDay"), reminders()))
            "calendar_event_update" -> service.updateEvent(CalendarEventUpdateRequest(args.text("eventId", true)!!, args.text("title"), args.text("startAt"), args.text("endAt"), args.text("description"), args.text("location"), args.text("timezone"), if (args.containsKey("allDay")) args.flag("allDay") else null, if (args.containsKey("reminderMinutes")) reminders() else null))
            "calendar_event_delete" -> service.deleteEvent(args.text("eventId", true)!!)
            "calendar_event_batch" -> {
                if (rawPayloadBytes != null) {
                    require(rawPayloadBytes <= 256 * 1024) { "日历批量请求数据过大（超过 256KiB）" }
                }

                val allowedTopLevelKeys = setOf("operations")
                val unknownTopKeys = args.keys.filter { it !in allowedTopLevelKeys }
                require(unknownTopKeys.isEmpty()) { "未知参数：${unknownTopKeys.joinToString()}" }

                val operationsArray = args["operations"] as? JsonArray
                    ?: throw IllegalArgumentException("operations 必须为数组")
                require(operationsArray.isNotEmpty()) { "operations 不能为空" }
                require(operationsArray.size <= 50) { "operations 不能超过 50 项（当前 ${operationsArray.size} 项）" }

                val allowedOperationTypes = setOf("create", "update", "delete")
                val allowedCreateFields = setOf("title", "startAt", "endAt", "calendarId", "description", "location", "timezone", "allDay", "reminderMinutes")
                val allowedUpdateFields = setOf("eventId", "title", "startAt", "endAt", "description", "location", "timezone", "allDay", "reminderMinutes")
                val allowedDeleteFields = setOf("eventId")
                val allowedItemKeys = setOf("operation", "arguments")

                val targetedEventIds = mutableSetOf<Long>()
                val mutations = operationsArray.mapIndexed { index, element ->
                    val opObj = element as? JsonObject
                        ?: throw IllegalArgumentException("operations[$index] 必须为对象")

                    val unknownItemKeys = opObj.keys.filter { it !in allowedItemKeys }
                    require(unknownItemKeys.isEmpty()) { "operations[$index] 包含未知字段：${unknownItemKeys.joinToString()}" }

                    val opType = opObj.text("operation", true)
                        ?: throw IllegalArgumentException("operations[$index].operation 不能为空")
                    require(opType in allowedOperationTypes) {
                        "operations[$index].operation 未知操作类型: $opType（仅支持 create, update, delete）"
                    }

                    val opArgs = opObj["arguments"] as? JsonObject
                        ?: throw IllegalArgumentException("operations[$index].arguments 必须为对象")

                    fun parseReminders(obj: JsonObject): List<Int>? {
                        if (!obj.containsKey("reminderMinutes")) return null
                        val element = obj["reminderMinutes"]
                        if (element == null || element is JsonNull) {
                            throw IllegalArgumentException("reminderMinutes 不能为 null，省略该字段以保留现有提醒，或传 [] 清空提醒")
                        }
                        val array = element as? JsonArray
                            ?: throw IllegalArgumentException("reminderMinutes 必须为整数数组，例如 [10, 30]")
                        return array.map {
                            val primitive = it as? JsonPrimitive
                                ?: throw IllegalArgumentException("reminderMinutes 元素必须为整数")
                            primitive.intOrNull
                                ?: throw IllegalArgumentException("reminderMinutes 元素必须为整数")
                        }
                    }

                    when (opType) {
                        "create" -> {
                            val unknownFields = opArgs.keys.filter { it !in allowedCreateFields }
                            require(unknownFields.isEmpty()) {
                                "operations[$index].arguments 包含未知字段：${unknownFields.joinToString()}"
                            }
                            CalendarBatchMutation.Create(
                                CalendarEventCreateRequest(
                                    title = opArgs.text("title", true)
                                        ?: throw IllegalArgumentException("operations[$index].arguments.title 不能为空"),
                                    startAt = opArgs.text("startAt", true)
                                        ?: throw IllegalArgumentException("operations[$index].arguments.startAt 不能为空"),
                                    endAt = opArgs.text("endAt", true)
                                        ?: throw IllegalArgumentException("operations[$index].arguments.endAt 不能为空"),
                                    calendarId = opArgs.text("calendarId"),
                                    description = opArgs.text("description"),
                                    location = opArgs.text("location"),
                                    timezone = opArgs.text("timezone"),
                                    allDay = opArgs.flag("allDay"),
                                    reminderMinutes = parseReminders(opArgs) ?: emptyList()
                                )
                            )
                        }
                        "update" -> {
                            val unknownFields = opArgs.keys.filter { it !in allowedUpdateFields }
                            require(unknownFields.isEmpty()) {
                                "operations[$index].arguments 包含未知字段：${unknownFields.joinToString()}"
                            }
                            val eventIdRaw = opArgs.text("eventId", true)
                                ?: throw IllegalArgumentException("operations[$index].arguments.eventId 不能为空")
                            val numericId = eventIdRaw.toLongOrNull()
                                ?: throw IllegalArgumentException("operations[$index].arguments.eventId 必须为数字：$eventIdRaw")
                            require(targetedEventIds.add(numericId)) {
                                "批处理中存在针对同一日程 (eventId=$eventIdRaw) 的重复操作"
                            }

                            CalendarBatchMutation.Update(
                                CalendarEventUpdateRequest(
                                    eventId = eventIdRaw,
                                    title = opArgs.text("title"),
                                    startAt = opArgs.text("startAt"),
                                    endAt = opArgs.text("endAt"),
                                    description = opArgs.text("description"),
                                    location = opArgs.text("location"),
                                    timezone = opArgs.text("timezone"),
                                    allDay = if (opArgs.containsKey("allDay")) opArgs.flag("allDay") else null,
                                    reminderMinutes = parseReminders(opArgs)
                                )
                            )
                        }
                        "delete" -> {
                            val unknownFields = opArgs.keys.filter { it !in allowedDeleteFields }
                            require(unknownFields.isEmpty()) {
                                "operations[$index].arguments 包含未知字段：${unknownFields.joinToString()}"
                            }
                            val eventIdRaw = opArgs.text("eventId", true)
                                ?: throw IllegalArgumentException("operations[$index].arguments.eventId 不能为空")
                            val numericId = eventIdRaw.toLongOrNull()
                                ?: throw IllegalArgumentException("operations[$index].arguments.eventId 必须为数字：$eventIdRaw")
                            require(targetedEventIds.add(numericId)) {
                                "批处理中存在针对同一日程 (eventId=$eventIdRaw) 的重复操作"
                            }

                            CalendarBatchMutation.Delete(
                                eventId = eventIdRaw
                            )
                        }
                        else -> throw IllegalArgumentException("未知操作类型: $opType")
                    }
                }
                service.applyBatch(mutations, rawPayloadBytes)
            }
            else -> throw IllegalArgumentException("Unknown calendar tool")
        }
    }

    private fun notifications(args: JsonObject): Map<String, Any?> {
        val access = PersonalContextAccess(context)
        access.requireAccess("notifications")
        val mode = args.text("mode") ?: "history"
        require(mode in setOf("current", "history")) { "mode must be current or history" }
        val result = if (mode == "history") {
            if (!PersonalNotificationListener.historyAvailable) throw PersonalContextException("storage_unavailable", "Notification history could not be saved.", "notifications")
            PersonalNotificationHistory.get(context).query(args)
        } else {
            val page = ContextPage.from(args)
            val range = if (args.containsKey("startAt") || args.containsKey("endAt")) ContextTimeRange.from(args, defaultDays = 7) else null
            val query = args.text("query").orEmpty()
            val packageName = args.text("packageName").orEmpty()
            val rows = PersonalNotificationListener.current().filter { (packageName.isBlank() || it.packageName == packageName) &&
                (range == null || it.postedAt in range.start until range.end) &&
                (query.isBlank() || it.title.orEmpty().contains(query, true) || it.text.orEmpty().contains(query, true)) }
                .sortedWith(compareByDescending<ContextNotification> { it.postedAt }.thenBy { it.key })
                .drop(page.offset).take(page.limit + 1).map { it.payload() }
            page.result("current_notifications", rows, mapOf("scope" to "currently_visible_notifications"))
        }
        access.requireAccess("notifications")
        return result
    }

    private suspend fun importFile(args: JsonObject): Map<String, Any?> {
        val uriText = args.text("uri")
        val path = args.text("path")
        require((uriText != null) xor (path != null)) { "Specify exactly one of uri or path" }
        val documents = PersonalContextDocuments(context)
        val uri = if (path != null) Uri.fromFile(documents.requirePublicFile(path)) else Uri.parse(uriText!!)
        fun check() {
            when {
                path != null -> require(documents.requirePublicFile(path).isFile) { "path must identify a file" }
                uri.authority == android.provider.MediaStore.AUTHORITY -> PersonalContextProviders(context).requireMedia(uri)
                else -> documents.requireReadable(uri)
            }
        }
        check()
        val coroutine = currentCoroutineContext()
        val file = McpFileInbox.storeFromUri(context, uri, sensitive = true, checkActive = { coroutine.ensureActive() })
            ?: throw PersonalContextException("file_unavailable", "The selected file could not be read.")
        try { coroutine.ensureActive(); check() } catch (error: Exception) { McpFileInbox.removeFile(file.id); throw error }
        return mapOf("success" to true, "source" to "authorized_file", "fileId" to file.id, "name" to file.fileName, "mimeType" to file.mimeType, "sizeBytes" to file.sizeBytes,
            "localPath" to file.path, "transferTool" to "file_transfer", "transferAction" to "get")
    }
}
