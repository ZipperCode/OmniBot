package cn.com.omnimind.bot.agent

import android.Manifest
import android.app.Application
import android.content.*
import android.content.pm.PackageManager
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.CalendarContract
import cn.com.omnimind.bot.agent.personal.PersonalContextException
import cn.com.omnimind.bot.agent.personal.PersonalContextService
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import java.time.Instant
import kotlinx.coroutines.runBlocking
import cn.com.omnimind.baselib.llm.AssistantToolCall
import cn.com.omnimind.baselib.llm.AssistantToolCallFunction
import cn.com.omnimind.bot.agent.personal.PersonalContextCapabilityModule

/**
 * 日历批量操作与真实事务行为测试。
 *
 * 验证事务原子回滚、并发约束、边界、UTC 标准化、提交后读回失败无二次重提、
 * 以及 PersonalContextService 对 batch 输入的严格预检查。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, manifest = Config.NONE)
class AgentCalendarBatchTest {

    private lateinit var context: TestContext
    private lateinit var provider: TransactionalCalendarProvider

    private fun json(text: String) = Json.parseToJsonElement(text).jsonObject

    @Before
    fun setup() {
        context = TestContext(RuntimeEnvironment.getApplication())
        provider = TransactionalCalendarProvider()
        ShadowContentResolver.registerProviderInternal(CalendarContract.AUTHORITY, provider)
    }

    @Test
    fun `事件提醒失败全回滚`() {
        provider.failReminders = true
        val service = AgentCalendarToolService(context)

        val request = CalendarEventCreateRequest(
            title = "会议与提醒",
            startAt = "2026-11-08T10:00:00+08:00",
            endAt = "2026-11-08T11:00:00+08:00",
            calendarId = "1",
            description = "测试回滚",
            location = "会议室",
            timezone = "Asia/Shanghai",
            allDay = false,
            reminderMinutes = listOf(15)
        )

        try {
            service.createEvent(request)
            fail("提醒插入失败时应抛出异常")
        } catch (e: Exception) {
            assertTrue(e is IllegalStateException)
        }

        // 验证原子事务已回滚：数据库内没有任何日程与提醒残留
        assertEquals(0, provider.events.size)
        assertEquals(0, provider.reminders.size)
    }

    @Test
    fun `批处理中间冲突全回滚`() {
        val service = AgentCalendarToolService(context)

        // 先预置一个待冲突的已有日程（DIRTY = 0）
        val existingValues = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, 1L)
            put(CalendarContract.Events.TITLE, "原日程")
            put(CalendarContract.Events.DTSTART, 1794200000000L)
            put(CalendarContract.Events.DTEND, 1794203600000L)
            put(CalendarContract.Events.EVENT_TIMEZONE, "UTC")
            put(CalendarContract.Events.ALL_DAY, 0)
            put(CalendarContract.Events.DIRTY, 0)
        }
        val existingUri = provider.insert(CalendarContract.Events.CONTENT_URI, existingValues)
        val existingId = ContentUris.parseId(existingUri).toString()

        // 构造三项批量操作：
        // 0: 创建 "Event A"
        // 1: 更新 existingId（模拟并发冲突：快照读后事件标题被并发篡改，但 DIRTY 保持 0 不变）
        // 2: 创建 "Event B"
        provider.onBeforeUpdate = {
            provider.events[existingId.toLong()]?.put(CalendarContract.Events.TITLE, "并发篡改标题")
        }
        val batch = listOf(
            CalendarBatchMutation.Create(
                CalendarEventCreateRequest(
                    title = "Event A",
                    startAt = "2026-11-08T09:00:00Z",
                    endAt = "2026-11-08T10:00:00Z",
                    calendarId = "1",
                    description = null,
                    location = null,
                    timezone = "UTC",
                    allDay = false,
                    reminderMinutes = emptyList()
                )
            ),
            CalendarBatchMutation.Update(
                CalendarEventUpdateRequest(
                    eventId = existingId,
                    title = "冲突更新",
                    startAt = null,
                    endAt = null,
                    description = null,
                    location = null,
                    timezone = null,
                    allDay = null,
                    reminderMinutes = null
                )
            ),
            CalendarBatchMutation.Create(
                CalendarEventCreateRequest(
                    title = "Event B",
                    startAt = "2026-11-08T11:00:00Z",
                    endAt = "2026-11-08T12:00:00Z",
                    calendarId = "1",
                    description = null,
                    location = null,
                    timezone = "UTC",
                    allDay = false,
                    reminderMinutes = emptyList()
                )
            )
        )

        try {
            service.applyBatch(batch)
            fail("预期更新冲突导致整批回滚")
        } catch (e: Exception) {
            assertTrue(e is IllegalStateException)
        }

        // 验证全回滚：Event A 与 Event B 均未写入，数据库只有原日程
        assertEquals(1, provider.events.size)
        assertNotNull(provider.events[existingId.toLong()])
        assertEquals("原日程", provider.events[existingId.toLong()]?.getAsString(CalendarContract.Events.TITLE))
    }

    @Test
    fun `批处理50项允许与51项边界拒绝`() {
        val service = AgentCalendarToolService(context)

        // 1. 50 项批量操作，允许执行
        val fiftyOps = (1..50).map { i ->
            CalendarBatchMutation.Create(
                CalendarEventCreateRequest(
                    title = "Batch $i",
                    startAt = "2026-11-08T10:00:00Z",
                    endAt = "2026-11-08T11:00:00Z",
                    calendarId = "1",
                    description = null,
                    location = null,
                    timezone = "UTC",
                    allDay = false,
                    reminderMinutes = emptyList()
                )
            )
        }
        val result50 = service.applyBatch(fiftyOps)
        assertEquals(true, result50["success"])
        assertEquals(true, result50["verified"])
        @Suppress("UNCHECKED_CAST")
        val items50 = result50["items"] as List<Map<String, Any?>>
        assertEquals(50, items50.size)
        assertEquals(50, provider.events.size)

        // 2. 51 项批量操作，提交前明确拒绝，不发生数据库调用
        val previousBatchCount = provider.applyBatchCount
        val fiftyOneOps = (1..51).map { i ->
            CalendarBatchMutation.Create(
                CalendarEventCreateRequest(
                    title = "Batch Over $i",
                    startAt = "2026-11-08T10:00:00Z",
                    endAt = "2026-11-08T11:00:00Z",
                    calendarId = "1",
                    description = null,
                    location = null,
                    timezone = "UTC",
                    allDay = false,
                    reminderMinutes = emptyList()
                )
            )
        }

        try {
            service.applyBatch(fiftyOneOps)
            fail("超过 50 项必须拒绝")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("50") == true)
        }
        assertEquals(previousBatchCount, provider.applyBatchCount)
    }

    @Test
    fun `全天日程使用UTC午夜与次日开区间`() {
        val service = AgentCalendarToolService(context)

        val batch = listOf(
            CalendarBatchMutation.Create(
                CalendarEventCreateRequest(
                    title = "全天活动",
                    startAt = "2026-11-08T10:30:00+08:00",
                    endAt = "2026-11-08T20:30:00+08:00",
                    calendarId = "1",
                    description = "全天测试",
                    location = "各地",
                    timezone = "Asia/Shanghai",
                    allDay = true,
                    reminderMinutes = emptyList()
                )
            )
        )

        val result = service.applyBatch(batch)
        assertEquals(true, result["success"])
        assertEquals(true, result["verified"])

        val event = provider.events.values.first()
        assertEquals(Instant.parse("2026-11-08T00:00:00Z").toEpochMilli(), event.getAsLong(CalendarContract.Events.DTSTART))
        assertEquals(Instant.parse("2026-11-09T00:00:00Z").toEpochMilli(), event.getAsLong(CalendarContract.Events.DTEND))
        assertEquals("UTC", event.getAsString(CalendarContract.Events.EVENT_TIMEZONE))
        assertEquals(1, event.getAsInteger(CalendarContract.Events.ALL_DAY))
    }

    @Test
    fun `删除不存在日程在提交前拒绝`() {
        val service = AgentCalendarToolService(context)
        val initialBatchCount = provider.applyBatchCount

        val batch = listOf(
            CalendarBatchMutation.Delete("999999")
        )

        try {
            service.applyBatch(batch)
            fail("删除不存在日程应拒绝")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("未找到对应日程") == true)
        }
        // 未向 Provider 提交任何批处理
        assertEquals(initialBatchCount, provider.applyBatchCount)
    }

    @Test
    fun `批处理内重复目标在提交前拒绝`() {
        val service = AgentCalendarToolService(context)

        // 预置两个日程
        val id1 = ContentUris.parseId(provider.insert(CalendarContract.Events.CONTENT_URI, ContentValues().apply {
            put(CalendarContract.Events.TITLE, "日程1")
            put(CalendarContract.Events.DTSTART, 1794200000000L)
            put(CalendarContract.Events.DTEND, 1794203600000L)
            put(CalendarContract.Events.CALENDAR_ID, 1L)
            put(CalendarContract.Events.EVENT_TIMEZONE, "UTC")
            put(CalendarContract.Events.ALL_DAY, 0)
        })).toString()

        val initialBatchCount = provider.applyBatchCount

        // 1. 重复数值 ID（例如 "1" 与 "01"）
        val duplicateNumericBatch = listOf(
            CalendarBatchMutation.Update(CalendarEventUpdateRequest(eventId = id1, title = "新标题1", startAt = null, endAt = null, description = null, location = null, timezone = null, allDay = null, reminderMinutes = null)),
            CalendarBatchMutation.Update(CalendarEventUpdateRequest(eventId = "0$id1", title = "新标题2", startAt = null, endAt = null, description = null, location = null, timezone = null, allDay = null, reminderMinutes = null))
        )
        try {
            service.applyBatch(duplicateNumericBatch)
            fail("不同字符串但相同数值ID必须拒绝")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("重复操作") == true)
        }

        // 2. Update 与 Delete 针对相同 ID（例如 "1" 与 "+1"）
        val updateAndDeleteBatch = listOf(
            CalendarBatchMutation.Update(CalendarEventUpdateRequest(eventId = id1, title = "新标题1", startAt = null, endAt = null, description = null, location = null, timezone = null, allDay = null, reminderMinutes = null)),
            CalendarBatchMutation.Delete(eventId = "+$id1")
        )
        try {
            service.applyBatch(updateAndDeleteBatch)
            fail("重复目标必须拒绝")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("重复操作") == true)
        }

        assertEquals(initialBatchCount, provider.applyBatchCount)
    }

    @Test
    fun `提交成功后读回失败不发生二次提交且保留已知ID`() {
        val service = AgentCalendarToolService(context)

        val batch = listOf(
            CalendarBatchMutation.Create(
                CalendarEventCreateRequest(
                    title = "提交后只读故障",
                    startAt = "2026-11-08T10:00:00Z",
                    endAt = "2026-11-08T11:00:00Z",
                    calendarId = "1",
                    description = null,
                    location = null,
                    timezone = "UTC",
                    allDay = false,
                    reminderMinutes = emptyList()
                )
            )
        )

        // 仅在提交完成后读回阶段抛出异常，创建前 resolveWritableCalendarId 正常通过
        provider.failPostCommitReadback = true
        val result = service.applyBatch(batch)

        assertEquals(true, result["success"])
        assertEquals(false, result["verified"])
        assertEquals(1, provider.applyBatchCount) // 严格单次提交，绝不重试写
        @Suppress("UNCHECKED_CAST")
        val items = result["items"] as List<Map<String, Any?>>
        assertEquals(1, items.size)
        assertEquals("create", items[0]["operation"])
        assertEquals(false, items[0]["verified"])
        assertNotNull(items[0]["eventId"])
        assertEquals(1, provider.events.size) // 确实已持久化
    }

    @Test
    fun `单条方法兼容保留旧合同字段与确认状态`() {
        val service = AgentCalendarToolService(context)

        // 1. 成功读回验证
        val created = service.createEvent(
            CalendarEventCreateRequest(
                title = "单个日程",
                startAt = "2026-11-08T10:00:00Z",
                endAt = "2026-11-08T11:00:00Z",
                calendarId = "1",
                description = "说明",
                location = "地点",
                timezone = "UTC",
                allDay = false,
                reminderMinutes = listOf(10, 30)
            )
        )
        assertEquals(true, created["success"])
        assertEquals(true, created["verified"])
        val eventId = created["eventId"] as String
        assertEquals("单个日程", created["title"])
        assertEquals("1", created["calendarId"])
        assertEquals("已创建日程“单个日程”", created["summary"])

        // updateEvent
        val updated = service.updateEvent(
            CalendarEventUpdateRequest(
                eventId = eventId,
                title = "改后日程",
                startAt = null,
                endAt = null,
                description = null,
                location = null,
                timezone = null,
                allDay = null,
                reminderMinutes = listOf(5)
            )
        )
        assertEquals(true, updated["success"])
        assertEquals(true, updated["verified"])
        assertEquals(eventId, updated["eventId"])
        assertEquals("改后日程", updated["title"])
        assertEquals("已更新日程“改后日程”", updated["summary"])

        // 2. 读回异常时透传 verified=false 与未确认 summary
        provider.failPostCommitReadback = true
        val deleted = service.deleteEvent(eventId)
        assertEquals(true, deleted["success"])
        assertEquals(false, deleted["verified"])
        assertEquals(eventId, deleted["eventId"])
        assertTrue((deleted["summary"] as String).contains("状态未确认"))
        assertEquals("提交已受理，但状态未确认", deleted["warning"])
        assertEquals(0, provider.events.size)
    }

    @Test
    fun `PersonalContextService批量解析与预检查行为`() {
        val pcs = PersonalContextService(context)

        // 1. 无写权限时拒绝
        context.permissions.remove(Manifest.permission.WRITE_CALENDAR)
        try {
            pcs.executeCalendar("calendar_event_batch", json("""{"operations":[]}"""))
            fail("缺少写权限应被拒绝")
        } catch (e: PersonalContextException) {
            assertEquals("permission_required", e.code)
        }
        context.permissions.add(Manifest.permission.WRITE_CALENDAR)

        // 2. operations 为空拒绝
        try {
            pcs.executeCalendar("calendar_event_batch", json("""{"operations":[]}"""))
            fail("空 operations 应被拒绝")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("不能为空") == true)
        }

        // 3. 包含未知顶层字段拒绝
        try {
            pcs.executeCalendar("calendar_event_batch", json("""{"operations":[],"unknownKey":"foo"}"""))
            fail("未知顶层字段应被拒绝")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("未知参数") == true)
        }

        // 4. operations 项包含未知字段拒绝
        try {
            pcs.executeCalendar("calendar_event_batch", json("""{"operations":[{"operation":"create","arguments":{},"extra":"bad"}]}"""))
            fail("operation 包含未知字段应被拒绝")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("包含未知字段") == true)
        }

        // 5. 未知操作类型拒绝
        try {
            pcs.executeCalendar("calendar_event_batch", json("""{"operations":[{"operation":"drop_table","arguments":{}}]}"""))
            fail("未知操作类型应被拒绝")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("未知操作类型") == true)
        }

        // 6. arguments 包含未知字段拒绝
        try {
            pcs.executeCalendar("calendar_event_batch", json("""{"operations":[{"operation":"create","arguments":{"title":"T","startAt":"2026-11-08T00:00:00Z","endAt":"2026-11-08T01:00:00Z","hackedField":"bad"}}]}"""))
            fail("arguments 包含未知字段应被拒绝")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("包含未知字段") == true)
        }

        // 7. 正确格式成功解析并执行批量创建
        val validPayload = json("""
            {
                "operations": [
                    {
                        "operation": "create",
                        "arguments": {
                            "title": "会议1",
                            "startAt": "2026-11-08T14:00:00Z",
                            "endAt": "2026-11-08T15:00:00Z",
                            "calendarId": "1",
                            "reminderMinutes": [10]
                        }
                    },
                    {
                        "operation": "create",
                        "arguments": {
                            "title": "会议2",
                            "startAt": "2026-11-08T16:00:00Z",
                            "endAt": "2026-11-08T17:00:00Z",
                            "calendarId": "1"
                        }
                    }
                ]
            }
        """.trimIndent())
        val result = pcs.executeCalendar("calendar_event_batch", validPayload)
        assertEquals(true, result["success"])
        assertEquals(true, result["verified"])
        @Suppress("UNCHECKED_CAST")
        val items = result["items"] as List<Map<String, Any?>>
        assertEquals(2, items.size)
        assertEquals("create", items[0]["operation"])
        assertEquals("create", items[1]["operation"])
        assertEquals(2, provider.events.size)

        // 8. reminderMinutes 非数组类型必须拒绝（防止静默清空提醒）
        try {
            pcs.executeCalendar("calendar_event_batch", json("""{"operations":[{"operation":"create","arguments":{"title":"T","startAt":"2026-11-08T00:00:00Z","endAt":"2026-11-08T01:00:00Z","reminderMinutes":"10"}}]}"""))
            fail("reminderMinutes 为非数组字符串必须拒绝")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("reminderMinutes") == true)
        }
        try {
            pcs.executeCalendar("calendar_event_batch", json("""{"operations":[{"operation":"create","arguments":{"title":"T","startAt":"2026-11-08T00:00:00Z","endAt":"2026-11-08T01:00:00Z","reminderMinutes":{}}}]}"""))
            fail("reminderMinutes 为对象必须拒绝")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("reminderMinutes") == true)
        }
        try {
            pcs.executeCalendar("calendar_event_batch", json("""{"operations":[{"operation":"create","arguments":{"title":"T","startAt":"2026-11-08T00:00:00Z","endAt":"2026-11-08T01:00:00Z","reminderMinutes":null}}]}"""))
            fail("reminderMinutes 为 null 必须拒绝")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("reminderMinutes") == true)
        }

        // 9. 重复数值 ID（例如 "1" 与 "01"）在解析层拒绝
        try {
            pcs.executeCalendar("calendar_event_batch", json("""{"operations":[{"operation":"delete","arguments":{"eventId":"1"}},{"operation":"delete","arguments":{"eventId":"01"}}]}"""))
            fail("重复数值ID必须拒绝")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("重复操作") == true)
        }

        // 10. 原始 rawPayloadBytes > 256KiB 拒绝
        try {
            pcs.executeCalendar("calendar_event_batch", validPayload, rawPayloadBytes = 256 * 1024 + 1)
            fail("rawPayloadBytes > 256KiB 必须拒绝")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("256KiB") == true)
        }
    }

    @Test
    fun `PersonalContextService公共execute方法成功调度批量操作`() = runBlocking {
        val pcs = PersonalContextService(context)
        val payload = json("""
            {
                "operations": [
                    {
                        "operation": "create",
                        "arguments": {
                            "title": "公共调度测试",
                            "startAt": "2026-11-08T18:00:00Z",
                            "endAt": "2026-11-08T19:00:00Z",
                            "calendarId": "1"
                        }
                    }
                ]
            }
        """.trimIndent())
        val result = pcs.execute("calendar_event_batch", payload)
        assertEquals(true, result["success"])
        assertEquals(true, result["verified"])
        @Suppress("UNCHECKED_CAST")
        val items = result["items"] as List<Map<String, Any?>>
        assertEquals(1, items.size)
        assertEquals("create", items[0]["operation"])
        assertEquals(1, provider.events.size)
    }

    @Test
    fun `PersonalContextCapabilityModule在toolCall原始参数处拒绝超256KiB`() = runBlocking {
        val module = PersonalContextCapabilityModule(context)
        val handler = module.handlers.first()

        val hugePadding = " ".repeat(257 * 1024)
        val oversizedCall = AssistantToolCall(
            id = "call_1",
            function = AssistantToolCallFunction(
                name = "calendar_event_batch",
                arguments = """{"operations":[]$hugePadding}"""
            )
        )

        try {
            handler.execute(
                oversizedCall,
                json("""{"operations":[]}"""),
                AgentToolRegistry.RuntimeToolDescriptor("calendar_event_batch", "批量管理日程", "calendar"),
                Mockito.mock(AgentExecutionEnvironment::class.java),
                Mockito.mock(AgentCallback::class.java),
                Mockito.mock(AgentToolExecutionHandle::class.java)
            )
            fail("原始参数超过 256KiB 应在 handler 处直接拒绝")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("256KiB") == true)
        }
    }

    @Test
    fun `真实CalendarProvider规范直接对item URI使用selection被拒绝而在collection URI使用selection正常执行`() {
        val eventId = ContentUris.parseId(provider.insert(CalendarContract.Events.CONTENT_URI, ContentValues().apply {
            put(CalendarContract.Events.TITLE, "原日程")
            put(CalendarContract.Events.CALENDAR_ID, 1L)
            put(CalendarContract.Events.DTSTART, 1794200000000L)
            put(CalendarContract.Events.DTEND, 1794203600000L)
            put(CalendarContract.Events.EVENT_TIMEZONE, "UTC")
            put(CalendarContract.Events.ALL_DAY, 0)
        })).toString()

        // 1. 模拟错误调用：直接对 item URI (events/1) 附加 selection，必须被拒绝抛出异常
        val badItemUri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId.toLong())
        try {
            provider.update(badItemUri, ContentValues().apply { put("title", "新标题") }, "title = ?", arrayOf("原日程"))
            fail("对 item URI 使用 selection 必须抛出 IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("Selection not permitted") == true)
        }
        try {
            provider.delete(badItemUri, "title = ?", arrayOf("原日程"))
            fail("对 item URI 使用 selection 必须抛出 IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("Selection not permitted") == true)
        }

        // 2. 正确规范：对 collection URI (Events.CONTENT_URI) 传入 where _id = ? + 快照字段，正常更新与删除
        val service = AgentCalendarToolService(context)
        val updated = service.updateEvent(CalendarEventUpdateRequest(eventId = eventId, title = "正确规范更新", startAt = null, endAt = null, description = null, location = null, timezone = null, allDay = null, reminderMinutes = null))
        assertEquals(true, updated["success"])
        assertEquals(true, updated["verified"])
        assertEquals("正确规范更新", provider.events[eventId.toLong()]?.getAsString(CalendarContract.Events.TITLE))

        val deleted = service.deleteEvent(eventId)
        assertEquals(true, deleted["success"])
        assertEquals(true, deleted["verified"])
        assertEquals(0, provider.events.size)
    }

    private class TestContext(base: Context) : ContextWrapper(base) {
        val permissions = mutableSetOf(
            Manifest.permission.READ_CALENDAR,
            Manifest.permission.WRITE_CALENDAR
        )
        private val pm = Mockito.mock(PackageManager::class.java).apply {
            Mockito.`when`(resolveContentProvider(Mockito.anyString(), Mockito.anyInt())).thenReturn(ProviderInfo())
            Mockito.`when`(getWhitelistedRestrictedPermissions(Mockito.anyString(), Mockito.anyInt())).thenReturn(emptySet())
        }
        override fun getPackageManager(): PackageManager = pm
        override fun checkPermission(permission: String, pid: Int, uid: Int): Int =
            if (permission in permissions) PackageManager.PERMISSION_GRANTED else PackageManager.PERMISSION_DENIED
        override fun getApplicationContext(): Context = this
    }

    private class TransactionalCalendarProvider : ContentProvider() {
        var applyBatchCount = 0
        var failReminders = false
        var failQueries = false
        var failPostCommitReadback = false
        var failUpdateConflict = false
        var onBeforeUpdate: (() -> Unit)? = null

        val events = LinkedHashMap<Long, ContentValues>()
        val reminders = mutableListOf<ContentValues>()
        private var nextEventId = 1L
        private var nextReminderId = 1L

        override fun onCreate(): Boolean = true
        override fun getType(uri: Uri): String = "vnd.android.cursor.dir/event"

        override fun applyBatch(operations: ArrayList<ContentProviderOperation>): Array<ContentProviderResult> {
            applyBatchCount++
            val eventsSnapshot = LinkedHashMap<Long, ContentValues>()
            for ((k, v) in events) {
                eventsSnapshot[k] = ContentValues(v)
            }
            val remindersSnapshot = reminders.map { ContentValues(it) }.toMutableList()

            val numOps = operations.size
            val results = arrayOfNulls<ContentProviderResult>(numOps)
            try {
                for (i in 0 until numOps) {
                    val op = operations[i]
                    results[i] = op.apply(this, results, i)
                }
                return results.requireNoNulls()
            } catch (e: Exception) {
                events.clear()
                events.putAll(eventsSnapshot)
                reminders.clear()
                reminders.addAll(remindersSnapshot)
                if (e is OperationApplicationException) throw e
                throw OperationApplicationException("事务执行失败并已回滚", e)
            } finally {
                if (failPostCommitReadback) {
                    failQueries = true
                }
            }
        }

        override fun insert(uri: Uri, values: ContentValues?): Uri {
            val v = ContentValues(values)
            when (uri) {
                CalendarContract.Events.CONTENT_URI -> {
                    val id = nextEventId++
                    v.put(CalendarContract.Events._ID, id)
                    if (!v.containsKey(CalendarContract.Events.CALENDAR_ID)) {
                        v.put(CalendarContract.Events.CALENDAR_ID, 1L)
                    }
                    events[id] = v
                    return ContentUris.withAppendedId(uri, id)
                }
                CalendarContract.Reminders.CONTENT_URI -> {
                    if (failReminders) {
                        throw IllegalStateException("提醒创建失败")
                    }
                    val id = nextReminderId++
                    v.put(CalendarContract.Reminders._ID, id)
                    reminders.add(v)
                    return ContentUris.withAppendedId(uri, id)
                }
                else -> throw IllegalArgumentException("Unknown insert URI: $uri")
            }
        }

        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int {
            if (uri.lastPathSegment?.toLongOrNull() != null && selection != null) {
                throw IllegalArgumentException("Selection not permitted for $uri")
            }
            onBeforeUpdate?.invoke()
            onBeforeUpdate = null
            if (failUpdateConflict) return 0
            val eventId = uri.lastPathSegment?.toLongOrNull()
                ?: selectionArgs?.firstOrNull()?.toLongOrNull()
            val existing = events[eventId] ?: return 0

            if (selection != null && selectionArgs != null) {
                val parts = selection.split(" AND ")
                val titlePartIdx = parts.indexOfFirst { it == "${CalendarContract.Events.TITLE} = ?" }
                if (titlePartIdx >= 0) {
                    val expectedTitle = selectionArgs.getOrNull(titlePartIdx)
                    if (expectedTitle != null && existing.getAsString(CalendarContract.Events.TITLE) != expectedTitle) {
                        return 0
                    }
                }
                val dirtyPartIdx = parts.indexOfFirst { it == "${CalendarContract.Events.DIRTY} = ?" }
                if (dirtyPartIdx >= 0) {
                    val expectedDirty = selectionArgs.getOrNull(dirtyPartIdx)?.toIntOrNull()
                    if (expectedDirty != null && existing.getAsInteger(CalendarContract.Events.DIRTY) != expectedDirty) {
                        return 0
                    }
                }
            }

            val updated = ContentValues(existing)
            if (values != null) {
                updated.putAll(values)
            }
            events[eventId!!] = updated
            return 1
        }

        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
            if (uri.lastPathSegment?.toLongOrNull() != null && selection != null) {
                throw IllegalArgumentException("Selection not permitted for $uri")
            }
            when {
                uri.pathSegments.contains("events") -> {
                    val eventId = uri.lastPathSegment?.toLongOrNull()
                        ?: selectionArgs?.firstOrNull()?.toLongOrNull()
                    val existing = events[eventId] ?: return 0
                    if (selection != null && selectionArgs != null) {
                        val parts = selection.split(" AND ")
                        val titlePartIdx = parts.indexOfFirst { it == "${CalendarContract.Events.TITLE} = ?" }
                        if (titlePartIdx >= 0) {
                            val expectedTitle = selectionArgs.getOrNull(titlePartIdx)
                            if (expectedTitle != null && existing.getAsString(CalendarContract.Events.TITLE) != expectedTitle) {
                                return 0
                            }
                        }
                    }
                    return if (events.remove(eventId) != null) 1 else 0
                }
                uri.pathSegments.contains("reminders") -> {
                    val eventId = selectionArgs?.firstOrNull()?.toLongOrNull()
                    val initialSize = reminders.size
                    reminders.removeAll { it.getAsLong(CalendarContract.Reminders.EVENT_ID) == eventId }
                    return initialSize - reminders.size
                }
                else -> return 0
            }
        }

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?
        ): Cursor? {
            if (failQueries && uri != CalendarContract.Calendars.CONTENT_URI) {
                throw IllegalStateException("读回阶段服务不可用")
            }
            val cols = projection ?: arrayOf(CalendarContract.Events._ID)

            when {
                uri == CalendarContract.Calendars.CONTENT_URI -> {
                    return MatrixCursor(cols).apply {
                        addRow(cols.map { col ->
                            when (col) {
                                CalendarContract.Calendars._ID -> 1L
                                CalendarContract.Calendars.CALENDAR_DISPLAY_NAME -> "Primary Calendar"
                                CalendarContract.Calendars.ACCOUNT_NAME -> "test@example.com"
                                CalendarContract.Calendars.ACCOUNT_TYPE -> "LOCAL"
                                CalendarContract.Calendars.VISIBLE -> 1
                                CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL -> CalendarContract.Calendars.CAL_ACCESS_OWNER
                                CalendarContract.Calendars.IS_PRIMARY -> 1
                                CalendarContract.Calendars.CALENDAR_COLOR -> -0x10000
                                else -> null
                            }
                        })
                    }
                }
                uri.pathSegments.contains("events") -> {
                    val eventId = uri.lastPathSegment?.toLongOrNull()
                    val targetEvents = if (eventId != null) {
                        listOfNotNull(events[eventId])
                    } else {
                        events.values.toList()
                    }
                    return MatrixCursor(cols).apply {
                        for (ev in targetEvents) {
                            addRow(cols.map { col ->
                                when (col) {
                                    CalendarContract.Events._ID -> ev.getAsLong(CalendarContract.Events._ID)
                                    CalendarContract.Events.CALENDAR_ID -> ev.getAsLong(CalendarContract.Events.CALENDAR_ID) ?: 1L
                                    CalendarContract.Events.TITLE -> ev.getAsString(CalendarContract.Events.TITLE).orEmpty()
                                    CalendarContract.Events.DESCRIPTION -> ev.getAsString(CalendarContract.Events.DESCRIPTION).orEmpty()
                                    CalendarContract.Events.EVENT_LOCATION -> ev.getAsString(CalendarContract.Events.EVENT_LOCATION).orEmpty()
                                    CalendarContract.Events.DTSTART -> ev.getAsLong(CalendarContract.Events.DTSTART) ?: 0L
                                    CalendarContract.Events.DTEND -> ev.getAsLong(CalendarContract.Events.DTEND) ?: 0L
                                    CalendarContract.Events.EVENT_TIMEZONE -> ev.getAsString(CalendarContract.Events.EVENT_TIMEZONE) ?: "UTC"
                                    CalendarContract.Events.ALL_DAY -> ev.getAsInteger(CalendarContract.Events.ALL_DAY) ?: 0
                                    CalendarContract.Events.LAST_SYNCED -> ev.getAsLong(CalendarContract.Events.LAST_SYNCED)
                                    CalendarContract.Events.DIRTY -> ev.getAsInteger(CalendarContract.Events.DIRTY)
                                    CalendarContract.Events.MUTATORS -> ev.getAsString(CalendarContract.Events.MUTATORS)
                                    else -> null
                                }
                            })
                        }
                    }
                }
                uri.pathSegments.contains("reminders") -> {
                    val eventId = selectionArgs?.firstOrNull()?.toLongOrNull()
                    val targetReminders = if (eventId != null) {
                        reminders.filter { it.getAsLong(CalendarContract.Reminders.EVENT_ID) == eventId }
                    } else {
                        reminders
                    }
                    return MatrixCursor(cols).apply {
                        for (rem in targetReminders) {
                            addRow(cols.map { col ->
                                when (col) {
                                    CalendarContract.Reminders.MINUTES -> rem.getAsInteger(CalendarContract.Reminders.MINUTES) ?: 0
                                    CalendarContract.Reminders.EVENT_ID -> rem.getAsLong(CalendarContract.Reminders.EVENT_ID) ?: 0L
                                    CalendarContract.Reminders._ID -> rem.getAsLong(CalendarContract.Reminders._ID) ?: 0L
                                    else -> null
                                }
                            })
                        }
                    }
                }
                else -> return MatrixCursor(cols)
            }
        }
    }
}
