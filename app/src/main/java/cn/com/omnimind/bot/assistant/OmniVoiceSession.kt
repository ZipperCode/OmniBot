package cn.com.omnimind.bot.assistant

import android.app.KeyguardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.service.voice.VoiceInteractionSession
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CompoundButton
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import cn.com.omnimind.baselib.util.OmniLog
import cn.com.omnimind.bot.agent.AgentWorkspaceManager
import cn.com.omnimind.bot.share.SharedOpenDraftAttachment
import cn.com.omnimind.bot.share.SharedOpenDraftConflictException
import cn.com.omnimind.bot.share.SharedOpenDraftStore
import cn.com.omnimind.bot.util.TaskCompletionNavigator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

/**
 * 标准系统数字助理 VoiceInteractionSession。
 * 单次唤醒单次处理，确认后将屏幕截图与辅助结构注入现有聊天草稿。
 */
class OmniVoiceSession(context: Context) : VoiceInteractionSession(context) {

    val sessionUuid: String = UUID.randomUUID().toString()
    var showCount: Int = 0
        private set
    var initialShowTime: Long = 0L
        private set
    var isClosed: Boolean = false
        private set

    var screenshotReceived: Boolean = false
        private set
    var assistReceived: Boolean = false
        private set

    var screenshotBitmap: Bitmap? = null
        private set
    var screenshotPngBytes: ByteArray? = null
        private set
    var assistText: String = ""
        private set
    var assistHasContent: Boolean = false
        private set

    val sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var compressionJob: Job? = null
    private var extractionJob: Job? = null

    // UI 组件引用
    private var rootLayout: View? = null
    private var lockScreenNotice: TextView? = null
    private var mainPanel: LinearLayout? = null
    internal var attachScreenSwitch: Switch? = null
    private var previewContainer: LinearLayout? = null
    private var previewScreenshot: ImageView? = null
    private var previewTextView: TextView? = null
    private var conflictNotice: TextView? = null
    private var duplicateNotice: TextView? = null
    private var continueButton: Button? = null

    override fun onCreate() {
        super.onCreate()
        OmniLog.i(TAG, "[$sessionUuid] OmniVoiceSession 已创建")
    }

