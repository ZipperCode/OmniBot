package cn.com.omnimind.bot.agent.tool.handlers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VlmTaskContextPayloadTest {
    @Test
    fun `vlm result keeps goal run log and final state context`() {
        val payload = buildVlmTaskContextPayload(
            requestedRunId = "gui-request",
            goal = "添加联系人 Mom",
            resultRunId = "gui-request",
            success = true,
            doneReason = "finished",
            content = "已添加联系人 Mom",
            finalStateId = "state-final",
            finalState = mapOf(
                "state_id" to "state-final",
                "package_name" to "com.android.contacts",
                "activity_name" to ".Contacts",
                "display" to mapOf("width" to 1080, "height" to 2400),
            ),
            stepCount = 7,
        )

        assertEquals("vlm_task_result", payload["context_type"])
        assertEquals("添加联系人 Mom", payload["goal"])
        assertEquals("gui-request", payload["run_log_id"])
        assertEquals(7, payload["step_count"])
        assertEquals(7, payload["action_count"])
        assertEquals("state-final", payload["final_state_id"])
        assertEquals(true, payload["registration_available"])
    }

    @Test
    fun `vlm result retains sanitized collection in success payload and strips arbitrary keys`() {
        val rawCollection = mapOf(
            "items" to listOf(
                mapOf(
                    "text" to "item-0",
                    "page_index" to 0,
                    "bounds" to listOf(50, 100, 950, 200),
                    "source" to "xml",
                    "state_id" to "state-0",
                    "forbidden_item_leak" to "do_not_leak",
                ),
                mapOf(
                    "text" to "item-1",
                    "page_index" to 1,
                    "bounds" to listOf(50.0, 200.0, 950.0, 300.0),
                    "source" to "ocr",
                    "state_id" to "state-1",
                ),
            ),
            "pages_scanned" to 2,
            "truncated" to false,
            "partial" to false,
            "stop_reason" to "no_new_content",
            "overlap_uncertain" to false,
            "ocr_status" to "ready",
            "forbidden_root_leak" to "internal_buffer",
        )

        val payload = buildVlmTaskContextPayload(
            requestedRunId = "gui-collect-1",
            goal = "采集列表",
            resultRunId = "gui-collect-1",
            success = true,
            doneReason = "finished",
            content = "已完成",
            finalStateId = "state-final",
            extras = mapOf(
                "collection" to rawCollection,
                "finished_evidence" to mapOf(
                    "status" to "matched",
                    "source" to "xml",
                    "observation_id" to "obs-2",
                ),
            ),
        )

        val collection = payload["collection"] as? Map<*, *>
        assertTrue("collection 必须作为受限 Map 透传", collection != null)
        assertFalse("collection 不得包含未在 allowlist 的根字段", collection!!.containsKey("forbidden_root_leak"))
        val items = collection["items"] as List<*>
        assertEquals(2, items.size)
        val first = items[0] as Map<*, *>
        assertFalse("collection.items 不得包含未在 allowlist 的条目字段", first.containsKey("forbidden_item_leak"))

        val evidence = payload["finished_evidence"] as? Map<*, *>
        assertTrue("finished_evidence 必须保留", evidence != null)
        assertEquals("matched", evidence!!["status"])
        assertEquals("xml", evidence["source"])
        assertEquals("obs-2", evidence["observation_id"])
    }

    @Test
    fun `vlm result retains sanitized partial collection in failure payload`() {
        val partialCollection = mapOf(
            "items" to listOf(
                mapOf(
                    "text" to "part-0",
                    "page_index" to 0,
                    "bounds" to listOf(100.0, 100.0, 900.0, 200.0),
                    "source" to "xml",
                    "state_id" to "state-mid",
                ),
            ),
            "pages_scanned" to 1,
            "truncated" to true,
            "partial" to true,
            "stop_reason" to "step_limit",
            "extra_debug_payload" to "must_be_stripped",
        )

        val payload = buildVlmTaskContextPayload(
            requestedRunId = "gui-collect-fail",
            goal = "采集长列表",
            resultRunId = "gui-collect-fail",
            success = false,
            doneReason = "step_limit",
            content = "",
            finalStateId = "state-mid",
            extras = mapOf(
                "error" to "步数超出限制",
                "collection" to partialCollection,
            ),
        )

        assertEquals(false, payload["success"])
        assertEquals("failed", payload["status"])
        val collection = payload["collection"] as? Map<*, *>
        assertTrue("失败时本 run 已采集的 partial collection 必须透传", collection != null)
        assertEquals(true, collection!!["partial"])
        assertEquals(true, collection["truncated"])
        assertEquals("step_limit", collection["stop_reason"])
        assertFalse("collection 不得包含未在 allowlist 的根字段", collection.containsKey("extra_debug_payload"))
        val items = collection["items"] as List<*>
        assertEquals(1, items.size)
    }

    @Test
    fun `sanitizeCollection handles edge cases and nulls cleanly`() {
        assertNull(sanitizeCollection(null))
        assertNull(sanitizeCollection("not_a_map"))
        assertNull(sanitizeCollection(emptyMap<String, Any?>()))
        assertNull(sanitizeCollection(mapOf("unknown_key" to 123)))

        val minimalPartial = sanitizeCollection(
            mapOf(
                "partial" to true,
                "stop_reason" to "swipe_failed",
                "pages_scanned" to 3,
            ),
        )
        assertTrue(minimalPartial != null)
        assertEquals(true, minimalPartial!!["partial"])
        assertEquals("swipe_failed", minimalPartial["stop_reason"])
        assertEquals(3, minimalPartial["pages_scanned"])
        assertEquals(emptyList<Map<String, Any?>>(), minimalPartial["items"])
    }

    @Test
    fun `sanitizeCollection drops illegal items without patching with toString or invented bounds`() {
        val corrupted = sanitizeCollection(
            mapOf(
                "items" to listOf(
                    mapOf("text" to mapOf("illegal" to "object"), "page_index" to 0, "bounds" to listOf(0, 0, 100, 100), "source" to "xml", "state_id" to "s1"),
                    mapOf("text" to "非法 bounds NaN", "page_index" to 0, "bounds" to listOf(0.0, Double.NaN, 100.0, 100.0), "source" to "xml", "state_id" to "s2"),
                    mapOf("text" to "非法 bounds 逆序", "page_index" to 0, "bounds" to listOf(100.0, 100.0, 50.0, 50.0), "source" to "xml", "state_id" to "s3"),
                    mapOf("text" to "非法 source", "page_index" to 0, "bounds" to listOf(0.0, 0.0, 100.0, 100.0), "source" to "model", "state_id" to "s4"),
                    mapOf("text" to "非法负数 page", "page_index" to -1, "bounds" to listOf(0.0, 0.0, 100.0, 100.0), "source" to "xml", "state_id" to "s5"),
                    mapOf("text" to "非法浮点 page", "page_index" to 1.5, "bounds" to listOf(0.0, 0.0, 100.0, 100.0), "source" to "xml", "state_id" to "s6"),
                    mapOf("text" to "非法 NaN page", "page_index" to Double.NaN, "bounds" to listOf(0.0, 0.0, 100.0, 100.0), "source" to "xml", "state_id" to "s7"),
                    mapOf("text" to "合法项", "page_index" to 0, "bounds" to listOf(0.0, 0.0, 100.0, 100.0), "source" to "xml", "state_id" to "s8"),
                ),
                "pages_scanned" to 1,
            ),
        )
        assertTrue(corrupted != null)
        val items = corrupted!!["items"] as List<*>
        assertEquals(1, items.size)
        assertEquals("合法项", (items[0] as Map<*, *>)["text"])
    }

    @Test
    fun `vlm result retains experience_update_error alongside collection and evidence in success`() {
        val payload = buildVlmTaskContextPayload(
            requestedRunId = "gui-exp-1",
            goal = "体验测试",
            resultRunId = "gui-exp-1",
            success = true,
            doneReason = "finished",
            content = "已完成",
            finalStateId = "state-final",
            extras = mapOf(
                "experience_update_error" to "app_experience_store_invalid",
                "auto_registered" to true,
                "registered_function_id" to "fn-1",
                "finished_evidence" to mapOf(
                    "status" to "matched",
                    "source" to "xml",
                    "observation_id" to "obs-1",
                ),
            ),
        )

        assertEquals("app_experience_store_invalid", payload["experience_update_error"])
        assertEquals(true, payload["auto_registered"])
        assertEquals("fn-1", payload["registered_function_id"])
        val evidence = payload["finished_evidence"] as? Map<*, *>
        assertEquals("matched", evidence?.get("status"))
    }

    @Test
    fun `vlm failure result retains experience_update_error in failure payload`() {
        val payload = buildVlmTaskContextPayload(
            requestedRunId = "gui-exp-2",
            goal = "体验失败测试",
            resultRunId = "gui-exp-2",
            success = false,
            doneReason = "error",
            content = "",
            finalStateId = "state-failed",
            extras = mapOf(
                "error" to "执行超时",
                "experience_update_error" to "run_not_found",
            ),
        )

        assertEquals(false, payload["success"])
        assertEquals("run_not_found", payload["experience_update_error"])
        assertEquals("执行超时", payload["error"])
    }
}
