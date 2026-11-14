package cn.com.omnimind.bot.webchat

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ApplicationInfo
import android.content.res.AssetManager
import android.util.Base64
import cn.com.omnimind.baselib.database.Conversation
import cn.com.omnimind.baselib.database.DatabaseHelper
import cn.com.omnimind.bot.agent.AgentWorkspaceManager
import cn.com.omnimind.bot.agent.conversation.OutboxException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.lang.reflect.Field

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, manifest = Config.NONE)
class OutboxAttachmentImportTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var service: ConversationDomainService
    private var testConversationId: Long = 0L
    private lateinit var attachmentsDir: File
    private var testDataDir: File? = null

    @Before
    fun setUp() = runBlocking {
        val app: Application = RuntimeEnvironment.getApplication()
        com.rk.libcommons.application = app
        DatabaseHelper.init(app)

        val assets = Mockito.mock(AssetManager::class.java)
        Mockito.`when`(assets.open(anyString())).thenAnswer { call ->
            File("src/main/assets", call.getArgument<String>(0)).inputStream()
        }

        testDataDir = tempFolder.newFolder("app_data")
        val appInfo = ApplicationInfo(app.applicationInfo).apply {
            dataDir = testDataDir!!.absolutePath
        }

        context = object : ContextWrapper(app) {
            override fun getAssets(): AssetManager = assets
            override fun getApplicationContext(): Context = this
            override fun getApplicationInfo(): ApplicationInfo = appInfo
            override fun getFilesDir(): File = File(testDataDir, "files").apply { mkdirs() }
        }

        service = ConversationDomainService(context)
        attachmentsDir = AgentWorkspaceManager(context).attachmentsDirectory().canonicalFile

        val conversation = Conversation(
            title = "Attachment Test Chat",
            mode = "agent",
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )
        testConversationId = DatabaseHelper.insertConversation(conversation)
    }

    @After
    fun tearDown() = runBlocking {
        if (testConversationId != 0L) {
            runCatching { DatabaseHelper.deleteConversationById(testConversationId) }
        }
        // 反射关闭 DatabaseHelper 内部数据库连接并置空，隔离测试环境，不依赖 Room/AppDatabase 类
        runCatching {
            val dbField: Field = DatabaseHelper::class.java.getDeclaredField("database")
            dbField.isAccessible = true
            val dbObj = dbField.get(DatabaseHelper)
            if (dbObj != null) {
                dbObj.javaClass.getMethod("close").invoke(dbObj)
                dbField.set(DatabaseHelper, null)
            }
        }
        Unit
    }

    @Test
    fun `import dataUrl persists file with exact bytes and strips dataUrl and secret fields`() = runBlocking {
        val plainText = "Hello OmniBot outbox attachment testing!"
        val encodedBase64 = Base64.encodeToString(plainText.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        val dataUrl = "data:text/plain;base64,$encodedBase64"

        val inputAttachment = mapOf(
            "id" to "att-data-1",
            "name" to "sample.txt",
            "fileName" to "sample.txt",
            "mimeType" to "text/plain",
            "dataUrl" to dataUrl,
            // 敏感凭据/内部字段：严禁透传到导入输出
            "apiKey" to "sk-secret-key-123",
            "secret" to "confidential-token",
            "password" to "p@ssword",
            "token" to "bearer-jwt-12345"
        )

        val imported = service.importOutboxAttachments(testConversationId, listOf(inputAttachment))
        assertEquals(1, imported.size)
        val result = imported.first()

        // 1. 输出绝不携带 dataUrl 或任意 secret 字段
        assertFalse("Output must not contain dataUrl", result.containsKey("dataUrl"))
        assertFalse("Output must not contain apiKey", result.containsKey("apiKey"))
        assertFalse("Output must not contain secret", result.containsKey("secret"))
        assertFalse("Output must not contain password", result.containsKey("password"))
        assertFalse("Output must not contain token", result.containsKey("token"))

        // 2. 字段白名单正确转发
        assertEquals("att-data-1", result["id"])
        assertEquals("sample.txt", result["name"])
        assertEquals("sample.txt", result["fileName"])
        assertEquals("text/plain", result["mimeType"])

        // 3. 落盘文件校验：文件字节严格等于输入，路径位于 attachments 目录
        val filePath = result["path"]?.toString()
        assertNotNull("Path must be present", filePath)
        val file = File(filePath!!).canonicalFile
        assertTrue("File must exist on disk", file.isFile && file.exists())
        assertTrue(
            "File must be located within attachmentsDirectory: ${file.absolutePath} vs ${attachmentsDir.absolutePath}",
            file.toPath().startsWith(attachmentsDir.toPath())
        )
        val expectedLength = plainText.toByteArray(Charsets.UTF_8).size.toLong()
        assertEquals(expectedLength, result["size"])
        assertEquals(expectedLength, file.length())
        assertEquals("File bytes must equal input bytes", plainText, file.readText(Charsets.UTF_8))
    }

    @Test
    fun `import binary image dataUrl sets isImage true and preserves binary bytes`() = runBlocking {
        // 1x1 像素有效 PNG 字节
        val pngBytes = byteArrayOf(
            0x89.toByte(), 0x50.toByte(), 0x4E.toByte(), 0x47.toByte(), 0x0D.toByte(), 0x0A.toByte(), 0x1A.toByte(), 0x0A.toByte(),
            0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x0D.toByte(), 0x49.toByte(), 0x48.toByte(), 0x44.toByte(), 0x52.toByte(),
            0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x01.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x01.toByte(),
            0x08.toByte(), 0x06.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x1F.toByte(), 0x15.toByte(), 0xC4.toByte(),
            0x89.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x0A.toByte(), 0x49.toByte(), 0x44.toByte(), 0x41.toByte(),
            0x54.toByte(), 0x78.toByte(), 0x9C.toByte(), 0x63.toByte(), 0x00.toByte(), 0x01.toByte(), 0x00.toByte(), 0x00.toByte(),
            0x05.toByte(), 0x00.toByte(), 0x01.toByte(), 0x0D.toByte(), 0x0A.toByte(), 0x2D.toByte(), 0xB4.toByte(), 0x00.toByte(),
            0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x49.toByte(), 0x45.toByte(), 0x4E.toByte(), 0x44.toByte(), 0xAE.toByte(),
            0x42.toByte(), 0x60.toByte(), 0x82.toByte()
        )
        val dataUrl = "data:image/png;base64," + Base64.encodeToString(pngBytes, Base64.NO_WRAP)
        val imported = service.importOutboxAttachments(
            testConversationId,
            listOf(
                mapOf(
                    "id" to "img-1",
                    "name" to "pixel.png",
                    "mimeType" to "image/png",
                    "dataUrl" to dataUrl
                )
            )
        )
        assertEquals(1, imported.size)
        val result = imported.first()
        assertEquals(true, result["isImage"])
        assertNotNull(result["promptPath"])
        assertNotNull(result["workspacePath"])
        val savedFile = File(result["path"].toString()).canonicalFile
        assertTrue(savedFile.exists())
        assertEquals(pngBytes.size.toLong(), savedFile.length())
        assertTrue("Binary bytes must match exactly", pngBytes.contentEquals(savedFile.readBytes()))
    }

    @Test
    fun `existing persistent reference inside attachments directory can be reused without copying`() = runBlocking {
        // 先导入一个文件，使其实际持久化在 attachments 目录下
        val firstImport = service.importOutboxAttachments(
            testConversationId,
            listOf(mapOf("dataUrl" to "data:text/plain;base64,cmV1c2Vk", "name" to "first.txt"))
        )
        val existingPath = firstImport.first()["path"].toString()
        assertTrue(File(existingPath).exists())

        // Web 模式（importedOrDataOnly = true）重用持久引用：直接复用原文件路径，不产生多余拷贝
        val reusedWeb = service.importOutboxAttachments(
            testConversationId,
            listOf(mapOf("id" to "reused-web", "path" to existingPath, "name" to "reused.txt")),
            importedOrDataOnly = true
        )
        assertEquals(1, reusedWeb.size)
        val reusedPathWeb = reusedWeb.first()["path"].toString()
        assertEquals("Existing attachment path must be reused directly without duplicate copy", existingPath, reusedPathWeb)
        val reusedFileWeb = File(reusedPathWeb)
        assertTrue(reusedFileWeb.exists())
        assertEquals("reused", reusedFileWeb.readText())

        // Native 模式（importedOrDataOnly = false）重用持久引用：同样直接复用原文件路径
        val reusedNative = service.importOutboxAttachments(
            testConversationId,
            listOf(mapOf("id" to "reused-native", "path" to existingPath, "name" to "reused_native.txt")),
            importedOrDataOnly = false
        )
        assertEquals(1, reusedNative.size)
        val reusedPathNative = reusedNative.first()["path"].toString()
        assertEquals("Existing attachment path must be reused directly without duplicate copy", existingPath, reusedPathNative)
        val reusedFileNative = File(reusedPathNative)
        assertTrue(reusedFileNative.exists())
        assertEquals("reused", reusedFileNative.readText())
    }

    @Test
    fun `Web importedOrDataOnly true rejects arbitrary host paths outside attachments directory`() = runBlocking {
        // 在 attachments 目录外创建宿主敏感文件
        val hostFolder = tempFolder.newFolder("host_sensitive")
        val hostFile = File(hostFolder, "credentials.json").apply {
            writeText("{\"apiKey\":\"super-secret-host-value\"}")
        }

        // Web 端试图传入非 attachments 目录下的绝对路径
        try {
            service.importOutboxAttachments(
                testConversationId,
                listOf(mapOf("name" to "host.json", "path" to hostFile.absolutePath)),
                importedOrDataOnly = true
            )
            fail("Web import must reject files outside attachments directory")
        } catch (e: OutboxException) {
            assertEquals("outbox_attachment_unavailable", e.errorCode)
        }

        // Web 端试图传入路径穿越相对路径
        try {
            service.importOutboxAttachments(
                testConversationId,
                listOf(mapOf("path" to "../../../etc/passwd")),
                importedOrDataOnly = true
            )
            fail("Web import must reject traversal paths outside attachments directory")
        } catch (e: OutboxException) {
            assertEquals("outbox_attachment_unavailable", e.errorCode)
        }
    }

    @Test
    fun `native picker temporary file is copied into attachments and retains content after original is deleted`() = runBlocking {
        // 模拟原生选择器将文件放在临时缓存目录
        val pickerCacheDir = tempFolder.newFolder("picker_cache")
        val pickerTempFile = File(pickerCacheDir, "picker_temp_doc.pdf").apply {
            writeText("Important PDF content from native picker")
        }

        val imported = service.importOutboxAttachments(
            testConversationId,
            listOf(
                mapOf(
                    "id" to "picker-1",
                    "name" to "document.pdf",
                    "path" to pickerTempFile.absolutePath
                )
            ),
            importedOrDataOnly = false
        )
        assertEquals(1, imported.size)
        val importedPath = imported.first()["path"].toString()
        val importedFile = File(importedPath).canonicalFile

        // 原生选择器缓存临时文件被删除
        assertTrue("Picker temp file deletion must succeed", pickerTempFile.delete())
        assertFalse("Picker temp file must no longer exist", pickerTempFile.exists())

        // 验证持久化复制到 attachments 目录下的副本内容不受任何影响
        assertTrue("Imported file must exist in attachments directory", importedFile.exists())
        assertTrue(
            "Imported file must be located under attachments directory",
            importedFile.toPath().startsWith(attachmentsDir.toPath())
        )
        assertEquals("Important PDF content from native picker", importedFile.readText())
    }

    @Test
    fun `rejects empty or blank path without dataUrl`() = runBlocking {
        try {
            service.importOutboxAttachments(
                testConversationId,
                listOf(mapOf("name" to "no_path_or_data"))
            )
            fail("Expected outbox_attachment_unavailable for missing path and dataUrl")
        } catch (e: OutboxException) {
            assertEquals("outbox_attachment_unavailable", e.errorCode)
        }

        try {
            service.importOutboxAttachments(
                testConversationId,
                listOf(mapOf("path" to "   "))
            )
            fail("Expected outbox_attachment_unavailable for blank path")
        } catch (e: OutboxException) {
            assertEquals("outbox_attachment_unavailable", e.errorCode)
        }
    }

    @Test
    fun `rejects non-existent file reference`() = runBlocking {
        val nonExistentPath = File(tempFolder.root, "missing_${System.currentTimeMillis()}.txt").absolutePath
        try {
            service.importOutboxAttachments(
                testConversationId,
                listOf(mapOf("name" to "missing.txt", "path" to nonExistentPath)),
                importedOrDataOnly = false
            )
            fail("Expected outbox_attachment_unavailable for non-existent file")
        } catch (e: OutboxException) {
            assertEquals("outbox_attachment_unavailable", e.errorCode)
        }
    }

    @Test
    fun `rejects non-existent conversation`() = runBlocking {
        try {
            service.importOutboxAttachments(
                999999L,
                listOf(mapOf("dataUrl" to "data:text/plain;base64,YWJj"))
            )
            fail("Expected conversation_not_found for non-existent conversation")
        } catch (e: OutboxException) {
            assertEquals("conversation_not_found", e.errorCode)
        }
    }

    @Test
    fun `empty attachments list returns empty without touching disk`() = runBlocking {
        val result = service.importOutboxAttachments(testConversationId, emptyList())
        assertTrue("Empty input must return empty list", result.isEmpty())
    }
}
