package cn.com.omnimind.baselib.llm.chatgpt

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class ChatGptResponsesRequestTest {
    @Test
    fun flatFunctionAndCustomToolsBecomeDeveloperAdditionalToolsBeforeHistory() {
        val request = request("""
            {
              "model": "gpt-test",
              "input": [{"role": "user", "content": "Use the tools"}],
              "tools": [
                {"type": "function", "name": "read_file", "parameters": {"type": "object"}, "strict": true},
                {"type": "custom", "name": "patch", "format": {"type": "text"}}
              ],
              "tool_choice": "auto"
            }
        """)

        val result = ChatGptResponsesRequest.normalize(request)
        val input = result.getValue("input").jsonArray
        val definitions = input.first().jsonObject
        assertEquals("additional_tools", definitions.getValue("type").jsonPrimitive.content)
        assertEquals("developer", definitions.getValue("role").jsonPrimitive.content)
        assertEquals(request["tools"], definitions["tools"])
        assertEquals(request.getValue("input").jsonArray.single(), input[1])
        assertEquals(2, input.size)
        assertFalse(result.containsKey("tools"))
        assertEquals(request["tool_choice"], result["tool_choice"])
    }

    @Test
    fun namespaceDefinitionsRemainTopLevelAndNormalizationDoesNotDuplicateTools() {
        val request = request("""
            {
              "model": "gpt-test",
              "input": "Use functions.exec",
              "tools": [
                {"type": "namespace", "name": "functions", "description": "Agent tools",
                 "tools": [{"type": "function", "name": "exec", "parameters": {"type": "object"}}]},
                {"type": "function", "name": "lookup", "parameters": {"type": "object"}}
              ]
            }
        """)

        val result = ChatGptResponsesRequest.normalize(request)
        assertEquals(JsonArray(listOf(request.getValue("tools").jsonArray[0])), result["tools"])
        val input = result.getValue("input").jsonArray
        assertEquals("additional_tools", input[0].jsonObject["type"]?.jsonPrimitive?.content)
        assertEquals("user", input[1].jsonObject["role"]?.jsonPrimitive?.content)
        assertEquals("Use functions.exec", input[1].jsonObject["content"]?.jsonPrimitive?.content)
        assertEquals(result, ChatGptResponsesRequest.normalize(result))
    }

    @Test
    fun signedReasoningAndToolCallResultIdentitiesArePreserved() {
        val request = request("""
            {
              "model": "gpt-test",
              "include": ["reasoning.encrypted_content"],
              "input": [
                {"type": "reasoning", "id": "rs_original", "encrypted_content": "signed+payload/==",
                 "summary": [{"type": "summary_text", "text": "Keep this reasoning"}]},
                {"type": "function_call", "id": "fc_original", "call_id": "call_original",
                 "name": "read_file", "arguments": "{\"path\":\"项目.txt\"}", "status": "completed"},
                {"type": "function_call_output", "id": "result_original", "call_id": "call_original", "output": "line one\nline two"},
                {"type": "message", "id": "msg_original", "role": "assistant", "status": "completed",
                 "content": [{"type": "output_text", "text": "Done", "annotations": []}]}
              ]
            }
        """)

        val result = ChatGptResponsesRequest.normalize(request)
        assertEquals(request["input"], result["input"])
        assertEquals(request["include"], result["include"])
    }

    @Test
    fun systemMessagesChangeOnlyRoleAndLeaveOtherItemsUntouched() {
        val request = request("""
            {
              "model": "gpt-test",
              "input": [
                {"type": "message", "id": "system-id", "role": "system", "content": [{"type": "input_text", "text": "Follow instructions"}]},
                {"role": "developer", "content": "Existing developer message"},
                {"role": "user", "content": "Hello"}
              ]
            }
        """)
        val before = request.getValue("input").jsonArray
        val after = ChatGptResponsesRequest.normalize(request).getValue("input").jsonArray
        assertEquals(JsonObject(before[0].jsonObject + ("role" to JsonPrimitive("developer"))), after[0])
        assertEquals(before[1], after[1])
        assertEquals(before[2], after[2])
        assertEquals("system", before[0].jsonObject["role"]?.jsonPrimitive?.content)
    }

    @Test
    fun unsupportedOptionalControlsAreRemovedWhileSupportedRequestDataSurvives() {
        val request = request("""
            {
              "model": "gpt-test", "input": "Hello", "instructions": "Be concise",
              "store": true, "stream": false, "background": true,
              "max_output_tokens": 100, "max_tool_calls": 2, "metadata": {"user": "example"},
              "moderation": {}, "multi_agent": true, "prompt_cache_retention": "24h",
              "safety_identifier": "example", "temperature": 0.4, "top_logprobs": 3,
              "top_p": 0.8, "truncation": "auto", "user": "example",
              "reasoning": {"effort": "low"}, "parallel_tool_calls": true,
              "prompt_cache_key": "conversation-cache", "text": {"format": {"type": "text"}}
            }
        """)

        val result = ChatGptResponsesRequest.normalize(request)
        assertEquals(JsonPrimitive(false), result["store"])
        assertEquals(JsonPrimitive(true), result["stream"])
        for (field in listOf("background", "max_output_tokens", "max_tool_calls", "metadata",
            "moderation", "multi_agent", "prompt_cache_retention", "safety_identifier", "temperature",
            "top_logprobs", "top_p", "truncation", "user")) {
            assertFalse("Unsupported field survived: $field", result.containsKey(field))
        }
        for (field in listOf("model", "instructions", "reasoning", "parallel_tool_calls", "prompt_cache_key", "text")) {
            assertEquals("Supported field changed: $field", request[field], result[field])
        }
    }

    @Test
    fun serverSideContextReferencesAreRejectedRatherThanSilentlyDiscarded() {
        val base = request("""{"model":"gpt-test","input":"Hello"}""")
        for (field in listOf("previous_response_id", "conversation", "prompt")) {
            for (reference in listOf(JsonPrimitive("remote-context"), request("""{"id":"remote-context"}"""))) {
                assertThrows("Accepted stateful $field", IllegalArgumentException::class.java) {
                    ChatGptResponsesRequest.normalize(JsonObject(base + (field to reference)))
                }
            }
            val withNull = ChatGptResponsesRequest.normalize(JsonObject(base + (field to JsonNull)))
            assertFalse(withNull.containsKey(field))
        }
    }

    @Test
    fun unsupportedHostedToolsAreRejectedBeforeSendingARequest() {
        val base = request("""{"model":"gpt-test","input":"Hello"}""")
        for (type in listOf("image_generation", "file_search", "code_interpreter", "mcp", "computer_use_preview")) {
            val tools = JsonArray(listOf(request("""{"type":"$type"}""")))
            assertThrows("Accepted unsupported tool: $type", IllegalArgumentException::class.java) {
                ChatGptResponsesRequest.normalize(JsonObject(base + ("tools" to tools)))
            }
        }
    }

    @Test
    fun supportedWebSearchStaysAtTopLevel() {
        val request = request("""
            {"model":"gpt-test","input":"Search", "tools":[{"type":"web_search","search_context_size":"low"}]}
        """)
        val result = ChatGptResponsesRequest.normalize(request)
        assertEquals(request["tools"], result["tools"])
        assertEquals(1, result.getValue("input").jsonArray.size)
    }

    @Test
    fun malformedInputAndToolCollectionsFailLocally() {
        for (raw in listOf(
            """{"model":"gpt-test"}""",
            """{"model":"gpt-test","input":null}""",
            """{"model":"gpt-test","input":42}""",
            """{"model":"gpt-test","input":"Hello","tools":{}}""",
            """{"model":"gpt-test","input":"Hello","tools":[{}]}""",
            """{"model":"gpt-test","input":"Hello","tools":["function"]}""",
        )) {
            assertThrows(IllegalArgumentException::class.java) {
                ChatGptResponsesRequest.normalize(request(raw))
            }
        }
    }

    private fun request(raw: String) = Json.parseToJsonElement(raw.trimIndent()).jsonObject
}
