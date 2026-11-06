package cn.com.omnimind.bot.agent.runtime

import cn.com.omnimind.baselib.account.*
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito.*
import java.io.ByteArrayInputStream
import java.io.IOException

class NaCloudAttachmentsTest {
    @get:Rule val temp = TemporaryFolder()
    private val client = OkHttpClient.Builder().retryOnConnectionFailure(false)
        .followRedirects(false).followSslRedirects(false).build()

    @Test fun exactLimitAndBinaryContentsUploadBeforeSingleMessage() = runBlocking {
        val bytes = ByteArray(NA_ATTACHMENT_MAX_BYTES.toInt()) { (it % 251).toByte() }
        val paths = mutableListOf<String>()
        var submitted = 0
        val message = naSubmitWithAttachments("Read the file", listOf(mapOf("name" to "报告.bin")), "alice/session/turn", temp.root,
            { ByteArrayInputStream(bytes) },
            { path, file, check ->
                check()
                assertEquals(0, submitted)
                assertArrayEquals(bytes, file.readBytes())
                paths += path
            }, {}, submit = { submitted++; it })
        assertEquals(1, submitted)
        assertTrue(message.endsWith("[附件] ${paths.single()}"))
        assertTrue(paths.single().endsWith("-报告.bin"))
        assertEquals(0, temp.root.listFiles()!!.size)
    }

    @Test fun unknownOrFalseSizeCannotBypassLimitAndNoPartialBatchIsUploaded() = runBlocking {
        var uploads = 0
        var submitted = false
        val rows = listOf(mapOf("name" to "first.txt"), mapOf("name" to "large.bin", "size" to 1))
        try {
            naSubmitWithAttachments("read", rows, "turn", temp.root,
                { ByteArrayInputStream(if (it["name"] == "first.txt") byteArrayOf(1) else ByteArray(NA_ATTACHMENT_MAX_BYTES.toInt() + 1)) },
                { _, _, _ -> uploads++ }, {}, submit = { submitted = true })
            fail("oversized source accepted")
        } catch (error: IllegalArgumentException) { assertTrue(error.message!!.contains("10 MiB")) }
        assertEquals(0, uploads)
        assertFalse(submitted)
        assertEquals(0, temp.root.listFiles()!!.size)
    }

    @Test fun declaredOversizeRejectedWithoutOpeningSource() = runBlocking {
        try {
            naSubmitWithAttachments("", listOf(mapOf("size" to NA_ATTACHMENT_MAX_BYTES + 1)), "turn", temp.root,
                { error("source must not be opened") }, { _, _, _ -> error("upload") }, {}, submit = { error("submit") })
            fail("accepted")
        } catch (error: IllegalArgumentException) { assertTrue(error.message!!.contains("10 MiB")) }
    }

    @Test fun attachmentOnlyMessageAndStableSafePaths() = runBlocking {
        suspend fun send(reservation: String): String = naSubmitWithAttachments("", listOf(mapOf("name" to "../../line\nbreak.txt")), reservation, temp.root,
            { ByteArrayInputStream(byteArrayOf()) }, { _, _, _ -> }, {}, submit = { it })
        val one = send("alice/turn")
        assertEquals(one, send("alice/turn"))
        assertNotEquals(one, send("bob/turn"))
        assertTrue(one.matches(Regex("\\[附件] attachment-[a-f0-9-]{36}-line_break.txt")))
        val unicode = naAttachmentFilename("很长的文件名".repeat(30) + ".csv")
        assertTrue(unicode.toByteArray().size <= 120)
        assertTrue(unicode.endsWith(".csv"))
    }

