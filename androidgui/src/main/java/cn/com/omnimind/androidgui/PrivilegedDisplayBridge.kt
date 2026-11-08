package cn.com.omnimind.androidgui

import android.view.Surface

/**
 * Bridge for privileged display and input operations executed under UID 0 (Root) or UID 2000 (Shell).
 */
interface PrivilegedDisplayBridge {
    suspend fun createVirtualDisplay(
        width: Int,
        height: Int,
        dpi: Int,
        surface: Surface,
        flags: Int,
    ): Result<Int>

    suspend fun releaseVirtualDisplay(displayId: Int)

    suspend fun injectKeyEvent(displayId: Int, keyCode: Int): Boolean

    suspend fun injectTap(displayId: Int, x: Int, y: Int): Boolean

    suspend fun injectSwipe(displayId: Int, x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long): Boolean

    suspend fun launchActivity(displayId: Int, packageName: String): Boolean

    suspend fun getRootTasks(displayId: Int): String

    suspend fun moveRootTask(taskId: Int, sourceDisplayId: Int, targetDisplayId: Int): String
}

class BinderPrivilegedDisplayBridge(
    private val service: IPrivilegedDisplayService,
) : PrivilegedDisplayBridge {
    override suspend fun createVirtualDisplay(
        width: Int,
        height: Int,
        dpi: Int,
        surface: Surface,
        flags: Int,
    ): Result<Int> = runCatching {
        val id = service.createVirtualDisplay("OmniVirtualDisplay", width, height, dpi, surface, flags)
        if (id >= 0) id else error("createVirtualDisplay failed in privileged service: $id")
    }

    override suspend fun releaseVirtualDisplay(displayId: Int) {
        runCatching { service.releaseVirtualDisplay(displayId) }
    }

    override suspend fun injectKeyEvent(displayId: Int, keyCode: Int): Boolean =
        runCatching { service.injectKeyEvent(displayId, keyCode) }.getOrDefault(false)

    override suspend fun injectTap(displayId: Int, x: Int, y: Int): Boolean =
        runCatching { service.injectTap(displayId, x, y) }.getOrDefault(false)

    override suspend fun injectSwipe(displayId: Int, x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long): Boolean =
        runCatching { service.injectSwipe(displayId, x1, y1, x2, y2, durationMs) }.getOrDefault(false)

    override suspend fun launchActivity(displayId: Int, packageName: String): Boolean =
        runCatching { service.launchActivity(displayId, packageName) }.getOrDefault(false)

    override suspend fun getRootTasks(displayId: Int): String =
        runCatching { service.getRootTasks(displayId) }.getOrDefault("[]")

    override suspend fun moveRootTask(taskId: Int, sourceDisplayId: Int, targetDisplayId: Int): String =
        runCatching { service.moveRootTask(taskId, sourceDisplayId, targetDisplayId) }
            .getOrDefault("""{"success":false,"code":"bridge_error","taskId":$taskId,"taskKind":"unknown","sourceDisplayId":$sourceDisplayId,"targetDisplayId":$targetDisplayId,"verified":false}""")
}
