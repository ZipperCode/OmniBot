package cn.com.omnimind.bot.agent.tool.handlers

import android.app.Application
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Base64

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, manifest = Config.NONE)
class ImageGenerationToolHandlerTest {

    private val samplePngBytes = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x00, 0x00, 0x0D
    )
    private val sampleJpegBytes = byteArrayOf(
        0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0x00, 0x10
    )
    private val sampleWebpBytes = "RIFF1234WEBPVP8 ".toByteArray(Charsets.US_ASCII)

    @Test
    fun `openAi request builder formats dall-e-3 parameters properly and avoids extraneous fields`() {
        val requestJson = OpenAiImageProtocolHandler.buildRequestJson(
            model = "dall-e-3",
            prompt = "A fantasy landscape",
            size = "1024x1536",
            quality = "high",
            outputFormat = "png",
            background = "transparent",
        )

        assertEquals("dall-e-3", requestJson.getString("model"))
        assertEquals("A fantasy landscape", requestJson.getString("prompt"))
        assertEquals(1, requestJson.getInt("n"))
        assertEquals("b64_json", requestJson.getString("response_format"))
        // 1024x1536 映射为 DALL-E 3 合法分辨率 1024x1792
        assertEquals("1024x1792", requestJson.getString("size"))
        // high 映射为 hd
        assertEquals("hd", requestJson.getString("quality"))
        // DALL-E 3 拒绝 background 与 output_format 字段
        assertFalse(requestJson.has("background"))
        assertFalse(requestJson.has("output_format"))
    }

    @Test
    fun `openAi request builder for dall-e-2 omits quality, background, and output_format`() {
        val requestJson = OpenAiImageProtocolHandler.buildRequestJson(
            model = "dall-e-2",
            prompt = "An oil painting",
            size = "512x512",
            quality = "high",
            outputFormat = "png",
            background = "auto",
        )

        assertEquals("dall-e-2", requestJson.getString("model"))
        assertEquals("512x512", requestJson.getString("size"))
        assertEquals("b64_json", requestJson.getString("response_format"))
        assertFalse(requestJson.has("quality"))
        assertFalse(requestJson.has("background"))
        assertFalse(requestJson.has("output_format"))
    }

    @Test
    fun `openAi request builder for generic models omits response_format and unselected parameters`() {
        // 未指定参数的通用端点（如 gpt-image）仅发送 model、prompt 和 n，绝不强塞 response_format
        val minimalJson = OpenAiImageProtocolHandler.buildRequestJson(
            model = "gpt-image-2",
            prompt = "A robot drawing",
            size = null,
            quality = null,
            outputFormat = null,
            background = null,
        )
        assertEquals("gpt-image-2", minimalJson.getString("model"))
        assertEquals("A robot drawing", minimalJson.getString("prompt"))
        assertEquals(1, minimalJson.getInt("n"))
        assertFalse(minimalJson.has("response_format"))
        assertFalse(minimalJson.has("size"))
        assertFalse(minimalJson.has("quality"))
        assertFalse(minimalJson.has("background"))
        assertFalse(minimalJson.has("output_format"))

        // 通用模型显式指定的参数正常透传
        val explicitJson = OpenAiImageProtocolHandler.buildRequestJson(
            model = "flux-pro-1.1",
            prompt = "A robot drawing",
            size = "1024x1024",
            quality = "high",
            outputFormat = "webp",
            background = "transparent",
        )
        assertFalse(explicitJson.has("response_format"))
        assertEquals("1024x1024", explicitJson.getString("size"))
        assertEquals("high", explicitJson.getString("quality"))
        assertEquals("transparent", explicitJson.getString("background"))
        assertEquals("webp", explicitJson.getString("output_format"))
    }

    @Test
    fun `openAi extractImage extracts b64_json and decodes correctly`() = kotlinx.coroutines.runBlocking {
        val base64Data = Base64.getEncoder().encodeToString(samplePngBytes)
        val payload = JSONObject().apply {
            put("data", JSONArray().apply {
                put(JSONObject().apply {
                    put("b64_json", base64Data)
                })
            })
        }

        val result = OpenAiImageProtocolHandler.extractImage(
            payload = payload,
            downloadImageFn = { throw UnsupportedOperationException() },
            decodeBase64Fn = ImageGenerationToolHandler::decodeBase64Image,
        )

        assertNotNull(result)
        assertTrue(result!!.bytes.contentEquals(samplePngBytes))
    }

    @Test
    fun `openAi extractImage falls back to downloading url`() = kotlinx.coroutines.runBlocking {
        var downloadedUrl: String? = null
        val payload = JSONObject().apply {
            put("data", JSONArray().apply {
                put(JSONObject().apply {
                    put("url", "https://images.example.com/generated-123.png")
                })
            })
        }

        val result = OpenAiImageProtocolHandler.extractImage(
            payload = payload,
            downloadImageFn = { url ->
                downloadedUrl = url
                sampleJpegBytes
            },
            decodeBase64Fn = ImageGenerationToolHandler::decodeBase64Image,
        )

        assertNotNull(result)
        assertEquals("https://images.example.com/generated-123.png", downloadedUrl)
        assertTrue(result!!.bytes.contentEquals(sampleJpegBytes))
    }

    @Test
    fun `gemini request builder configures contents and responseModalities`() {
        val requestJson = GeminiImageProtocolHandler.buildRequestJson("A futuristic car")
        val contents = requestJson.getJSONArray("contents")
        assertEquals(1, contents.length())

        val firstContent = contents.getJSONObject(0)
        val parts = firstContent.getJSONArray("parts")
        assertEquals(1, parts.length())
        assertEquals("A futuristic car", parts.getJSONObject(0).getString("text"))

        val generationConfig = requestJson.getJSONObject("generationConfig")
        val responseModalities = generationConfig.getJSONArray("responseModalities")
        val modalitiesList = (0 until responseModalities.length()).map { responseModalities.getString(it) }
        assertTrue(modalitiesList.contains("IMAGE"))
        assertTrue(modalitiesList.contains("TEXT"))
    }

    @Test
    fun `gemini extractImage parses inlineData and ignores audio inlineData`() {
        val imageBase64 = Base64.getEncoder().encodeToString(sampleWebpBytes)
        val audioBase64 = Base64.getEncoder().encodeToString("fake-audio-bytes".toByteArray())
        val payload = JSONObject().apply {
            put("candidates", JSONArray().apply {
                put(JSONObject().apply {
                    put("finishReason", "STOP")
                    put("content", JSONObject().apply {
                        put("parts", JSONArray().apply {
                            put(JSONObject().apply {
                                put("text", "Here is your generated image with sound description.")
                            })
                            // 含有 audio inlineData 时必须忽略，优先提取属于 image/ 模态的数据
                            put(JSONObject().apply {
                                put("inlineData", JSONObject().apply {
                                    put("mimeType", "audio/mp3")
                                    put("data", audioBase64)
                                })
                            })
                            put(JSONObject().apply {
                                put("inlineData", JSONObject().apply {
                                    put("mimeType", "image/webp")
                                    put("data", imageBase64)
                                })
                            })
                        })
                    })
                })
            })
        }

        val result = GeminiImageProtocolHandler.extractImage(
            payload = payload,
            decodeBase64Fn = ImageGenerationToolHandler::decodeBase64Image,
        )

        assertTrue(result.bytes.contentEquals(sampleWebpBytes))
        assertEquals("image/webp", result.mimeType)
    }

    @Test
    fun `gemini extractImage throws when candidate contains only text or non-image data`() {
        val audioBase64 = Base64.getEncoder().encodeToString("fake-audio-bytes".toByteArray())
        val payload = JSONObject().apply {
            put("candidates", JSONArray().apply {
                put(JSONObject().apply {
                    put("finishReason", "STOP")
                    put("content", JSONObject().apply {
                        put("parts", JSONArray().apply {
                            put(JSONObject().apply {
                                put("text", "Audio response only.")
                            })
                            put(JSONObject().apply {
                                put("inlineData", JSONObject().apply {
                                    put("mimeType", "audio/mp3")
                                    put("data", audioBase64)
                                })
                            })
                        })
                    })
                })
            })
        }

        assertThrows(IllegalStateException::class.java) {
            GeminiImageProtocolHandler.extractImage(
                payload = payload,
                decodeBase64Fn = ImageGenerationToolHandler::decodeBase64Image,
            )
        }
    }

    @Test
    fun `gemini extractImage throws on blocked finishReason`() {
        for (blockedReason in listOf("SAFETY", "IMAGE_SAFETY", "NO_IMAGE", "PROHIBITED_CONTENT")) {
            val payload = JSONObject().apply {
                put("candidates", JSONArray().apply {
                    put(JSONObject().apply {
                        put("finishReason", blockedReason)
                    })
                })
            }

            assertThrows(IllegalStateException::class.java) {
                GeminiImageProtocolHandler.extractImage(
                    payload = payload,
                    decodeBase64Fn = ImageGenerationToolHandler::decodeBase64Image,
                )
            }
        }
    }

    @Test
    fun `gemini extractImage throws on promptFeedback blockReason`() {
        val payload = JSONObject().apply {
            put("promptFeedback", JSONObject().apply {
                put("blockReason", "SAFETY")
            })
        }

        assertThrows(IllegalStateException::class.java) {
            GeminiImageProtocolHandler.extractImage(
                payload = payload,
                decodeBase64Fn = ImageGenerationToolHandler::decodeBase64Image,
            )
        }
    }

    @Test
    fun `requireSupportedImage verifies magic bytes and returns detected mimeType`() {
        assertEquals("image/png", ImageGenerationToolHandler.requireSupportedImage(samplePngBytes))
        assertEquals("image/jpeg", ImageGenerationToolHandler.requireSupportedImage(sampleJpegBytes))
        assertEquals("image/webp", ImageGenerationToolHandler.requireSupportedImage(sampleWebpBytes))

        assertThrows(IllegalArgumentException::class.java) {
            ImageGenerationToolHandler.requireSupportedImage("plain text".toByteArray())
        }
        assertThrows(IllegalArgumentException::class.java) {
            ImageGenerationToolHandler.requireSupportedImage(ByteArray(0))
        }
    }
}