    @Test fun uploadFailureLeavesNoMessageAndCleansLocalCopies() = runBlocking {
        var submitted = false
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(413))
            server.start()
            val connection = NaCloudConnection(server.url("/").toString().trimEnd('/'))
            try {
                naSubmitWithAttachments("read", listOf(mapOf("name" to "small.txt")), "turn", temp.root,
                    { ByteArrayInputStream("small".toByteArray()) },
                    { path, file, check -> naUploadAttachment(connection, client, path, file, check) }, {}, submit = { submitted = true })
                fail("upload failure ignored")
            } catch (error: IllegalStateException) { assertTrue(error.message!!.contains("10 MiB")) }
            assertEquals(1, server.requestCount)
            assertEquals("PUT", server.takeRequest().method)
        }
        assertFalse(submitted)
        assertEquals(0, temp.root.listFiles()!!.size)
    }

    @Test fun cancellationDuringPreparationOrAfterUploadNeverSubmits() = runBlocking {
        for (afterUpload in listOf(false, true)) {
            var uploads = 0
            var checks = 0
            try {
                naSubmitWithAttachments("read", listOf(mapOf("name" to "a.txt")), "turn", temp.root,
                    { ByteArrayInputStream(ByteArray(32000)) }, { _, _, _ -> uploads++ },
                    { checks++; if ((afterUpload && uploads == 1) || (!afterUpload && checks == 4)) throw NaAttachmentCancelled() },
                    submit = { error("cancelled prompt must not be sent") })
                fail("cancellation ignored")
            } catch (_: NaAttachmentCancelled) { }
            assertEquals(if (afterUpload) 1 else 0, uploads)
            assertEquals(0, temp.root.listFiles()!!.size)
        }
    }

    @Test fun unconfirmedSavedResponseAndLostPutResponseNeverSubmitOrReplay() = runBlocking {
        for (response in listOf(MockResponse().setBody("{}"), MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))) {
            MockWebServer().use { server ->
                server.enqueue(response)
                server.start()
                val connection = NaCloudConnection(server.url("/").toString().trimEnd('/'))
                try {
                    naSubmitWithAttachments("", listOf(mapOf("name" to "a")), "turn", temp.root,
                        { ByteArrayInputStream(byteArrayOf(1)) },
                        { path, file, check -> naUploadAttachment(connection, client, path, file, check) }, {},
                        submit = { error("unconfirmed upload must not submit") })
                    fail("unconfirmed response accepted")
                } catch (error: Exception) { assertTrue(error is IOException || error is IllegalStateException) }
                assertEquals(1, server.requestCount)
            }
        }
    }

    private class Store(var value: AccountTokens?) : AccountTokenStore {
        override fun read() = value
        override fun write(tokens: AccountTokens): Boolean { value = tokens; return true }
        override fun clear(): Boolean { value = null; return true }
    }

    @Test fun pendingBatchCleanupRunsOnLostPostWithoutReplayingOrReplacingOriginalFailure() = runBlocking {
        var uploads = 0
        var submits = 0
        var cleanups = 0
        try {
            naSubmitWithAttachments("read", listOf(mapOf("name" to "file.txt")), "turn", temp.root,
                { ByteArrayInputStream(byteArrayOf(1)) }, { _, _, _ -> uploads++ }, {},
                includeReferences = false,
                cleanup = { cleanups++; throw IOException("offline cleanup") },
            ) { text ->
                assertEquals("read", text)
                submits++
                throw IOException("lost admission response")
            }
            fail("lost response must surface")
        } catch (error: IOException) { assertEquals("lost admission response", error.message) }
        assertEquals(1, uploads); assertEquals(1, submits); assertEquals(1, cleanups)
        assertEquals(0, temp.root.listFiles()!!.size)
    }

    @Test fun managedUploadCarriesOwnerAndNeverUsesPhonePaths() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("{\"saved\":true}")); server.start()
            val file = temp.newFile().apply { writeText("one") }
            val id = "12345678-1234-1234-1234-123456789abc"
            naUploadAttachment(NaCloudConnection(server.url("/").toString().trimEnd('/')), client,
                "attachment-$id-report.txt", file, {}, mapOf("conversationId" to "conversation", "clientMessageId" to "turn", "mimeType" to "text/plain"))
            val request = server.takeRequest()
            assertEquals("/api/files/uploads/$id", request.requestUrl!!.encodedPath)
            assertEquals("conversation", request.requestUrl!!.queryParameter("conversationId"))
            assertEquals("turn", request.requestUrl!!.queryParameter("clientMessageId"))
            assertEquals("report.txt", request.requestUrl!!.queryParameter("name"))
            assertFalse(request.path!!.contains(temp.root.path))
        }
    }

    @Test fun managedUploadUsesAccountTokenAndLogoutFencesFollowingUploadsAndMessage() = runBlocking {
        val remote = mock(AccountRemoteDataSource::class.java)
        val user = AccountUser("alice", "alice@example.test", "user", "active", "", "")
        `when`(remote.getCurrentUser("account-token")).thenReturn(user)
        val account = AccountRepository(remote, Store(AccountTokens("account-token", "2099-01-01T00:00:00Z", "refresh", "2099-01-01T00:00:00Z")))
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("{\"saved\":true}"))
            server.start()
            val connection = NaCloudConnection.managed(server.url("/").newBuilder().host("127.0.0.1").build().toString(), true, account)
            try {
                naSubmitWithAttachments("read", listOf(mapOf("name" to "a"), mapOf("name" to "b")), connection.prefix + "turn", temp.root,
                    { ByteArrayInputStream(byteArrayOf(0, 127, -1)) },
                    { path, file, check -> naUploadAttachment(connection, client, path, file, check); account.logout() },
                    { account.requireSessionGeneration(connection.generation) }, submit = { error("logged out prompt must not submit") })
                fail("logout ignored")
            } catch (_: AccountNotAuthenticatedException) { }
            assertEquals(1, server.requestCount)
            val request = server.takeRequest()
            assertEquals("Bearer account-token", request.getHeader("Authorization"))
            assertTrue(request.path!!.startsWith("/v1/cloud/na/api/files/content?path=attachment-"))
            assertArrayEquals(byteArrayOf(0, 127, -1), request.body.readByteArray())
            assertEquals(0, temp.root.listFiles()!!.size)
        }
    }
}