    override fun onCreateContentView(): View {
        val root = FrameLayout(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            setBackgroundColor(Color.parseColor("#44000000"))
            setOnClickListener {
                hide()
            }
        }

        val dp16 = dpToPx(16f)
        val dp12 = dpToPx(12f)
        val dp8 = dpToPx(8f)

        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM,
            ).apply {
                setMargins(dp12, dp12, dp12, dp12)
            }
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#F71E1E1E"))
                cornerRadius = dpToPx(16f).toFloat()
            }
            setPadding(dp16, dp16, dp16, dp16)
            setOnClickListener { /* 消费点击，防止冒泡到遮罩 */ }
        }

        // Header: 标题与关闭按钮
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        }

        val titleView = TextView(context).apply {
            text = "打开小万"
            textSize = 18f
            setTextColor(Color.WHITE)
            paint.isFakeBoldText = true
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val closeButton = TextView(context).apply {
            text = "✕"
            textSize = 18f
            setTextColor(Color.LTGRAY)
            setPadding(dp8, dp8, dp8, dp8)
            setOnClickListener {
                hide()
            }
        }

        header.addView(titleView)
        header.addView(closeButton)
        card.addView(header)

        // 锁屏提示
        val lockText = TextView(context).apply {
            text = "手机已锁定，请先解锁手机以使用屏幕助理"
            textSize = 14f
            setTextColor(Color.parseColor("#FFAA00"))
            visibility = View.GONE
            setPadding(0, dp8, 0, dp8)
        }
        lockScreenNotice = lockText
        card.addView(lockText)

        // 重复唤醒提示
        val dupText = TextView(context).apply {
            text = "请关闭后重新唤醒"
            textSize = 14f
            setTextColor(Color.parseColor("#FF5555"))
            visibility = View.GONE
            setPadding(0, dp8, 0, dp8)
        }
        duplicateNotice = dupText
        card.addView(dupText)

        // 主操作区
        val mainContent = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                setMargins(0, dp8, 0, 0)
            }
        }
        mainPanel = mainContent

        // 附带当前屏幕开关（默认关闭）
        val screenSwitch = Switch(context).apply {
            text = "附带当前屏幕"
            textSize = 15f
            setTextColor(Color.WHITE)
            isChecked = false
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dpToPx(48f),
            )
            setOnCheckedChangeListener { _: CompoundButton, isChecked: Boolean ->
                previewContainer?.visibility = if (isChecked) View.VISIBLE else View.GONE
                conflictNotice?.visibility = View.GONE
            }
        }
        attachScreenSwitch = screenSwitch
        mainContent.addView(screenSwitch)

        // 展开预览容器
        val previewLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                setMargins(0, dp8, 0, dp8)
            }
        }
        previewContainer = previewLayout

        val previewScroll = ScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dpToPx(160f),
            )
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#2A2A2A"))
                cornerRadius = dpToPx(8f).toFloat()
            }
            setPadding(dp8, dp8, dp8, dp8)
        }

        val scrollContent = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }

        val imgPreview = ImageView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dpToPx(120f),
            ).apply {
                setMargins(0, 0, 0, dp8)
            }
            scaleType = ImageView.ScaleType.FIT_CENTER
            visibility = View.GONE
        }
        previewScreenshot = imgPreview
        scrollContent.addView(imgPreview)

        val txtPreview = TextView(context).apply {
            text = "正在采集屏幕内容..."
            textSize = 12f
            setTextColor(Color.LTGRAY)
        }
        previewTextView = txtPreview
        scrollContent.addView(txtPreview)

        previewScroll.addView(scrollContent)
        previewLayout.addView(previewScroll)
        mainContent.addView(previewLayout)

        // 冲突提示
        val conflictText = TextView(context).apply {
            text = "存在未处理的分享草稿，请先在聊天中处理或关闭附带打开"
            textSize = 13f
            setTextColor(Color.parseColor("#FF5555"))
            visibility = View.GONE
            setPadding(0, dp8, 0, dp8)
        }
        conflictNotice = conflictText
        mainContent.addView(conflictText)

        // 按钮栏
        val actionRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                setMargins(0, dp12, 0, 0)
            }
        }

        val btnCancel = Button(context).apply {
            text = "关闭"
            setTextColor(Color.LTGRAY)
            setBackgroundColor(Color.TRANSPARENT)
            minHeight = dpToPx(48f)
            setOnClickListener {
                hide()
            }
        }

        val btnContinue = Button(context).apply {
            text = "继续"
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#007AFF"))
                cornerRadius = dpToPx(8f).toFloat()
            }
            minHeight = dpToPx(48f)
            setOnClickListener {
                onContinueClicked()
            }
        }
        continueButton = btnContinue

        actionRow.addView(btnCancel)
        actionRow.addView(btnContinue)
        mainContent.addView(actionRow)

        card.addView(mainContent)
        root.addView(card)

        rootLayout = root
        return root
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        showCount++
        OmniLog.i(TAG, "[$sessionUuid] onShow showCount=$showCount showFlags=$showFlags")

        if (showCount > 1) {
            // 同一对象收到重复 onShow 不再次采集或覆盖已有内容
            OmniLog.w(TAG, "[$sessionUuid] 同一对象收到重复 onShow，提示关闭后重新唤醒")
            duplicateNotice?.visibility = View.VISIBLE
            mainPanel?.visibility = View.GONE
            return
        }

        initialShowTime = System.currentTimeMillis()

        val keyguardManager = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        if (keyguardManager?.isKeyguardLocked == true) {
            OmniLog.w(TAG, "[$sessionUuid] 手机处于锁屏状态，禁止采集屏幕内容")
            lockScreenNotice?.visibility = View.VISIBLE
            mainPanel?.visibility = View.GONE
        } else {
            lockScreenNotice?.visibility = View.GONE
            mainPanel?.visibility = View.VISIBLE
        }
    }

    override fun onHandleScreenshot(screenshot: Bitmap?) {
        super.onHandleScreenshot(screenshot)
        if (isClosed || showCount > 1 || screenshotReceived) {
            OmniLog.w(TAG, "[$sessionUuid] 忽略迟到或多余的截图回调 isClosed=$isClosed showCount=$showCount")
            if (screenshot != null && !screenshot.isRecycled) {
                runCatching { screenshot.recycle() }
            }
            return
        }

        val keyguardManager = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        if (keyguardManager?.isKeyguardLocked == true) {
            OmniLog.w(TAG, "[$sessionUuid] 锁屏中，跳过截图采集")
            if (screenshot != null && !screenshot.isRecycled) {
                runCatching { screenshot.recycle() }
            }
            return
        }

        screenshotReceived = true
        OmniLog.i(TAG, "[$sessionUuid] 收到截图回调: bitmap=${screenshot != null}")

        if (screenshot != null) {
            val sourceBitmap = screenshot
            screenshotBitmap = sourceBitmap
            previewScreenshot?.setImageBitmap(sourceBitmap)
            previewScreenshot?.visibility = View.VISIBLE

            compressionJob = sessionScope.launch(Dispatchers.IO) {
                try {
                    val stream = ByteArrayOutputStream()
                    sourceBitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
                    val bytes = stream.toByteArray()
                    withContext(Dispatchers.Main) {
                        if (!isClosed && screenshotBitmap === sourceBitmap && showCount == 1 && (coroutineContext[Job]?.isActive == true)) {
                            screenshotPngBytes = bytes
                            OmniLog.i(TAG, "[$sessionUuid] PNG 原尺寸编码完成: ${bytes.size} 字节")
                        }
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    OmniLog.e(TAG, "[$sessionUuid] 压缩截图失败: ${e.message}")
                }
            }
        }
    }

    override fun onHandleAssist(state: AssistState) {
        super.onHandleAssist(state)
        if (isClosed || showCount > 1 || assistReceived) {
            OmniLog.w(TAG, "[$sessionUuid] 忽略迟到或多余的 Assist 回调")
            return
        }

        val keyguardManager = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        if (keyguardManager?.isKeyguardLocked == true) {
            OmniLog.w(TAG, "[$sessionUuid] 锁屏中，跳过 Assist 采集")
            return
        }

        assistReceived = true
        OmniLog.i(TAG, "[$sessionUuid] 收到 Assist 回调: index=${state.index}, count=${state.count}, structureNull=${state.assistStructure == null}")

        extractionJob = sessionScope.launch(Dispatchers.Default) {
            val result = AssistStructureExtractor.extract(state.assistStructure)
            if (isClosed) return@launch
            assistText = result.text
            assistHasContent = result.hasContent
            withContext(Dispatchers.Main) {
                if (isClosed) return@withContext
                previewTextView?.text = if (result.hasContent) result.text else AssistStructureExtractor.NO_CONTENT_TEXT
            }
        }
    }

    override fun onHide() {
        super.onHide()
        OmniLog.i(TAG, "[$sessionUuid] onHide，清理暂存并 finish")
        isClosed = true
        cleanupTemporaryResources()
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        OmniLog.i(TAG, "[$sessionUuid] onDestroy，清理暂存")
        isClosed = true
        cleanupTemporaryResources()
    }

    internal fun onContinueClicked() {
        if (isClosed) return
        continueButton?.isEnabled = false
        conflictNotice?.visibility = View.GONE

        sessionScope.launch {
            val attachScreen = attachScreenSwitch?.isChecked == true

            if (attachScreen) {
                // 开启附带后最多等待剩余 2 秒采集窗口
                val elapsed = System.currentTimeMillis() - initialShowTime
                val remainingMs = (2000L - elapsed).coerceAtLeast(0L)
                if (remainingMs > 0) {
                    OmniLog.i(TAG, "[$sessionUuid] 等待屏幕数据与后台处理就绪，剩余等待时间=${remainingMs}ms")
                    withTimeoutOrNull(remainingMs) {
                        // 1. 等待系统回调到达
                        while (!isClosed && (!screenshotReceived || !assistReceived)) {
                            delay(30)
                        }
                        // 2. 在剩余窗口内等待后台压缩与文本提取完成
                        compressionJob?.join()
                        extractionJob?.join()
                    }
                }
                // 超出窗口未完成的后台任务主动取消，确保丢弃晚完成数据，只采用已就绪的部分数据
                if (compressionJob?.isActive == true) {
                    compressionJob?.cancel()
                }
                if (extractionJob?.isActive == true) {
                    extractionJob?.cancel()
                }
            }
            val requestKey = UUID.randomUUID().toString()
            val attachments = mutableListOf<SharedOpenDraftAttachment>()

            if (attachScreen) {
                // 写 workspace 之前先检查 pending 冲突，若有冲突则保留两边内容，不写磁盘文件并留在 Session
                try {
                    SharedOpenDraftStore.checkPendingConflict(context, requestKey)
                } catch (e: SharedOpenDraftConflictException) {
                    OmniLog.w(TAG, "[$sessionUuid] 存储前检查发现待处理草稿冲突，保留两边内容并留在 Session: ${e.message}")
                    conflictNotice?.visibility = View.VISIBLE
                    continueButton?.isEnabled = true
                    return@launch
                }

                val workspaceDir = File(AgentWorkspaceManager.rootDirectory(context), "assistant-input/$requestKey")
                val workspaceManager = AgentWorkspaceManager(context)

                // 写入截图
                screenshotPngBytes?.let { pngBytes ->
                    try {
                        if (!workspaceDir.exists()) workspaceDir.mkdirs()
                        val screenshotFile = File(workspaceDir, "screenshot.png")
                        screenshotFile.writeBytes(pngBytes)
                        val shellPath = workspaceManager.shellPathForAndroid(screenshotFile) ?: screenshotFile.absolutePath
                        attachments.add(
                            SharedOpenDraftAttachment(
                                id = UUID.randomUUID().toString(),
                                name = "screenshot.png",
                                path = screenshotFile.absolutePath,
                                size = screenshotFile.length(),
                                mimeType = "image/png",
                                isImage = true,
                                promptPath = shellPath,
                                sendToModel = true,
                            )
                        )
                    } catch (e: Exception) {
                        OmniLog.e(TAG, "[$sessionUuid] 写入截图附件失败: ${e.message}")
                    }
                }

                // 写入文本
                if (assistHasContent && assistText.isNotBlank()) {
                    try {
                        if (!workspaceDir.exists()) workspaceDir.mkdirs()
                        val textFile = File(workspaceDir, "screen_context.txt")
                        textFile.writeText(assistText, Charsets.UTF_8)
                        val shellPath = workspaceManager.shellPathForAndroid(textFile) ?: textFile.absolutePath
                        attachments.add(
                            SharedOpenDraftAttachment(
                                id = UUID.randomUUID().toString(),
                                name = "screen_context.txt",
                                path = textFile.absolutePath,
                                size = textFile.length(),
                                mimeType = "text/plain",
                                isImage = false,
                                promptPath = shellPath,
                                sendToModel = true,
                            )
                        )
                    } catch (e: Exception) {
                        OmniLog.e(TAG, "[$sessionUuid] 写入屏幕文本附件失败: ${e.message}")
                    }
                }

                if (attachments.isNotEmpty()) {
                    try {
                        SharedOpenDraftStore.storeAssistantDraft(context, requestKey, attachments)
                    } catch (e: SharedOpenDraftConflictException) {
                        OmniLog.w(TAG, "[$sessionUuid] 存储助理草稿发生并发冲突，清理本次写入文件并留在 Session: ${e.message}")
                        runCatching { workspaceDir.deleteRecursively() }
                        conflictNotice?.visibility = View.VISIBLE
                        continueButton?.isEnabled = true
                        return@launch
                    } catch (e: Exception) {
                        OmniLog.e(TAG, "[$sessionUuid] 存储助理草稿异常: ${e.message}")
                        runCatching { workspaceDir.deleteRecursively() }
                        conflictNotice?.text = "保存草稿失败，请重试"
                        conflictNotice?.visibility = View.VISIBLE
                        continueButton?.isEnabled = true
                        return@launch
                    }

                    val route = "/home/chat?conversationId=new&mode=normal&requestKey=${Uri.encode(requestKey)}"
                    OmniLog.i(TAG, "[$sessionUuid] 导航至新对话: $route")
                    TaskCompletionNavigator.navigateToMainRoute(context, route, needClear = false)
                } else {
                    // 开启了附带当前屏幕但无任何内容可用时，直接新 Agent 对话
                    val route = "/home/chat?conversationId=new&mode=normal"
                    OmniLog.i(TAG, "[$sessionUuid] 附带内容为空，直接导航至新 Agent 对话: $route")
                    TaskCompletionNavigator.navigateToMainRoute(context, route, needClear = false)
                }
            } else {
                // 未开启附带屏幕，直接新 Agent 对话
                val route = "/home/chat?conversationId=new&mode=normal"
                OmniLog.i(TAG, "[$sessionUuid] 无屏幕附件，直接导航至新 Agent 对话: $route")
                TaskCompletionNavigator.navigateToMainRoute(context, route, needClear = false)
            }

            hide()
            finish()
        }
    }

    private fun cleanupTemporaryResources() {
        val bitmapToRecycle = screenshotBitmap
        screenshotBitmap = null
        screenshotPngBytes = null
        assistText = ""
        assistHasContent = false

        previewScreenshot?.setImageDrawable(null)

        val job = compressionJob
        job?.cancel()
        extractionJob?.cancel()
        sessionScope.cancel()

        if (bitmapToRecycle != null) {
            if (job != null && !job.isCompleted) {
                job.invokeOnCompletion {
                    Handler(Looper.getMainLooper()).post {
                        if (!bitmapToRecycle.isRecycled) {
                            runCatching { bitmapToRecycle.recycle() }
                        }
                    }
                }
            } else {
                if (!bitmapToRecycle.isRecycled) {
                    runCatching { bitmapToRecycle.recycle() }
                }
            }
        }
    }
    private fun dpToPx(dp: Float): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp,
            context.resources.displayMetrics,
        ).toInt()
    }

    companion object {
        private const val TAG = "[OmniVoiceSession]"
    }
}
