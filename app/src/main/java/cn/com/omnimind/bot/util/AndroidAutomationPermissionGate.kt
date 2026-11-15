package cn.com.omnimind.bot.util

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import cn.com.omnimind.baselib.util.exception.PermissionException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive

internal data class AndroidAutomationPermissionCheck(
    val missingIds: List<String>,
    val context: Context,
) {
    val granted: Boolean
        get() = missingIds.isEmpty()

    fun getDisplayNames(): List<String> = missingIds.map { id ->
        when (id) {
            AndroidAutomationPermissionGate.ACCESSIBILITY -> context.getString(cn.com.omnimind.bot.R.string.permission_accessibility)
            AndroidAutomationPermissionGate.OVERLAY -> context.getString(cn.com.omnimind.bot.R.string.permission_overlay)
            else -> id
        }
    }

    val errorCode: String
        get() = if (AndroidAutomationPermissionGate.ACCESSIBILITY in missingIds) {
            "OOB_ACCESSIBILITY_REQUIRED"
        } else {
            "OOB_PERMISSION_REQUIRED"
        }

    fun getMessage(): String = when {
        AndroidAutomationPermissionGate.ACCESSIBILITY in missingIds &&
            AndroidAutomationPermissionGate.OVERLAY in missingIds ->
            context.getString(cn.com.omnimind.bot.R.string.permission_automation_both_required)
        AndroidAutomationPermissionGate.ACCESSIBILITY in missingIds ->
            context.getString(cn.com.omnimind.bot.R.string.permission_automation_accessibility_required)
        AndroidAutomationPermissionGate.OVERLAY in missingIds ->
            context.getString(cn.com.omnimind.bot.R.string.permission_automation_overlay_required)
        else -> ""
    }

    fun requireGranted() {
        if (!granted) throw PermissionException(getMessage())
    }
}

internal object AndroidAutomationPermissionGate {
    const val ACCESSIBILITY = "accessibility"
    const val OVERLAY = "overlay"

    fun check(context: Context): AndroidAutomationPermissionCheck = evaluate(
        context = context,
        accessibilityEnabled = isAccessibilityEnabled(context),
        overlayEnabled = Settings.canDrawOverlays(context),
    )

    private fun isAccessibilityEnabled(context: Context): Boolean {
        val expected = ComponentName(context.packageName, "cn.com.omnimind.accessibility.service.AssistsService")
        return Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ).orEmpty().split(':').any { ComponentName.unflattenFromString(it.trim()) == expected }
    }


    internal fun evaluate(
        context: Context,
        accessibilityEnabled: Boolean,
        overlayEnabled: Boolean,
    ): AndroidAutomationPermissionCheck = AndroidAutomationPermissionCheck(
        missingIds = buildList {
            if (!accessibilityEnabled) add(ACCESSIBILITY)
            if (!overlayEnabled) add(OVERLAY)
        },
        context = context,
    )

    /** 缺权限时挂起原工具调用，授权完成后继续；停止沿调用协程取消，不创建独立任务。 */
    suspend fun awaitPermissions(
        context: Context,
        checkStop: () -> Unit,
        onMissing: suspend (AndroidAutomationPermissionCheck) -> Unit,
    ) {
        currentCoroutineContext().ensureActive()
        checkStop()
        var permission = check(context)
        if (!permission.granted) {
            onMissing(permission)
            while (!permission.granted) {
                delay(500L)
                checkStop()
                permission = check(context)
            }
        }
        currentCoroutineContext().ensureActive()
        checkStop()
    }
}
