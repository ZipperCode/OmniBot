package cn.com.omnimind.bot.statistics

import cn.com.omnimind.baselib.database.TokenUsageBucket
import cn.com.omnimind.bot.webchat.VisibleConversationActivity
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class UsageStatisticsServiceTest {
    private val day = 86_400_000L
    private val zone = ZoneId.of("Asia/Shanghai")
    private fun ms(value: String) = Instant.parse(value).toEpochMilli()

    @Test fun `provider prefixes preserve the original chart model identity`() {
        mapOf(
            "openai/gpt-4o-mini" to "gpt-4o-mini",
            "Qwen/Qwen2.5-7B-Instruct" to "Qwen2.5-7B-Instruct",
            "openai:gpt-4o" to "gpt-4o",
            "openrouter|meta-llama/llama-3.1:free" to "llama-3.1:free",
            " a :: b\\model " to "model",
            "\u00a0\ufeff " to "unknown",
            " provider | model\u00a0 name " to "model name",
        ).forEach { (raw, expected) -> assertEquals(raw, expected, normalizeTokenModelId(raw)) }
    }

    @Test fun `summaries merge normalized models and retain zero weeks and cache-only calls`() {
        val result = tokenUsageSummary(listOf(
            TokenUsageBucket("openai/gpt-4o", 0, 300, 400),
            TokenUsageBucket("local/gpt-4o", 0, 300, 1),
            TokenUsageBucket("qwen/qwen3", 1, 600, 0),
            TokenUsageBucket("cache-only", 1, 0, -2),
            TokenUsageBucket("out of range", 3, 50, 50),
        ), listOf(0, day * 7, day * 14))
        assertEquals(mapOf("gpt-4o" to 600L, "qwen3" to 600L), result["modelTotals"])
        assertEquals(listOf("gpt-4o", "qwen3"), result["modelOrder"])
        assertEquals(399L, result["totalCached"])
        val weeks = result["weeklyData"] as List<*>
        assertEquals(3, weeks.size)
        assertEquals(mapOf("gpt-4o" to 600L), (weeks[0] as Map<*, *>)["modelTokens"])
        assertEquals(emptyMap<String, Long>(), (weeks[2] as Map<*, *>)["modelTokens"])
    }

    @Test fun `conversation summary excludes out of range local dates and retains streak`() {
        val today = ms("2026-10-20T16:00:00Z")
        val result = conversationActivitySummary(listOf(
            VisibleConversationActivity(today + day - 1, false),
            VisibleConversationActivity(today + 1, true),
            VisibleConversationActivity(today - 1, false),
            VisibleConversationActivity(today - 3 * day, false),
            VisibleConversationActivity(today + day, false),
        ), today - 2 * day, today, zone)
        assertEquals(mapOf("2026-10-21" to 2, "2026-10-20" to 1), result["activityMap"])
        assertEquals(3, result["totalConversations"])
        assertEquals(2, result["currentStreak"])
    }

    @Test fun `DST follows elapsed-day Dart arithmetic rather than changing calendar dates`() {
        val newYork = ZoneId.of("America/New_York")
        val today = ms("2026-03-09T04:00:00Z") // First midnight after spring-forward.
        val result = conversationActivitySummary(listOf(
            VisibleConversationActivity(ms("2026-03-09T16:00:00Z"), false),
            VisibleConversationActivity(ms("2026-03-08T16:00:00Z"), false),
            VisibleConversationActivity(ms("2026-03-07T17:00:00Z"), false),
        ), today - 3 * day, today, newYork)
        // today - 24h is March 7 at 23:00. Dart's existing streak skips March 8.
        assertEquals(2, result["currentStreak"])
        assertEquals(3, result["totalConversations"])
    }

    @Test fun `host read ranges and local account fallback are independent`() = runBlocking {
        val today = ms("2026-10-20T16:00:00Z")
        val service = UsageStatisticsService(
            readActivity = { since, until ->
                assertEquals(today - day, since)
                assertEquals(today + day, until)
                listOf(VisibleConversationActivity(today + 1, false), VisibleConversationActivity(today + 2, true))
            },
            readTokens = { since, until ->
                assertEquals(today - 7 * day, since)
                assertEquals(today + 7 * day, until)
                emptyList()
            },
            timeZone = { zone },
        )
        val conversation = service.conversationSummary(today - day, today)
        assertEquals(2, conversation["totalConversations"])
        assertEquals(1, (conversation["localSummary"] as Map<*, *>)["totalConversations"])
        assertEquals(2, (service.tokenSummary(today - 7 * day, listOf(today - 7 * day, today))["weeklyData"] as List<*>).size)
    }
}
