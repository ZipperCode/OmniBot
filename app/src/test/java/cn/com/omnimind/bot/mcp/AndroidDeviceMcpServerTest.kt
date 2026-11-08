package cn.com.omnimind.bot.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.runBlocking

class AndroidDeviceMcpServerTest {
    @Test
    fun `public MCP surface exposes Android device tools only and hides general tools`() {
        assertTrue(AndroidDeviceMcpServer.publicToolNames.contains("run_gui"))
        assertTrue(AndroidDeviceMcpServer.publicToolNames.contains("system_clock_action"))
        assertFalse(AndroidDeviceMcpServer.publicToolNames.contains("android_system_action"))
        assertFalse(
            AndroidDeviceMcpServer.publicToolNames.any {
                it in setOf(
                    "context_time_now",
                    "browser_use",
                    "file_read",
                    "file_write",
                    "file_edit",
                    "file_list",
                    "file_search",
                    "file_stat",
                    "file_move",
                    "skills_list",
                    "skills_read",
                    "music_playback_control",
                    "memory_search",
                    "memory_write_daily",
                    "memory_upsert_longterm",
                    "memory_rollup_day",
                    "memory_load",
                    "subagent_dispatch",
                )
            },
        )
    }

    @Test
    fun `system action tool is exposed only to ACP caller and hidden from global caller`() {
        val context = org.mockito.Mockito.mock(android.content.Context::class.java)
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined)
        val globalTools = AndroidDeviceMcpServer.modernToolDescriptors(context, scope, McpDeviceCaller.Global)
        assertFalse(globalTools.any { it.name == AndroidDeviceMcpServer.SYSTEM_ACTION_TOOL_NAME })

        val acpCaller = McpDeviceCaller.Acp("test-lease", "test-token")
        val acpTools = AndroidDeviceMcpServer.modernToolDescriptors(context, scope, acpCaller)
        assertTrue(acpTools.any { it.name == AndroidDeviceMcpServer.SYSTEM_ACTION_TOOL_NAME })
    }

    @Test
    fun `calling system action tool with Global caller returns error`() = runBlocking {
        val context = org.mockito.Mockito.mock(android.content.Context::class.java)
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined)
        val result = AndroidDeviceMcpServer.modernCallTool(
            context = context,
            scope = scope,
            name = AndroidDeviceMcpServer.SYSTEM_ACTION_TOOL_NAME,
            arguments = emptyMap(),
            caller = McpDeviceCaller.Global,
        )
        assertTrue(result.isError == true)
    }

    @Test
    fun `clock action durationSeconds overflow and non-integer float are strictly rejected without dispatch`() = runBlocking {
        val context = org.mockito.Mockito.mock(android.content.Context::class.java)
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined)

        // Overflow: 4294967356L (which would truncate to 60 if using Number.toInt())
        val overflowArgs = mapOf(
            "action" to kotlinx.serialization.json.JsonPrimitive("create_timer"),
            "durationSeconds" to kotlinx.serialization.json.JsonPrimitive(4294967356L),
        )
        val overflowResult = AndroidDeviceMcpServer.modernCallTool(
            context = context,
            scope = scope,
            name = "system_clock_action",
            arguments = overflowArgs,
        )
        assertTrue(overflowResult.isError == true)
        val contentStr = overflowResult.content.first().toString()
        assertTrue(contentStr.contains("invalid_argument") || contentStr.contains("1 到 86400"))

        // Float: 60.9 (which would truncate to 60 if using Number.toInt())
        val floatArgs = mapOf(
            "action" to kotlinx.serialization.json.JsonPrimitive("create_timer"),
            "durationSeconds" to kotlinx.serialization.json.JsonPrimitive(60.9),
        )
        val floatResult = AndroidDeviceMcpServer.modernCallTool(
            context = context,
            scope = scope,
            name = "system_clock_action",
            arguments = floatArgs,
        )
        assertTrue(floatResult.isError == true)
    }

    @Test
    fun `missing default plugin explains how to enable phone control`() = runBlocking {
        var message = ""
        try {
            AndroidDeviceMcpServer.requireDefaultPluginEnabled(
                isEnabled = { false },
                inspect = { null },
            )
        } catch (error: IllegalStateException) {
            message = error.message.orEmpty()
        }

        assertTrue(message.contains("插件市场"))
        assertTrue(message.contains("安装并启用"))
    }

    @Test
    fun `disabled installed default plugin explains how to enable it`() = runBlocking {
        var message = ""
        try {
            AndroidDeviceMcpServer.requireDefaultPluginEnabled(
                isEnabled = { false },
                inspect = {
                    AndroidDeviceMcpServer.DefaultPluginStatus(
                        installed = true,
                        enabled = false,
                    )
                },
            )
        } catch (error: IllegalStateException) {
            message = error.message.orEmpty()
        }

        assertTrue(message.contains("启用插件"))
        assertTrue(message.contains("无障碍服务"))
    }

    @Test
    fun `ready runtime skips plugin inspection`() = runBlocking {
        var inspectionCount = 0

        AndroidDeviceMcpServer.requireDefaultPluginEnabled(
            isEnabled = { true },
            inspect = {
                inspectionCount += 1
                null
            },
        )

        assertEquals(0, inspectionCount)
    }
}
