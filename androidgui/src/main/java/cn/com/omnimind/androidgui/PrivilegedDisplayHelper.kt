package cn.com.omnimind.androidgui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.IBinder
import android.os.Looper
import android.os.Process
import java.lang.reflect.Method
import kotlin.system.exitProcess

/**
 * Standalone `app_process` root helper running as UID 0 (Root) or UID 2000 (Shell).
 *
 * 显式初始化 ActivityThread/systemContext 与 Looper，并通过一次性显式包广播 Bundle.putBinder
 * 与主应用交接 Binder，无需依赖 untrusted_app ServiceManager 注册/查找。
 */
object PrivilegedDisplayHelper {
    private const val TAG = "OmniDisplayHelper"

    @JvmStatic
    fun main(args: Array<String>) {
        if (args.isEmpty()) {
            System.err.println("Usage: PrivilegedDisplayHelper <targetPackage> <nonce> [targetUid] OR <service_name>")
            exitProcess(1)
        }

        val uid = Process.myUid()
        if (uid != 0 && uid != 2000) {
            System.err.println("Warning: PrivilegedDisplayHelper running under non-privileged UID: $uid")
        }

        // 1. 显式初始化主线程 Looper
        if (Looper.myLooper() == null) {
            Looper.prepareMainLooper()
        }

        // 2. 显式建立 ActivityThread 与 systemContext（复用 Android 官方与 Shizuku/scrcpy 上游模式）
        val systemContext = obtainSystemContext()

        if (args.size >= 2) {
            val targetPackage = args[0]
            val nonce = args[1]
            val targetUid = args.getOrNull(2)?.toIntOrNull()

            val stub = PrivilegedDisplayServiceStub(
                context = systemContext,
                allowedClientUid = targetUid,
            )

            // 3. 一次性显式广播 Bundle.putBinder 交接
            val intent = Intent(DeviceDisplayManager.ACTION_ROOT_DISPLAY_BINDER).apply {
                setPackage(targetPackage)
                putExtra(DeviceDisplayManager.EXTRA_NONCE, nonce)
                putExtra(DeviceDisplayManager.EXTRA_SERVICE_PID, Process.myPid())
                val bundle = Bundle().apply {
                    putBinder(DeviceDisplayManager.KEY_BINDER, stub)
                }
                putExtra(DeviceDisplayManager.EXTRA_BINDER_BUNDLE, bundle)
            }
            systemContext.sendBroadcast(intent)

            try {
                // 保持主线程 Looper 轮询，服务 Binder IPC 事务
                Looper.loop()
            } finally {
                stub.destroy()
                exitProcess(0)
            }
        } else {
            // 兼容原有单个参数的 ServiceManager 模式
            val serviceName = args[0]
            val stub = PrivilegedDisplayServiceStub(context = systemContext)
            val registered = registerWithServiceManager(serviceName, stub)
            if (!registered) {
                System.err.println("Failed to register $serviceName with ServiceManager")
                exitProcess(2)
            }
            try {
                Looper.loop()
            } finally {
                stub.destroy()
                exitProcess(0)
            }
        }
    }

    /**
     * 显式初始化并获取系统 Context（UID 0/2000 下可用）。
     */
    fun obtainSystemContext(): Context {
        val activityThreadClass = Class.forName("android.app.ActivityThread")
        var thread = runCatching {
            activityThreadClass.getMethod("currentActivityThread").invoke(null)
        }.getOrNull()

        if (thread == null) {
            thread = runCatching {
                activityThreadClass.getMethod("systemMain").invoke(null)
            }.getOrNull()
        }

        checkNotNull(thread) { "未能初始化或获取 ActivityThread 实例" }

        val getSystemContextMethod = activityThreadClass.getMethod("getSystemContext")
        val context = getSystemContextMethod.invoke(thread) as? Context
        checkNotNull(context) { "未能从 ActivityThread 获取 systemContext" }
        return context
    }

    private fun registerWithServiceManager(name: String, binder: IBinder): Boolean = runCatching {
        val smClass = Class.forName("android.os.ServiceManager")
        val addService: Method = smClass.getMethod("addService", String::class.java, IBinder::class.java)
        addService.invoke(null, name, binder)
        true
    }.getOrDefault(false)
}
