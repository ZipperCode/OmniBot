package cn.com.omnimind.bot.agent

import android.Manifest
import android.content.ContentProviderOperation
import android.content.ContentProviderResult
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.OperationApplicationException
import android.content.pm.PackageManager
import android.os.RemoteException
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

data class CalendarEventCreateRequest(
    val title: String,
    val startAt: String,
    val endAt: String,
    val calendarId: String?,
    val description: String?,
    val location: String?,
    val timezone: String?,
    val allDay: Boolean,
    val reminderMinutes: List<Int>
)

data class CalendarEventListRequest(
    val calendarId: String?,
    val startAt: String?,
    val endAt: String?,
    val query: String?,
    val limit: Int?,
    val offset: Int = 0
)

/** An omitted limit means that the Android calendar provider decides the result size. */
internal fun resolveCalendarListLimit(raw: Int?): Int? {
    require(raw == null || raw > 0) { "limit 必须大于 0" }
    return raw
}

data class CalendarEventUpdateRequest(
    val eventId: String,
    val title: String?,
    val startAt: String?,
    val endAt: String?,
    val description: String?,
    val location: String?,
    val timezone: String?,
    val allDay: Boolean?,
    val reminderMinutes: List<Int>?
)

class AgentCalendarToolService(
    private val context: Context,
    private val cancellation: android.os.CancellationSignal? = null
) {

    private fun query(uri: android.net.Uri, projection: Array<String>, selection: String?, arguments: Array<String>?, order: String?): android.database.Cursor {
        cancellation?.throwIfCanceled()
        return context.contentResolver.query(uri, projection, selection, arguments, order, cancellation)
            ?: throw IllegalStateException("Calendar provider is unavailable")
    }

    private data class EventSnapshot(
        val eventId: Long,
        val calendarId: Long,
        val title: String,
        val description: String,
        val location: String,
        val startMillis: Long,
        val endMillis: Long,
        val timezone: String,
        val allDay: Boolean,
        val lastSynced: Long? = null,
        val dirty: Int? = null,
        val mutators: String? = null
    )

    fun hasCalendarPermissions(write: Boolean = false): Boolean {
        val readGranted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_CALENDAR
        ) == PackageManager.PERMISSION_GRANTED
        val writeGranted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.WRITE_CALENDAR
        ) == PackageManager.PERMISSION_GRANTED
        return readGranted && (!write || writeGranted)
    }

    fun listCalendars(
        writableOnly: Boolean,
        visibleOnly: Boolean
    ): List<Map<String, Any?>> {
        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.ACCOUNT_NAME,
            CalendarContract.Calendars.ACCOUNT_TYPE,
            CalendarContract.Calendars.VISIBLE,
            CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL,
            CalendarContract.Calendars.IS_PRIMARY,
            CalendarContract.Calendars.CALENDAR_COLOR
        )

        val selectionParts = mutableListOf<String>()
        val selectionArgs = mutableListOf<String>()

        if (writableOnly) {
            selectionParts += "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ?"
            selectionArgs += CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR.toString()
        }
        if (visibleOnly) {
            selectionParts += "${CalendarContract.Calendars.VISIBLE} = 1"
        }

        val selection = selectionParts.takeIf { it.isNotEmpty() }?.joinToString(" AND ")
        val orderBy = "${CalendarContract.Calendars.IS_PRIMARY} DESC, ${CalendarContract.Calendars.CALENDAR_DISPLAY_NAME} ASC"

        val items = mutableListOf<Map<String, Any?>>()
        val cursor = query(
            CalendarContract.Calendars.CONTENT_URI,
            projection,
            selection,
            selectionArgs.toTypedArray(),
            orderBy
        )

        cursor.use {
            while (it.moveToNext()) {
                val id = it.getLong(0)
                val displayName = it.getString(1).orEmpty()
                val accountName = it.getString(2).orEmpty()
                val accountType = it.getString(3).orEmpty()
                val visible = it.getInt(4) == 1
                val accessLevel = it.getInt(5)
                val primary = it.getInt(6) == 1
                val color = it.getInt(7)

                items += mapOf(
                    "calendarId" to id.toString(),
                    "displayName" to displayName,
                    "accountName" to accountName,
                    "accountType" to accountType,
                    "visible" to visible,
                    "accessLevel" to accessLevel,
                    "isPrimary" to primary,
                    "color" to color,
                    "isWritable" to (accessLevel >= CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR)
                )
            }
        }

        return items
    }

    fun applyBatch(
        mutations: List<CalendarBatchMutation>,
        rawPayloadBytes: Int? = null
    ): Map<String, Any?> {
        val internalResult = executeBatchInternal(mutations, rawPayloadBytes)
        @Suppress("UNCHECKED_CAST")
        val items = internalResult["items"] as List<Map<String, Any?>>
        val verified = internalResult["verified"] as Boolean
        val result = mutableMapOf<String, Any?>(
            "success" to true,
            "items" to items,
            "verified" to verified
        )
        if (!verified) {
            result["warning"] = "提交已受理，但状态未确认"
        }
        return result
    }

    fun createEvent(request: CalendarEventCreateRequest): Map<String, Any?> {
        val internalResult = executeBatchInternal(listOf(CalendarBatchMutation.Create(request)))
        @Suppress("UNCHECKED_CAST")
        val items = internalResult["items"] as List<Map<String, Any?>>
        val item = items[0]
        val eventId = item["eventId"] as String
        val verified = item["verified"] as Boolean
        @Suppress("UNCHECKED_CAST")
        val prepared = (internalResult["_prepared"] as List<PreparedMutation>)[0]

        val summary = if (verified) {
            "已创建日程“${prepared.title}”"
        } else {
            "已提交创建日程“${prepared.title}”，但状态未确认"
        }

        val result = mutableMapOf<String, Any?>(
            "success" to true,
            "eventId" to eventId,
            "calendarId" to prepared.calendarId.toString(),
            "title" to prepared.title,
            "startAt" to prepared.normalizedStart.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
            "endAt" to prepared.normalizedEnd.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
            "timezone" to prepared.timezone,
            "allDay" to prepared.allDay,
            "reminderMinutes" to (prepared.reminderMinutes ?: emptyList()),
            "verified" to verified,
            "summary" to summary
        )
        if (!verified) {
            result["warning"] = "提交已受理，但状态未确认"
        }
        return result
    }

    fun listEvents(request: CalendarEventListRequest): Map<String, Any?> {
        val limit = resolveCalendarListLimit(request.limit)
        require(request.offset >= 0) { "offset must not be negative" }
        val defaultZone = ZoneId.systemDefault()
        val now = ZonedDateTime.now(defaultZone)

        val rangeStart = request.startAt
            ?.takeIf { it.isNotBlank() }
            ?.let { parseDateTime(it, defaultZone) }
            ?: now.minusDays(30)
        val rangeEnd = request.endAt
            ?.takeIf { it.isNotBlank() }
            ?.let { parseDateTime(it, defaultZone) }
            ?: now.plusDays(180)

        require(rangeEnd.toInstant().toEpochMilli() > rangeStart.toInstant().toEpochMilli()) {
            "查询结束时间必须晚于开始时间"
        }

        val builder = CalendarContract.Instances.CONTENT_URI.buildUpon()
        ContentUris.appendId(builder, rangeStart.toInstant().toEpochMilli())
        ContentUris.appendId(builder, rangeEnd.toInstant().toEpochMilli())

        val projection = arrayOf(
            CalendarContract.Instances.EVENT_ID,
            CalendarContract.Instances.CALENDAR_ID,
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.EVENT_LOCATION,
            CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.EVENT_TIMEZONE
        )

        val selectionParts = mutableListOf<String>()
        val selectionArgs = mutableListOf<String>()

        request.calendarId?.trim()?.takeIf { it.isNotEmpty() }?.let {
            val calendarId = it.toLongOrNull()
                ?: throw IllegalArgumentException("calendarId 必须为数字")
            selectionParts += "${CalendarContract.Instances.CALENDAR_ID} = ?"
            selectionArgs += calendarId.toString()
        }

        request.query?.trim()?.takeIf { it.isNotEmpty() }?.let { query ->
            selectionParts += "(${CalendarContract.Instances.TITLE} LIKE ? OR ${CalendarContract.Instances.EVENT_LOCATION} LIKE ?)"
            val pattern = "%$query%"
            selectionArgs += pattern
            selectionArgs += pattern
        }

        val selection = selectionParts.takeIf { it.isNotEmpty() }?.joinToString(" AND ")
        val orderBy = "${CalendarContract.Instances.BEGIN} ASC, ${CalendarContract.Instances.EVENT_ID} ASC"

        val items = mutableListOf<Map<String, Any?>>()
        val cursor = query(
            builder.build(),
            projection,
            selection,
            selectionArgs.toTypedArray(),
            orderBy
        )

        cursor.use {
            var skipped = 0
            while (it.moveToNext() && (limit == null || items.size <= limit)) {
                cancellation?.throwIfCanceled()
                if (skipped++ < request.offset) continue
                val eventId = it.getLong(0)
                val calendarId = it.getLong(1)
                val title = it.getString(2).orEmpty()
                val beginMillis = it.getLong(3)
                val endMillis = it.getLong(4)
                val location = it.getString(5).orEmpty()
                val allDay = it.getInt(6) == 1
                val timezone = it.getString(7).orEmpty().ifBlank { defaultZone.id }
                val zone = runCatching { ZoneId.of(timezone) }.getOrDefault(defaultZone)

                items += mapOf(
                    "eventId" to eventId.toString(),
                    "calendarId" to calendarId.toString(),
                    "title" to title,
                    "startAt" to Instant.ofEpochMilli(beginMillis).atZone(zone)
                        .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
                    "endAt" to Instant.ofEpochMilli(endMillis).atZone(zone)
                        .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
                    "location" to location,
                    "timezone" to zone.id,
                    "allDay" to allDay
                )
            }
        }

        return mapOf(
            "success" to true,
            "count" to if (limit == null) items.size else minOf(items.size, limit),
            "limit" to limit,
            "offset" to request.offset,
            "hasMore" to (limit != null && items.size > limit),
            "nextOffset" to if (limit != null && items.size > limit) request.offset + limit else null,
            "rangeStart" to rangeStart.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
            "rangeEnd" to rangeEnd.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
            "items" to (limit?.let(items::take) ?: items)
        )
    }

    fun updateEvent(request: CalendarEventUpdateRequest): Map<String, Any?> {
        val internalResult = executeBatchInternal(listOf(CalendarBatchMutation.Update(request)))
        @Suppress("UNCHECKED_CAST")
        val items = internalResult["items"] as List<Map<String, Any?>>
        val item = items[0]
        val eventId = item["eventId"] as String
        val verified = item["verified"] as Boolean
        @Suppress("UNCHECKED_CAST")
        val prepared = (internalResult["_prepared"] as List<PreparedMutation>)[0]

        val summary = if (verified) {
            "已更新日程“${prepared.title}”"
        } else {
            "已提交更新日程“${prepared.title}”，但状态未确认"
        }

        val result = mutableMapOf<String, Any?>(
            "success" to true,
            "eventId" to eventId,
            "title" to prepared.title,
            "startAt" to prepared.normalizedStart.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
            "endAt" to prepared.normalizedEnd.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
            "timezone" to prepared.timezone,
            "allDay" to prepared.allDay,
            "reminderMinutes" to (prepared.reminderMinutes ?: loadReminderMinutes(prepared.targetEventId!!)),
            "verified" to verified,
            "summary" to summary
        )
        if (!verified) {
            result["warning"] = "提交已受理，但状态未确认"
        }
        return result
    }

    fun deleteEvent(eventIdRaw: String): Map<String, Any?> {
        val internalResult = executeBatchInternal(listOf(CalendarBatchMutation.Delete(eventIdRaw)))
        @Suppress("UNCHECKED_CAST")
        val items = internalResult["items"] as List<Map<String, Any?>>
        val item = items[0]
        val eventId = item["eventId"] as String
        val verified = item["verified"] as Boolean
        @Suppress("UNCHECKED_CAST")
        val prepared = (internalResult["_prepared"] as List<PreparedMutation>)[0]

        val summary = if (verified) {
            "已删除日程“${prepared.title}”"
        } else {
            "已提交删除日程“${prepared.title}”，但状态未确认"
        }

        val result = mutableMapOf<String, Any?>(
            "success" to true,
            "eventId" to eventId,
            "verified" to verified,
            "summary" to summary
        )
        if (!verified) {
            result["warning"] = "提交已受理，但状态未确认"
        }
        return result
    }

    private data class PreparedMutation(
        val index: Int,
        val mutation: CalendarBatchMutation,
        val eventOpIndex: Int,
        val targetEventId: Long?,
        val calendarId: Long,
        val title: String,
        val description: String,
        val location: String,
        val startMillis: Long,
        val endMillis: Long,
        val timezone: String,
        val allDay: Boolean,
        val normalizedStart: ZonedDateTime,
        val normalizedEnd: ZonedDateTime,
        val reminderMinutes: List<Int>?,
        val currentSnapshot: EventSnapshot?
    )

    private fun executeBatchInternal(
        mutations: List<CalendarBatchMutation>,
        rawPayloadBytes: Int? = null
    ): Map<String, Any?> {
        require(mutations.isNotEmpty()) { "operations 不能为空" }
        require(mutations.size <= 50) { "operations 不能超过 50 项（当前 ${mutations.size} 项）" }

        if (rawPayloadBytes != null) {
            require(rawPayloadBytes <= 256 * 1024) { "日历批量请求数据过大（超过 256KiB）" }
        }

        val targetedEventIds = mutableSetOf<Long>()
        for (mutation in mutations) {
            val rawId = when (mutation) {
                is CalendarBatchMutation.Create -> null
                is CalendarBatchMutation.Update -> mutation.request.eventId.trim()
                is CalendarBatchMutation.Delete -> mutation.eventId.trim()
            }
            if (rawId != null) {
                require(rawId.isNotEmpty()) { "eventId 不能为空" }
                val numericId = rawId.toLongOrNull()
                    ?: throw IllegalArgumentException("eventId 必须为数字：$rawId")
                require(targetedEventIds.add(numericId)) { "批处理中存在针对同一日程 (eventId=$rawId) 的重复操作" }
            }
        }

        val operations = ArrayList<ContentProviderOperation>()
        val preparedList = mutableListOf<PreparedMutation>()

        for ((index, mutation) in mutations.withIndex()) {
            cancellation?.throwIfCanceled()
            when (mutation) {
                is CalendarBatchMutation.Create -> {
                    val req = mutation.request
                    require(req.title.isNotBlank()) { "title 不能为空" }
                    val calendarId = resolveWritableCalendarId(req.calendarId)
                    val zone = resolveZone(req.timezone)
                    val start = parseDateTime(req.startAt, zone)
                    val end = parseDateTime(req.endAt, zone)
                    val (normalizedStart, normalizedEnd) = normalizeEventRange(start, end, req.allDay)
                    val normalizedReminders = req.reminderMinutes
                        .map { it.coerceAtLeast(0) }
                        .distinct()
                        .sorted()

                    val eventOpIndex = operations.size
                    val values = ContentValues().apply {
                        put(CalendarContract.Events.CALENDAR_ID, calendarId)
                        put(CalendarContract.Events.TITLE, req.title.trim())
                        put(CalendarContract.Events.DTSTART, normalizedStart.toInstant().toEpochMilli())
                        put(CalendarContract.Events.DTEND, normalizedEnd.toInstant().toEpochMilli())
                        put(CalendarContract.Events.EVENT_TIMEZONE, normalizedStart.zone.id)
                        put(CalendarContract.Events.ALL_DAY, if (req.allDay) 1 else 0)
                        if (!req.description.isNullOrBlank()) {
                            put(CalendarContract.Events.DESCRIPTION, req.description.trim())
                        }
                        if (!req.location.isNullOrBlank()) {
                            put(CalendarContract.Events.EVENT_LOCATION, req.location.trim())
                        }
                        put(CalendarContract.Events.HAS_ALARM, if (normalizedReminders.isNotEmpty()) 1 else 0)
                    }

                    operations.add(
                        ContentProviderOperation.newInsert(CalendarContract.Events.CONTENT_URI)
                            .withValues(values)
                            .build()
                    )

                    for (minutes in normalizedReminders) {
                        operations.add(
                            ContentProviderOperation.newInsert(CalendarContract.Reminders.CONTENT_URI)
                                .withValueBackReference(CalendarContract.Reminders.EVENT_ID, eventOpIndex)
                                .withValue(CalendarContract.Reminders.MINUTES, minutes)
                                .withValue(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT)
                                .build()
                        )
                    }

                    preparedList.add(
                        PreparedMutation(
                            index = index,
                            mutation = mutation,
                            eventOpIndex = eventOpIndex,
                            targetEventId = null,
                            calendarId = calendarId,
                            title = req.title.trim(),
                            description = req.description?.trim().orEmpty(),
                            location = req.location?.trim().orEmpty(),
                            startMillis = normalizedStart.toInstant().toEpochMilli(),
                            endMillis = normalizedEnd.toInstant().toEpochMilli(),
                            timezone = normalizedStart.zone.id,
                            allDay = req.allDay,
                            normalizedStart = normalizedStart,
                            normalizedEnd = normalizedEnd,
                            reminderMinutes = normalizedReminders,
                            currentSnapshot = null
                        )
                    )
                }
                is CalendarBatchMutation.Update -> {
                    val req = mutation.request
                    val eventId = req.eventId.trim().toLongOrNull()
                        ?: throw IllegalArgumentException("eventId 必须为数字")
                    val current = loadEventSnapshot(eventId)
                        ?: throw IllegalArgumentException("未找到对应日程：eventId=$eventId")
                    if (!isWritableCalendar(current.calendarId)) {
                        throw IllegalArgumentException("指定日程所在日历不可写：calendarId=${current.calendarId}")
                    }

                    val zone = resolveZone(req.timezone ?: current.timezone)
                    val allDay = req.allDay ?: current.allDay
                    val currentStart = Instant.ofEpochMilli(current.startMillis).atZone(zone)
                    val currentEnd = Instant.ofEpochMilli(current.endMillis).atZone(zone)

                    val nextStart = req.startAt?.takeIf { it.isNotBlank() }?.let { parseDateTime(it, zone) }
                        ?: currentStart
                    val nextEnd = req.endAt?.takeIf { it.isNotBlank() }?.let { parseDateTime(it, zone) }
                        ?: currentEnd
                    val (normalizedStart, normalizedEnd) = normalizeEventRange(nextStart, nextEnd, allDay)

                    val normalizedReminders = req.reminderMinutes?.map { it.coerceAtLeast(0) }?.distinct()?.sorted()

                    val nextTitle = req.title?.trim()?.ifBlank { current.title } ?: current.title
                    val nextDescription = req.description?.trim() ?: current.description
                    val nextLocation = req.location?.trim() ?: current.location

                    val values = ContentValues().apply {
                        put(CalendarContract.Events.TITLE, nextTitle)
                        put(CalendarContract.Events.DESCRIPTION, nextDescription)
                        put(CalendarContract.Events.EVENT_LOCATION, nextLocation)
                        put(CalendarContract.Events.DTSTART, normalizedStart.toInstant().toEpochMilli())
                        put(CalendarContract.Events.DTEND, normalizedEnd.toInstant().toEpochMilli())
                        put(CalendarContract.Events.EVENT_TIMEZONE, normalizedStart.zone.id)
                        put(CalendarContract.Events.ALL_DAY, if (allDay) 1 else 0)
                        if (normalizedReminders != null) {
                            put(CalendarContract.Events.HAS_ALARM, if (normalizedReminders.isNotEmpty()) 1 else 0)
                        }
                    }

                    val eventOpIndex = operations.size
                    val (selection, selectionArgs) = buildConcurrencySelection(current)

                    operations.add(
                        ContentProviderOperation.newUpdate(CalendarContract.Events.CONTENT_URI)
                            .withSelection(selection, selectionArgs)
                            .withValues(values)
                            .withExpectedCount(1)
                            .build()
                    )

                    if (normalizedReminders != null) {
                        operations.add(
                            ContentProviderOperation.newDelete(CalendarContract.Reminders.CONTENT_URI)
                                .withSelection("${CalendarContract.Reminders.EVENT_ID} = ?", arrayOf(eventId.toString()))
                                .build()
                        )
                        for (minutes in normalizedReminders) {
                            operations.add(
                                ContentProviderOperation.newInsert(CalendarContract.Reminders.CONTENT_URI)
                                    .withValue(CalendarContract.Reminders.EVENT_ID, eventId)
                                    .withValue(CalendarContract.Reminders.MINUTES, minutes)
                                    .withValue(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT)
                                    .build()
                            )
                        }
                    }

                    preparedList.add(
                        PreparedMutation(
                            index = index,
                            mutation = mutation,
                            eventOpIndex = eventOpIndex,
                            targetEventId = eventId,
                            calendarId = current.calendarId,
                            title = nextTitle,
                            description = nextDescription,
                            location = nextLocation,
                            startMillis = normalizedStart.toInstant().toEpochMilli(),
                            endMillis = normalizedEnd.toInstant().toEpochMilli(),
                            timezone = normalizedStart.zone.id,
                            allDay = allDay,
                            normalizedStart = normalizedStart,
                            normalizedEnd = normalizedEnd,
                            reminderMinutes = normalizedReminders,
                            currentSnapshot = current
                        )
                    )
                }
                is CalendarBatchMutation.Delete -> {
                    val eventId = mutation.eventId.trim().toLongOrNull()
                        ?: throw IllegalArgumentException("eventId 必须为数字")
                    val current = loadEventSnapshot(eventId)
                        ?: throw IllegalArgumentException("未找到对应日程：eventId=$eventId")
                    if (!isWritableCalendar(current.calendarId)) {
                        throw IllegalArgumentException("指定日程所在日历不可写：calendarId=${current.calendarId}")
                    }

                    val eventOpIndex = operations.size
                    val (selection, selectionArgs) = buildConcurrencySelection(current)

                    operations.add(
                        ContentProviderOperation.newDelete(CalendarContract.Events.CONTENT_URI)
                            .withSelection(selection, selectionArgs)
                            .withExpectedCount(1)
                            .build()
                    )

                    preparedList.add(
                        PreparedMutation(
                            index = index,
                            mutation = mutation,
                            eventOpIndex = eventOpIndex,
                            targetEventId = eventId,
                            calendarId = current.calendarId,
                            title = current.title,
                            description = current.description,
                            location = current.location,
                            startMillis = current.startMillis,
                            endMillis = current.endMillis,
                            timezone = current.timezone,
                            allDay = current.allDay,
                            normalizedStart = Instant.ofEpochMilli(current.startMillis).atZone(ZoneId.of("UTC")),
                            normalizedEnd = Instant.ofEpochMilli(current.endMillis).atZone(ZoneId.of("UTC")),
                            reminderMinutes = null,
                            currentSnapshot = current
                        )
                    )
                }
            }
        }

        cancellation?.throwIfCanceled()
        val results: Array<ContentProviderResult> = try {
            context.contentResolver.applyBatch(CalendarContract.AUTHORITY, operations)
        } catch (error: OperationApplicationException) {
            throw IllegalStateException("日历批量操作未通过约束检查：${error.message}", error)
        } catch (error: RemoteException) {
            throw IllegalStateException("日历服务通信失败：${error.message}", error)
        }

        val items = mutableListOf<Map<String, Any?>>()
        for (prepared in preparedList) {
            val eventIdStr = when (prepared.mutation) {
                is CalendarBatchMutation.Create -> {
                    val opResult = results.getOrNull(prepared.eventOpIndex)
                        ?: throw IllegalStateException("未能获取创建结果")
                    opResult.uri?.lastPathSegment
                        ?: throw IllegalStateException("创建日程失败：无法解析 eventId")
                }
                is CalendarBatchMutation.Update -> prepared.targetEventId.toString()
                is CalendarBatchMutation.Delete -> prepared.targetEventId.toString()
            }

            var verified = false
            try {
                if (cancellation?.isCanceled == true) {
                    verified = false
                } else {
                    val eventIdLong = eventIdStr.toLongOrNull()
                    if (eventIdLong != null) {
                        when (prepared.mutation) {
                            is CalendarBatchMutation.Create -> {
                                val snapshot = loadEventSnapshot(eventIdLong)
                                if (snapshot != null &&
                                    snapshot.title == prepared.title &&
                                    snapshot.description == prepared.description &&
                                    snapshot.location == prepared.location &&
                                    snapshot.startMillis == prepared.startMillis &&
                                    snapshot.endMillis == prepared.endMillis &&
                                    snapshot.timezone == prepared.timezone &&
                                    snapshot.allDay == prepared.allDay
                                ) {
                                    if (prepared.reminderMinutes.isNullOrEmpty()) {
                                        verified = true
                                    } else {
                                        val readReminders = loadReminderMinutes(eventIdLong)
                                        verified = (readReminders == prepared.reminderMinutes)
                                    }
                                }
                            }
                            is CalendarBatchMutation.Update -> {
                                val snapshot = loadEventSnapshot(eventIdLong)
                                if (snapshot != null &&
                                    snapshot.title == prepared.title &&
                                    snapshot.description == prepared.description &&
                                    snapshot.location == prepared.location &&
                                    snapshot.startMillis == prepared.startMillis &&
                                    snapshot.endMillis == prepared.endMillis &&
                                    snapshot.timezone == prepared.timezone &&
                                    snapshot.allDay == prepared.allDay
                                ) {
                                    if (prepared.reminderMinutes == null) {
                                        verified = true
                                    } else {
                                        val readReminders = loadReminderMinutes(eventIdLong)
                                        verified = (readReminders == prepared.reminderMinutes)
                                    }
                                }
                            }
                            is CalendarBatchMutation.Delete -> {
                                val snapshot = loadEventSnapshot(eventIdLong)
                                verified = (snapshot == null)
                            }
                        }
                    }
                }
            } catch (_: Exception) {
                verified = false
            }

            items.add(
                mapOf(
                    "index" to prepared.index,
                    "operation" to prepared.mutation.operation,
                    "eventId" to eventIdStr,
                    "verified" to verified
                )
            )
        }

        val allVerified = items.isNotEmpty() && items.all { it["verified"] == true }
        return mapOf(
            "success" to true,
            "items" to items,
            "verified" to allVerified,
            "_prepared" to preparedList
        )
    }

    private fun buildConcurrencySelection(snapshot: EventSnapshot): Pair<String, Array<String>> {
        val parts = mutableListOf<String>()
        val args = mutableListOf<String>()

        parts += "${CalendarContract.Events._ID} = ?"
        args += snapshot.eventId.toString()

        parts += "${CalendarContract.Events.CALENDAR_ID} = ?"
        args += snapshot.calendarId.toString()
        parts += "${CalendarContract.Events.TITLE} = ?"
        args += snapshot.title

        if (snapshot.description.isEmpty()) {
            parts += "(${CalendarContract.Events.DESCRIPTION} IS NULL OR ${CalendarContract.Events.DESCRIPTION} = '')"
        } else {
            parts += "${CalendarContract.Events.DESCRIPTION} = ?"
            args += snapshot.description
        }

        if (snapshot.location.isEmpty()) {
            parts += "(${CalendarContract.Events.EVENT_LOCATION} IS NULL OR ${CalendarContract.Events.EVENT_LOCATION} = '')"
        } else {
            parts += "${CalendarContract.Events.EVENT_LOCATION} = ?"
            args += snapshot.location
        }

        parts += "${CalendarContract.Events.DTSTART} = ?"
        args += snapshot.startMillis.toString()

        parts += "${CalendarContract.Events.DTEND} = ?"
        args += snapshot.endMillis.toString()

        parts += "${CalendarContract.Events.EVENT_TIMEZONE} = ?"
        args += snapshot.timezone

        parts += "${CalendarContract.Events.ALL_DAY} = ?"
        args += if (snapshot.allDay) "1" else "0"

        if (snapshot.lastSynced != null) {
            parts += "${CalendarContract.Events.LAST_SYNCED} = ?"
            args += snapshot.lastSynced.toString()
        }
        if (snapshot.dirty != null) {
            parts += "${CalendarContract.Events.DIRTY} = ?"
            args += snapshot.dirty.toString()
        }
        if (snapshot.mutators != null) {
            parts += "${CalendarContract.Events.MUTATORS} = ?"
            args += snapshot.mutators
        }

        return parts.joinToString(" AND ") to args.toTypedArray()
    }


    private fun loadEventSnapshot(eventId: Long): EventSnapshot? {
        val baseProjection = arrayOf(
            CalendarContract.Events._ID,
            CalendarContract.Events.CALENDAR_ID,
            CalendarContract.Events.TITLE,
            CalendarContract.Events.DESCRIPTION,
            CalendarContract.Events.EVENT_LOCATION,
            CalendarContract.Events.DTSTART,
            CalendarContract.Events.DTEND,
            CalendarContract.Events.EVENT_TIMEZONE,
            CalendarContract.Events.ALL_DAY
        )
        val fullProjection = arrayOf(
            CalendarContract.Events._ID,
            CalendarContract.Events.CALENDAR_ID,
            CalendarContract.Events.TITLE,
            CalendarContract.Events.DESCRIPTION,
            CalendarContract.Events.EVENT_LOCATION,
            CalendarContract.Events.DTSTART,
            CalendarContract.Events.DTEND,
            CalendarContract.Events.EVENT_TIMEZONE,
            CalendarContract.Events.ALL_DAY,
            CalendarContract.Events.LAST_SYNCED,
            CalendarContract.Events.DIRTY,
            CalendarContract.Events.MUTATORS
        )

        val cursor = runCatching {
            query(
                ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId),
                fullProjection,
                null,
                null,
                null
            )
        }.getOrElse {
            query(
                ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId),
                baseProjection,
                null,
                null,
                null
            )
        }

        cursor.use {
            if (it.moveToFirst()) {
                val lastSyncedIdx = it.getColumnIndex(CalendarContract.Events.LAST_SYNCED)
                val dirtyIdx = it.getColumnIndex(CalendarContract.Events.DIRTY)
                val mutatorsIdx = it.getColumnIndex(CalendarContract.Events.MUTATORS)

                return EventSnapshot(
                    eventId = it.getLong(0),
                    calendarId = it.getLong(1),
                    title = it.getString(2).orEmpty(),
                    description = it.getString(3).orEmpty(),
                    location = it.getString(4).orEmpty(),
                    startMillis = it.getLong(5),
                    endMillis = it.getLong(6),
                    timezone = it.getString(7).orEmpty().ifBlank { ZoneId.systemDefault().id },
                    allDay = it.getInt(8) == 1,
                    lastSynced = if (lastSyncedIdx >= 0 && !it.isNull(lastSyncedIdx)) it.getLong(lastSyncedIdx) else null,
                    dirty = if (dirtyIdx >= 0 && !it.isNull(dirtyIdx)) it.getInt(dirtyIdx) else null,
                    mutators = if (mutatorsIdx >= 0 && !it.isNull(mutatorsIdx)) it.getString(mutatorsIdx) else null
                )
            }
        }
        return null
    }

    private fun resolveWritableCalendarId(rawCalendarId: String?): Long {
        val fromArg = rawCalendarId?.trim()?.takeIf { it.isNotEmpty() }
        if (fromArg != null) {
            val id = fromArg.toLongOrNull() ?: throw IllegalArgumentException("calendarId 必须为数字")
            if (!isWritableCalendar(id)) {
                throw IllegalArgumentException("指定 calendarId 不可写")
            }
            return id
        }

        val calendars = listCalendars(writableOnly = true, visibleOnly = true)
        val preferred = calendars.firstOrNull { it["isPrimary"] == true } ?: calendars.firstOrNull()
            ?: throw IllegalStateException("未找到可写日历")
        return preferred["calendarId"]?.toString()?.toLongOrNull()
            ?: throw IllegalStateException("未找到可写日历")
    }

    private fun isWritableCalendar(calendarId: Long): Boolean {
        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL,
            CalendarContract.Calendars.VISIBLE
        )

        val selection = "${CalendarContract.Calendars._ID} = ?"
        val args = arrayOf(calendarId.toString())
        val cursor = query(
            CalendarContract.Calendars.CONTENT_URI,
            projection,
            selection,
            args,
            null
        )

        cursor.use {
            if (it.moveToFirst()) {
                val accessLevel = it.getInt(1)
                val visible = it.getInt(2) == 1
                return accessLevel >= CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR && visible
            }
        }
        return false
    }


    private fun loadReminderMinutes(eventId: Long): List<Int> {
        val projection = arrayOf(
            CalendarContract.Reminders.MINUTES
        )
        val cursor = query(
            CalendarContract.Reminders.CONTENT_URI,
            projection,
            "${CalendarContract.Reminders.EVENT_ID} = ?",
            arrayOf(eventId.toString()),
            "${CalendarContract.Reminders.MINUTES} ASC"
        )

        val result = mutableListOf<Int>()
        cursor.use {
            while (it.moveToNext()) {
                result += it.getInt(0)
            }
        }
        return result
    }

    private fun normalizeEventRange(
        start: ZonedDateTime,
        end: ZonedDateTime,
        allDay: Boolean
    ): Pair<ZonedDateTime, ZonedDateTime> {
        val normalizedStart: ZonedDateTime
        val normalizedEnd: ZonedDateTime

        if (allDay) {
            // Calendar Provider 的全天日期使用 UTC 午夜，结束日期不包含在事件内。
            val firstDate = start.toLocalDate()
            val endDate = end.toLocalDate()
            normalizedStart = firstDate.atStartOfDay(ZoneId.of("UTC"))
            normalizedEnd = (if (endDate == firstDate) firstDate.plusDays(1) else endDate)
                .atStartOfDay(ZoneId.of("UTC"))
        } else {
            normalizedStart = start
            normalizedEnd = end
        }

        require(normalizedEnd.toInstant().toEpochMilli() > normalizedStart.toInstant().toEpochMilli()) {
            "结束时间必须晚于开始时间"
        }

        return normalizedStart to normalizedEnd
    }

    private fun parseDateTime(value: String, zone: ZoneId): ZonedDateTime {
        val normalized = value.trim()
        require(normalized.isNotEmpty()) { "时间不能为空" }

        runCatching { return ZonedDateTime.parse(normalized) }
        runCatching { return OffsetDateTime.parse(normalized).toZonedDateTime().withZoneSameInstant(zone) }
        runCatching { return Instant.parse(normalized).atZone(zone) }
        runCatching { return LocalDateTime.parse(normalized).atZone(zone) }

        throw IllegalArgumentException("无法解析时间：$normalized")
    }

    private fun resolveZone(rawZone: String?): ZoneId {
        val zone = rawZone?.trim().orEmpty()
        if (zone.isEmpty()) return ZoneId.systemDefault()
        return runCatching { ZoneId.of(zone) }.getOrElse {
            throw IllegalArgumentException("Invalid timezone: $zone")
        }
    }
}
