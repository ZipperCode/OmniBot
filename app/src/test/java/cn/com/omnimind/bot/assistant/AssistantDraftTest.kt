package cn.com.omnimind.bot.assistant

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Binder
import android.os.IBinder
import android.os.Looper
import android.service.voice.VoiceInteractionSession
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import cn.com.omnimind.bot.agent.AgentWorkspaceManager
import cn.com.omnimind.bot.share.SharedOpenDraftAttachment
import cn.com.omnimind.bot.share.SharedOpenDraftConflictException
import cn.com.omnimind.bot.share.SharedOpenDraftStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.util.ReflectionHelpers
import org.robolectric.annotation.Config
import java.io.File
import java.lang.reflect.Proxy
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.concurrent.locks.LockSupport
import kotlinx.coroutines.Job

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, manifest = Config.NONE)
class AssistantDraftTest {

    private lateinit var context: Context
    private val activeSessions = mutableListOf<OmniVoiceSession>()

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        SharedOpenDraftStore.clearPending(context)
        activeSessions.clear()
    }

    @After
    fun tearDown() {
        SharedOpenDraftStore.clearPending(context)
        val assistantInputDir = File(AgentWorkspaceManager.rootDirectory(context), "assistant-input")
        if (assistantInputDir.exists()) {
            assistantInputDir.deleteRecursively()
        }
        for (session in activeSessions) {
            runCatching {
                if (!session.isClosed) {
                    session.onDestroy()
                }
            }
        }
        activeSessions.clear()
    }

    private fun createInitializedSession(): OmniVoiceSession {
        val session = OmniVoiceSession(context)
        activeSessions.add(session)
        val serviceClass = Class.forName("com.android.internal.app.IVoiceInteractionManagerService")
        val token = Binder()
        val serviceProxy = Proxy.newProxyInstance(
            serviceClass.classLoader,
            arrayOf(serviceClass)
        ) { _, method, _ ->
            when (method.name) {
                "asBinder" -> token
                else -> when (method.returnType) {
                    java.lang.Boolean.TYPE -> false
                    java.lang.Integer.TYPE -> 0
                    else -> null
                }
            }
        }
        val doCreateMethod = VoiceInteractionSession::class.java.getDeclaredMethod(
            "doCreate",
            serviceClass,
            IBinder::class.java
        )
        doCreateMethod.isAccessible = true
        doCreateMethod.invoke(session, serviceProxy, token)
        session.onCreateContentView()
        return session
    }

    private fun clickContinue(session: OmniVoiceSession) {
        val continueButtonField = OmniVoiceSession::class.java.getDeclaredField("continueButton").apply {
            isAccessible = true
        }
        val continueButton = continueButtonField.get(session) as? Button
        if (continueButton != null) {
            continueButton.performClick()
        } else {
            session.onContinueClicked()
        }
    }

    private fun createAssistState(): VoiceInteractionSession.AssistState =
        ReflectionHelpers.callConstructor(VoiceInteractionSession.AssistState::class.java)

    // --- Fake AssistNode 实现 ---
    private class SimpleTestNode(
        override val className: String? = "android.widget.TextView",
        override val text: CharSequence? = null,
        override val contentDescription: CharSequence? = null,
        override val hint: CharSequence? = null,
        override val isVisible: Boolean = true,
        override val isPassword: Boolean = false,
        override val isAssistBlocked: Boolean = false,
        val children: MutableList<AssistStructureExtractor.AssistNode> = mutableListOf(),
    ) : AssistStructureExtractor.AssistNode {
        override val childCount: Int get() = children.size
        override fun getChildAt(index: Int): AssistStructureExtractor.AssistNode? = children.getOrNull(index)
    }

    @Test
    fun testPasswordAndBlockedSubtreesAreExcluded() {
        val passwordChild = SimpleTestNode(
            className = "android.widget.TextView",
            text = "CANARY_PASSWORD_CHILD_TEXT",
            contentDescription = "CANARY_PASSWORD_CHILD_DESC",
            hint = "CANARY_PASSWORD_CHILD_HINT",
        )
        val passwordNode = SimpleTestNode(
            className = "android.widget.EditText",
            text = "CANARY_SECRET_PASSWORD_TEXT_123",
            contentDescription = "CANARY_SECRET_PASSWORD_DESC",
            hint = "CANARY_SECRET_PASSWORD_HINT",
            isPassword = true,
            children = mutableListOf(passwordChild),
        )

        val blockedChild = SimpleTestNode(
            className = "android.widget.TextView",
            text = "CANARY_BLOCKED_DESCENDANT_TEXT",
            contentDescription = "CANARY_BLOCKED_DESCENDANT_DESC",
            hint = "CANARY_BLOCKED_DESCENDANT_HINT",
        )
        val blockedNode = SimpleTestNode(
            className = "android.widget.EditText",
            text = "CANARY_BLOCKED_PIN_TEXT_456",
            contentDescription = "CANARY_BLOCKED_PIN_DESC",
            hint = "CANARY_BLOCKED_PIN_HINT",
            isAssistBlocked = true,
            children = mutableListOf(blockedChild),
        )

        val visibleSibling = SimpleTestNode(
            className = "android.widget.TextView",
            text = "公开可见内容",
            contentDescription = "公开描述",
            hint = "公开提示",
        )

        val root = SimpleTestNode(
            className = "android.widget.LinearLayout",
            children = mutableListOf(passwordNode, blockedNode, visibleSibling),
        )

        val result = AssistStructureExtractor.extractFromRoots(listOf(root))
        assertTrue("应包含有效内容", result.hasContent)
        assertFalse("不应泄露密码明文文本", result.text.contains("CANARY_SECRET_PASSWORD_TEXT_123"))
        assertFalse("不应泄露密码 desc", result.text.contains("CANARY_SECRET_PASSWORD_DESC"))
        assertFalse("不应泄露密码 hint", result.text.contains("CANARY_SECRET_PASSWORD_HINT"))
        assertFalse("不应泄露密码子节点文本", result.text.contains("CANARY_PASSWORD_CHILD_TEXT"))
        assertFalse("不应泄露密码子节点 desc", result.text.contains("CANARY_PASSWORD_CHILD_DESC"))
        assertFalse("不应泄露密码子节点 hint", result.text.contains("CANARY_PASSWORD_CHILD_HINT"))
        assertFalse("不应泄露被阻止节点文本", result.text.contains("CANARY_BLOCKED_PIN_TEXT_456"))
        assertFalse("不应泄露被阻止节点 desc", result.text.contains("CANARY_BLOCKED_PIN_DESC"))
        assertFalse("不应泄露被阻止节点 hint", result.text.contains("CANARY_BLOCKED_PIN_HINT"))
        assertFalse("不应泄露被阻止节点后代文本", result.text.contains("CANARY_BLOCKED_DESCENDANT_TEXT"))
        assertFalse("不应泄露被阻止节点后代 desc", result.text.contains("CANARY_BLOCKED_DESCENDANT_DESC"))
        assertFalse("不应泄露被阻止节点后代 hint", result.text.contains("CANARY_BLOCKED_DESCENDANT_HINT"))
        assertTrue("应保留公开内容文本", result.text.contains("公开可见内容"))
        assertTrue("应保留公开内容 desc", result.text.contains("公开描述"))
        assertTrue("应保留公开内容 hint", result.text.contains("公开提示"))
    }

    @Test
    fun testInvisibleNodesAndTheirChildrenAreSkipped() {
        val invisibleChild = SimpleTestNode(
            className = "android.widget.TextView",
            text = "隐藏子节点内容",
            isVisible = true,
        )
        val hiddenParent = SimpleTestNode(
            className = "android.widget.LinearLayout",
            text = "隐藏容器",
            isVisible = false,
            children = mutableListOf(invisibleChild),
        )
        val visibleSibling = SimpleTestNode(
            className = "android.widget.TextView",
            text = "可见同级内容",
            isVisible = true,
        )

        val result = AssistStructureExtractor.extractFromRoots(listOf(hiddenParent, visibleSibling))
        assertFalse("不应包含隐藏容器内容", result.text.contains("隐藏容器"))
        assertFalse("不应包含隐藏容器的子节点", result.text.contains("隐藏子节点内容"))
        assertTrue("应包含可见同级内容", result.text.contains("可见同级内容"))
    }

    @Test
    fun testMaxDepthLimitEnforcedWithMarker() {
        // 构建深度为 36 的链表
        val root = SimpleTestNode(text = "深度 0")
        var current = root
        for (depth in 1..35) {
            val child = SimpleTestNode(text = "深度 $depth")
            current.children.add(child)
            current = child
        }

        val result = AssistStructureExtractor.extractFromRoots(listOf(root))
        assertTrue("应包含深度限制截断标记", result.text.contains(AssistStructureExtractor.DEPTH_LIMIT_MARKER))
        assertTrue("应包含深层 32 之前的文本", result.text.contains("深度 30"))
    }

    @Test
    fun testMaxNodesLimitEnforcedWithMarker() {
        val root = SimpleTestNode(text = "FIRST_VISIBLE_NODE")
        // 添加 2500 个短子节点，前 2000 个不带长文本，后置第 2200 个带独有特征文本
        for (i in 1..2500) {
            val childText = if (i == 2200) "UNSEEN_EXCEEDED_NODE_TAIL" else null
            root.children.add(SimpleTestNode(text = childText))
        }

        val result = AssistStructureExtractor.extractFromRoots(listOf(root))
        assertTrue("超过 2000 节点必须标记为截断", result.isTruncated)
        assertTrue("首个可见节点必须被包含", result.text.contains("FIRST_VISIBLE_NODE"))
        assertTrue("节点计数不应超出边界加一", result.nodeCount <= AssistStructureExtractor.MAX_NODES + 1)
        assertTrue("节点计数应达到最大边界", result.nodeCount >= AssistStructureExtractor.MAX_NODES)
        assertFalse("超限后续节点的文本绝不能出现在结果中", result.text.contains("UNSEEN_EXCEEDED_NODE_TAIL"))
    }

    @Test
    fun testMaxUtf8BytesLimitEnforcedWithMarker() {
        val root = SimpleTestNode(text = "FIRST_CHUNK")
        val longString = "A".repeat(1000)
        for (i in 1..50) {
            root.children.add(SimpleTestNode(text = "CHUNK_$i: $longString"))
        }
        root.children.add(SimpleTestNode(text = "UNSEEN_EXCEEDED_BYTE_TAIL"))

        val result = AssistStructureExtractor.extractFromRoots(listOf(root))
        val bytes = result.text.toByteArray(StandardCharsets.UTF_8).size
        assertTrue("提取的 UTF-8 字节数不应超过 32 KiB: $bytes", bytes <= AssistStructureExtractor.MAX_UTF8_BYTES)
        assertTrue("超出字节上限必须标记为截断", result.isTruncated)
        assertTrue("首块内容必须在结果中", result.text.contains("FIRST_CHUNK"))
        assertFalse("超出字节限制后续节点的文本绝不能出现在结果中", result.text.contains("UNSEEN_EXCEEDED_BYTE_TAIL"))
    }

    @Test
    fun testEmptyScreenReturnsNoContentMessage() {
        val root = SimpleTestNode(text = null, contentDescription = null, hint = null)
        val result = AssistStructureExtractor.extractFromRoots(listOf(root))
        assertFalse("无内容时 hasContent 应为 false", result.hasContent)
        assertEquals(AssistStructureExtractor.NO_CONTENT_TEXT, result.text)
    }

    // --- SharedOpenDraftStore 冲突与持久化测试 ---

    @Test
    fun testStoreAssistantDraftSavesSuccessfully() {
        val attachment = SharedOpenDraftAttachment(
            id = "att-1",
            name = "screenshot.png",
            path = "/data/data/test/screenshot.png",
            size = 1024L,
            mimeType = "image/png",
            isImage = true,
            promptPath = "/workspace/screenshot.png",
            sendToModel = true,
        )

        val draft = SharedOpenDraftStore.storeAssistantDraft(
            context = context,
            requestKey = "req-100",
            attachments = listOf(attachment),
        )

        assertEquals("req-100", draft.requestKey)
        val pending = SharedOpenDraftStore.getPending(context)
        assertNotNull(pending)
        assertEquals("req-100", pending?.get("requestKey"))
    }

    @Test
    fun testStoreAssistantDraftConflictDoesNotOverwriteExistingPendingDraft() {
        // 先存入第一个草稿
        SharedOpenDraftStore.storeAssistantDraft(
            context = context,
            requestKey = "existing-key-1",
            attachments = emptyList(),
        )

        // 尝试用不同 key 写入第二个草稿
        try {
            SharedOpenDraftStore.storeAssistantDraft(
                context = context,
                requestKey = "incoming-key-2",
                attachments = emptyList(),
            )
            fail("不同 requestKey 应抛出 SharedOpenDraftConflictException")
        } catch (e: SharedOpenDraftConflictException) {
            assertEquals("existing-key-1", e.existingRequestKey)
            assertEquals("incoming-key-2", e.incomingRequestKey)
        }

        // 验证原有草稿未被覆盖
        val pending = SharedOpenDraftStore.getPending(context)
        assertEquals("existing-key-1", pending?.get("requestKey"))
    }

    @Test
    fun testStoreAssistantDraftIdempotentForSameKey() {
        SharedOpenDraftStore.storeAssistantDraft(
            context = context,
            requestKey = "same-key",
            attachments = emptyList(),
        )

        // 相同 key 重复提交不产生冲突
        val draft = SharedOpenDraftStore.storeAssistantDraft(
            context = context,
            requestKey = "same-key",
            attachments = emptyList(),
        )
        assertEquals("same-key", draft.requestKey)
    }

    @Test
    fun testCheckPendingConflictPassesWhenEmptyOrSameKey() {
        // 空 pending 时不抛异常
        SharedOpenDraftStore.checkPendingConflict(context, "new-key")

        // 写入一个 pending
        SharedOpenDraftStore.storeAssistantDraft(context, "new-key", emptyList())

        // 相同 key 检查通过
        SharedOpenDraftStore.checkPendingConflict(context, "new-key")

        // 不同 key 检查必须抛出异常
        try {
            SharedOpenDraftStore.checkPendingConflict(context, "another-key")
            fail("不同 key 应抛出 SharedOpenDraftConflictException")
        } catch (e: SharedOpenDraftConflictException) {
            assertEquals("new-key", e.existingRequestKey)
            assertEquals("another-key", e.incomingRequestKey)
        }
    }

    // --- OmniVoiceSession 状态与生命周期行为测试 ---

    @Test
    fun testDuplicateOnShowDoesNotCollectOrOverwrite() {
        val session = createInitializedSession()
        session.onShow(null, 0)
        assertEquals(1, session.showCount)

        // 第二次 onShow
        session.onShow(null, 0)
        assertEquals(2, session.showCount)

        // 收到截图回调，因重复唤醒应直接忽略并安全 recycle
        val bitmap = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888)
        session.onHandleScreenshot(bitmap)
        assertFalse("重复 onShow 后的截图回调应被丢弃", session.screenshotReceived)
        assertNull("重复唤醒时不应生成 pending 草稿", SharedOpenDraftStore.getPending(context))
        assertTrue("被丢弃的截图应被 recycle 释放", bitmap.isRecycled)
    }

    @Test
    fun testLateCallbacksIgnoredAfterHide() {
        val session = createInitializedSession()
        session.onShow(null, 0)

        // 用户或系统关闭 session
        session.onHide()
        assertTrue("onHide 后 session 应标记为关闭", session.isClosed)

        // 迟到的截图回调
        val bitmap = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888)
        session.onHandleScreenshot(bitmap)
        assertFalse("关闭后的迟到截图回调应被忽略", session.screenshotReceived)
        assertNull("关闭后不应保留迟到截图", session.screenshotBitmap)
        assertNull("关闭后不应生成截图字节", session.screenshotPngBytes)

        // 迟到的 assist 回调
        session.onHandleAssist(createAssistState())
        assertFalse("关闭后的迟到 assist 回调应被忽略", session.assistReceived)
        assertTrue("关闭后 assistText 应为空", session.assistText.isEmpty())

        // 确认迟到回调零新增
        assertNull("关闭后的迟到回调不得新增 pending 草稿", SharedOpenDraftStore.getPending(context))
        assertTrue("迟到截图 bitmap 必须被 recycle 释放", bitmap.isRecycled)
    }

    @Test
    fun testLateScreenshotBitmapRecycledAfterHide() {
        val session = createInitializedSession()
        session.onShow(null, 0)
        session.onHide()

        val bitmap = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888)
        session.onHandleScreenshot(bitmap)
        assertTrue("关闭后收到的 late 截图 bitmap 应被安全 recycle", bitmap.isRecycled)
        assertNull("关闭后的迟到截图不得生成 pending 草稿", SharedOpenDraftStore.getPending(context))
    }

    @Test
    fun testInFlightCompressionDoesNotResurrectClosedSessionDataAndRecyclesAfterJobCompletion() {
        val session = createInitializedSession()
        session.onShow(null, 0)

        val bitmap = Bitmap.createBitmap(20, 20, Bitmap.Config.ARGB_8888)
        bitmap.setPixel(0, 0, Color.BLUE)

        // 收到截图，后台 compressionJob 启动
        session.onHandleScreenshot(bitmap)
        assertTrue("已接收截图回调", session.screenshotReceived)

        // 立即关闭 session（模拟用户在压缩完成前关闭）
        session.onHide()
        assertTrue("session 应标记为关闭", session.isClosed)
        assertNull("关闭后 screenshotBitmap 必须清空", session.screenshotBitmap)
        assertNull("关闭后 screenshotPngBytes 必须为 null", session.screenshotPngBytes)

        // 驱动主线程 Looper 等待后台压缩完成及 invokeOnCompletion 回调执行
        val waitDeadline = System.currentTimeMillis() + 4000L
        val compressionJobField = OmniVoiceSession::class.java.getDeclaredField("compressionJob").apply { isAccessible = true }
        while ((compressionJobField.get(session) as? Job)?.isCompleted != true && System.currentTimeMillis() < waitDeadline) {
            shadowOf(Looper.getMainLooper()).idle()
            LockSupport.parkNanos(10_000_000L)
        }
        shadowOf(Looper.getMainLooper()).idle()

        // 验证：即使后台 compress 完成，已关闭的 session 绝不能复活 screenshotPngBytes
        assertNull("闭合 session 绝不能被后台压缩任务写回 screenshotPngBytes 复活数据", session.screenshotPngBytes)
        // 验证：bitmap 必须在任务完成后被安全 recycle
        assertTrue("源 Bitmap 必须在压缩任务完成后被安全 recycle 释放", bitmap.isRecycled)
    }

    @Test
    fun testNullScreenshotIsHandledGracefully() {
        val session = createInitializedSession()
        session.onShow(null, 0)

        // 系统因 FLAG_SECURE 等返回 null 截图
        session.onHandleScreenshot(null)
        assertTrue("null 截图仍应标记为已接收回调", session.screenshotReceived)
        assertNull("null 截图时 screenshotBitmap 必须为 null", session.screenshotBitmap)
        assertNull("null 截图时 screenshotPngBytes 必须为 null", session.screenshotPngBytes)
    }

    @Test
    fun testHideDiscardsInFlightExtraction() {
        val session = createInitializedSession()
        session.onShow(null, 0)

        // 隐藏 session 必须清理所有后台任务并 finish
        session.onHide()
        assertTrue("onHide 后 session 应标记为关闭", session.isClosed)
        assertTrue("隐藏后 assistText 必须为空", session.assistText.isEmpty())
        assertFalse("隐藏后 assistHasContent 必须为 false", session.assistHasContent)
        assertNull("未移交时 pending 草稿必须为空", SharedOpenDraftStore.getPending(context))
    }

    @Test
    fun testContinueCreatesConsumerDraftWithAttachments() {
        val session = createInitializedSession()
        session.onShow(null, 0)

        // 提供有效截图并真实等待后台压缩完成
        val bitmap = Bitmap.createBitmap(24, 32, Bitmap.Config.ARGB_8888)
        bitmap.setPixel(0, 0, Color.RED)
        bitmap.setPixel(12, 16, Color.GREEN)
        session.onHandleScreenshot(bitmap)

        val compressionJobField = OmniVoiceSession::class.java.getDeclaredField("compressionJob").apply { isAccessible = true }
        val extractionJobField = OmniVoiceSession::class.java.getDeclaredField("extractionJob").apply { isAccessible = true }
        val waitDeadline = System.currentTimeMillis() + 4000L
        while ((session.screenshotPngBytes == null ||
                (compressionJobField.get(session) as? Job)?.isActive == true ||
                (extractionJobField.get(session) as? Job)?.isActive == true) &&
            System.currentTimeMillis() < waitDeadline
        ) {
            shadowOf(Looper.getMainLooper()).idle()
            LockSupport.parkNanos(10_000_000L)
        }
        assertNotNull("截图 PNG 压缩必须就绪", session.screenshotPngBytes)

        // 开启附带屏幕（直接操作既有 UI Switch，避免重复调用 onCreateContentView 覆写状态）
        val attachSwitch = session.attachScreenSwitch
        assertNotNull("附带屏幕 Switch 必须存在", attachSwitch)
        attachSwitch!!.isChecked = true

        // 点击确认按钮触发生效
        clickContinue(session)

        // 驱动主线程 Looper 等待提交完成
        val submitDeadline = System.currentTimeMillis() + 4000L
        while (SharedOpenDraftStore.getPending(context) == null && System.currentTimeMillis() < submitDeadline) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50))
            shadowOf(Looper.getMainLooper()).idle()
            LockSupport.parkNanos(10_000_000L)
        }
        shadowOf(Looper.getMainLooper()).idle()

        // 消费者断言：从 SharedOpenDraftStore 读取草稿实体与真实磁盘文件
        val pending = SharedOpenDraftStore.getPending(context)
        assertNotNull("确认后必须在 SharedOpenDraftStore 生成 pending 草稿", pending)

        @Suppress("UNCHECKED_CAST")
        val attachments = pending?.get("attachments") as? List<Map<String, Any?>>
        assertNotNull("附件列表必须存在", attachments)
        assertTrue("必须包含截图附件", attachments!!.isNotEmpty())

        val screenshotAtt = attachments.firstOrNull { it["name"] == "screenshot.png" }
        assertNotNull("必须包含名为 screenshot.png 的附件", screenshotAtt)
        assertEquals(true, screenshotAtt?.get("isImage"))
        assertEquals("image/png", screenshotAtt?.get("mimeType"))
        assertEquals(true, screenshotAtt?.get("sendToModel"))

        val diskPath = screenshotAtt?.get("path") as? String
        assertNotNull("文件磁盘路径必须存在", diskPath)
        val diskFile = File(diskPath!!)
        val workspaceRoot = AgentWorkspaceManager.rootDirectory(context).canonicalFile
        assertTrue("附件路径必须位于工作区目录内", diskFile.canonicalFile.startsWith(workspaceRoot))
        assertEquals(true, screenshotAtt?.get("sendToModel"))

        // 真实解码生成的 PNG 截图并断言源 Bitmap 真实宽高与像素颜色
        val decodedBitmap = BitmapFactory.decodeFile(diskFile.absolutePath)
        assertNotNull("磁盘截图必须可真实解码为 Bitmap", decodedBitmap)
        assertEquals(24, decodedBitmap.width)
        assertEquals(32, decodedBitmap.height)
        assertEquals(Color.RED, decodedBitmap.getPixel(0, 0))
        assertEquals(Color.GREEN, decodedBitmap.getPixel(12, 16))
    }

    @Test
    fun testSwitchUncheckedProducesNoDiskAttachments() {
        val session = createInitializedSession()
        session.onShow(null, 0)

        val bitmap = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888)
        session.onHandleScreenshot(bitmap)

        val compressDeadline = System.currentTimeMillis() + 3000L
        while (session.screenshotPngBytes == null && System.currentTimeMillis() < compressDeadline) {
            shadowOf(Looper.getMainLooper()).idle()
            LockSupport.parkNanos(10_000_000L)
        }

        // 未勾选附带当前屏幕
        session.attachScreenSwitch?.isChecked = false

        clickContinue(session)

        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50))
        shadowOf(Looper.getMainLooper()).idle()

        // 未附带内容不生成 pending 草稿
        val pending = SharedOpenDraftStore.getPending(context)
        assertNull("未勾选附带时不应创建 pending 附件草稿", pending)
    }
}
