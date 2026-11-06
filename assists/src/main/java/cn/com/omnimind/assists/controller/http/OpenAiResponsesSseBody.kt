package cn.com.omnimind.assists.controller.http

import java.io.EOFException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Response
import okhttp3.ResponseBody
import okio.ByteString.Companion.decodeHex

/**
 * Accept a positively identified Responses SSE body with a missing/incorrect media type.
 * Normalize only that transport envelope; OkHttp remains the sole SSE reader and
 * the shared Responses adapter remains the sole owner of completion/error events.
 * The caller restricts this to the OAuth Responses endpoint, never model catalogs.
 */
object OpenAiResponsesSseBody {
    private val eventStreamType = "text/event-stream".toMediaType()
    private val utf8Bom = "efbbbf".decodeHex()
    private val responseEventLine = Regex("event: ?(?:response\\.[A-Za-z0-9_.-]+|error)")
    private const val MAX_PREFIX_BYTES = 1024L
    private const val MAX_PREFIX_LINES = 16

    fun normalize(response: Response): Response {
        if (!response.isSuccessful) return response
        val body = response.body
        val originalType = body.contentType()
        val declaredSse = originalType?.type == "text" && originalType.subtype == "event-stream"
        val source = body.source()
        val probe = source.peek()
        val hasBom = probe.rangeEquals(0, utf8Bom)
        if (hasBom) probe.skip(utf8Bom.size.toLong())
        if (!declaredSse && !hasResponsesSsePrefix(probe)) return response
        if (declaredSse && !hasBom) return response

        // A leading UTF-8 BOM is SSE framing, not event data. All actual event
        // bytes remain on this same source and are consumed once by OkHttp.
        if (hasBom) source.skip(utf8Bom.size.toLong())
        val normalizedBody = object : ResponseBody() {
            override fun contentType() = eventStreamType
            override fun contentLength(): Long = body.contentLength().let { length ->
                if (length >= 0 && hasBom) length - utf8Bom.size else length
            }
            override fun source() = source
        }
        // Keep the original HTTP headers available for diagnostics. OkHttp's
        // EventSource validates ResponseBody.contentType(), not this header.
        return response.newBuilder().body(normalizedBody).build()
    }

    private fun hasResponsesSsePrefix(probe: okio.BufferedSource): Boolean {
        var remaining = MAX_PREFIX_BYTES
        repeat(MAX_PREFIX_LINES) {
            if (remaining <= 0) return false
            val line = try {
                probe.readUtf8LineStrict(remaining)
            } catch (_: EOFException) {
                return false
            }
            remaining -= line.toByteArray(Charsets.UTF_8).size + 1L
            when {
                line.isEmpty() || line.startsWith(':') -> Unit
                line.startsWith("id:") || line.startsWith("retry:") -> Unit
                responseEventLine.matches(line) -> return true
                line.startsWith("data:") -> {
                    val data = line.removePrefix("data:").removePrefix(" ")
                    val payload = runCatching { Json.parseToJsonElement(data) as? JsonObject }.getOrNull()
                    val type = (payload?.get("type") as? JsonPrimitive)?.contentOrNull
                    return type?.startsWith("response.") == true || type == "error"
                }
                else -> return false
            }
        }
        return false
    }
}
