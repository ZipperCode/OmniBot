package cn.com.omnimind.bot.uikit

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.ResolveInfo
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import cn.com.omnimind.uikit.loader.cat.DraggableBallInstance
import cn.com.omnimind.uikit.loader.cat.DraggableBallLoader
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowApplication
import org.robolectric.shadows.ShadowLooper
import org.robolectric.shadows.ShadowPackageManager

/**
 * 悬浮宠物点击与拖动手势回归测试。
 *
 * 验证消费者可见边界行为：
 * 1. 无完成提示时普通触摸点击会通过官方 PackageManager 启动宿主应用的 Launcher 入口；
 * 2. 存在任务完成提示回调时，点击优先执行该回调动作而不启动普通入口；
 * 3. 超过 6dp 阈值的拖动手势不触发点击，不启动应用且不执行回调。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, manifest = Config.NONE)
class PetOverlayClickTest {

    private lateinit var context: Context
    private lateinit var shadowApplication: ShadowApplication
    private lateinit var shadowPackageManager: ShadowPackageManager
    private var loader: DraggableBallLoader? = null

    @Before
    fun setUp() {
        val app = RuntimeEnvironment.getApplication()
        context = app
        shadowApplication = Shadows.shadowOf(app)
        shadowPackageManager = Shadows.shadowOf(app.packageManager)
        DraggableBallInstance.destroy()
        DraggableBallInstance.initialize(context)

        // 使用真实 ShadowPackageManager 注册官方 LauncherActivity，避免 Mockito 回声测试
        registerLauncherActivity(context.packageName, "${context.packageName}.LauncherActivity")
        drainStartedActivities()
        ShadowLooper.idleMainLooper()
    }

    @After
    fun tearDown() {
        DraggableBallInstance.destroy()
        loader?.destroy()
        loader = null
        drainStartedActivities()
        ShadowLooper.idleMainLooper()
    }

    @Test
    fun 通过宠物进入应用后移除悬浮窗并清除显示状态() {
        val currentLoader = DraggableBallInstance.getInstance()!!.also { loader = it }
        currentLoader.loadBall()
        assertTrue("测试前宠物悬浮窗必须已显示", currentLoader.isAttachedToWindow)

        dispatchTap(getImageView(currentLoader))

        assertFalse("启动应用后宠物悬浮窗必须隐藏", currentLoader.isAttachedToWindow)
        assertFalse("启动应用后全局宠物状态必须为隐藏", DraggableBallInstance.isShowing())
        assertFalse(
            "启动应用后持久显示状态必须清除",
            context.getSharedPreferences("OmnibotSettings", Context.MODE_PRIVATE)
                .getBoolean("pet_overlay_visible", true)
        )
        DraggableBallInstance.loadBall()
        assertTrue("菜单重新唤起后宠物必须重新显示", DraggableBallInstance.isShowing())
    }

    @Test
    fun 应用进入前台时收起已显示的宠物() {
        val currentLoader = DraggableBallInstance.getInstance()!!.also { loader = it }
        currentLoader.loadBall()
        assertTrue("测试前宠物必须已显示", currentLoader.isAttachedToWindow)

        DraggableBallInstance.dismissForAppForeground()

        assertFalse("应用进入前台后必须收起宠物", DraggableBallInstance.isShowing())
        assertFalse(
            "应用进入前台后必须清除宠物显示状态",
            context.getSharedPreferences("OmnibotSettings", Context.MODE_PRIVATE)
                .getBoolean("pet_overlay_visible", true)
        )
    }

    @Test
    fun 外部页面临时隐藏的宠物在应用恢复时仍可恢复() {
        val currentLoader = DraggableBallInstance.getInstance()!!.also { loader = it }
        currentLoader.loadBall()
        assertTrue("测试前宠物必须已显示", currentLoader.isAttachedToWindow)
        assertTrue("测试前必须成功临时隐藏宠物", currentLoader.hideForExternalActivity())

        DraggableBallInstance.dismissForAppForeground()

        assertTrue("外部页面返回时必须恢复原宠物", currentLoader.restoreAfterExternalActivity())
        assertTrue("恢复后全局宠物状态必须显示", DraggableBallInstance.isShowing())
    }

    @Test
    fun 无完成提示时普通触摸点击启动官方Launcher入口() {
        val currentLoader = DraggableBallLoader(context).also { loader = it }
        val imageView = getImageView(currentLoader)

        dispatchTap(imageView)

        val startedIntent = shadowApplication.nextStartedActivity
        assertNotNull("普通触摸点击必须启动应用入口 Intent", startedIntent)
        val targetPkg = startedIntent?.component?.packageName ?: startedIntent?.`package`
        assertEquals("启动的 Intent 必须目标为当前宿主包名", context.packageName, targetPkg)
        assertEquals(
            "启动的 Intent 目标组件必须为已注册的 LauncherActivity",
            "${context.packageName}.LauncherActivity",
            startedIntent?.component?.className
        )
        assertEquals("启动 Intent 的 Action 必须为 ACTION_MAIN", Intent.ACTION_MAIN, startedIntent?.action)
        assertTrue("启动 Intent 必须包含 CATEGORY_LAUNCHER 类别", startedIntent?.hasCategory(Intent.CATEGORY_LAUNCHER) == true)
        assertNull("普通点击进入应用不应附带 route 参数", startedIntent?.getStringExtra("route"))
        assertFalse("普通点击进入应用不应附带 needClear 参数", startedIntent?.getBooleanExtra("needClear", false) ?: false)
    }

    @Test
    fun 存在任务完成提示时点击执行回调并收起宠物() {
        val currentLoader = DraggableBallInstance.getInstance()!!.also { loader = it }
        currentLoader.loadBall()
        assertTrue("测试前必须能显示宠物", currentLoader.isAttachedToWindow)
        context.getSharedPreferences("OmnibotSettings", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("pet_overlay_visible", true)
            .commit()
        var actionExecuted = false
        setCompletionAction(currentLoader) {
            actionExecuted = true
        }

        dispatchTap(getImageView(currentLoader))

        assertTrue("点击任务完成提示必须执行原回跳回调", actionExecuted)
        assertFalse("回跳到应用后必须收起宠物", DraggableBallInstance.isShowing())
        assertFalse(
            "回跳后必须清除宠物显示状态",
            context.getSharedPreferences("OmnibotSettings", Context.MODE_PRIVATE)
                .getBoolean("pet_overlay_visible", true)
        )
        assertNull("任务回跳点击不应启动普通应用入口", shadowApplication.nextStartedActivity)
    }

    @Test
    fun 水平移动超过6dp阈值的拖动不触发应用启动() {
        val currentLoader = DraggableBallLoader(context).also { loader = it }
        val imageView = getImageView(currentLoader)

        val density = context.resources.displayMetrics.density
        val thresholdPx = (6 * density).toInt()
        val dragDelta = thresholdPx + 20f

        dispatchDrag(imageView, startX = 100f, startY = 100f, endX = 100f + dragDelta, endY = 100f)

        assertNull("水平移动超过6dp阈值的拖动手势不应启动应用", shadowApplication.nextStartedActivity)
    }

    @Test
    fun 垂直移动超过6dp阈值的拖动不触发应用启动() {
        val currentLoader = DraggableBallLoader(context).also { loader = it }
        val imageView = getImageView(currentLoader)

        val density = context.resources.displayMetrics.density
        val thresholdPx = (6 * density).toInt()
        val dragDelta = thresholdPx + 20f

        dispatchDrag(imageView, startX = 100f, startY = 100f, endX = 100f, endY = 100f + dragDelta)

        assertNull("垂直移动超过6dp阈值的拖动手势不应启动应用", shadowApplication.nextStartedActivity)
    }

    @Test
    fun 存在任务完成提示回调时超过6dp阈值的拖动不触发回调且不启动应用() {
        val currentLoader = DraggableBallLoader(context).also { loader = it }
        var actionExecuted = false
        setCompletionAction(currentLoader) {
            actionExecuted = true
        }
        val imageView = getImageView(currentLoader)

        val density = context.resources.displayMetrics.density
        val dragDelta = (6 * density).toInt() + 20f

        dispatchDrag(imageView, startX = 100f, startY = 100f, endX = 100f + dragDelta, endY = 100f)

        assertFalse("超过阈值的拖动手势不应执行完成回调动作", actionExecuted)
        assertNull("超过阈值的拖动手势不应启动应用主入口", shadowApplication.nextStartedActivity)
        assertNotNull("未判定为点击时 completionAction 应当保持不被消费", getCompletionAction(currentLoader))
    }

    @Test
    fun 轻微移动在6dp阈值内的点击手势仍然正常触发应用启动() {
        val currentLoader = DraggableBallLoader(context).also { loader = it }
        val imageView = getImageView(currentLoader)

        // 微小晃动（例如 1px），在 6dp 阈值内应视为普通点击
        val slightJitter = 1f
        dispatchDrag(imageView, startX = 100f, startY = 100f, endX = 100f + slightJitter, endY = 100f + slightJitter)

        val startedIntent = shadowApplication.nextStartedActivity
        assertNotNull("6dp阈值内的微小位移触控必须判定为有效点击并启动应用", startedIntent)
        val targetPkg = startedIntent?.component?.packageName ?: startedIntent?.`package`
        assertEquals("启动的 Intent 必须目标为当前宿主包名", context.packageName, targetPkg)
    }

    private fun drainStartedActivities() {
        while (shadowApplication.nextStartedActivity != null) {
            // 清理已记录的启动 Intent
        }
    }

    private fun registerLauncherActivity(packageName: String, activityClassName: String) {
        val appInfo = ApplicationInfo().apply {
            this.packageName = packageName
            this.enabled = true
            this.flags = ApplicationInfo.FLAG_INSTALLED
        }
        val activityInfo = ActivityInfo().apply {
            this.name = activityClassName
            this.packageName = packageName
            this.applicationInfo = appInfo
            this.enabled = true
            this.exported = true
        }
        val resolveInfo = ResolveInfo().apply {
            this.activityInfo = activityInfo
            this.filter = IntentFilter(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            }
        }
        val packageInfo = PackageInfo().apply {
            this.packageName = packageName
            this.applicationInfo = appInfo
            this.activities = arrayOf(activityInfo)
        }
        shadowPackageManager.installPackage(packageInfo)

        val launcherIntentWithPackage = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
            `package` = packageName
        }
        shadowPackageManager.addResolveInfoForIntent(launcherIntentWithPackage, resolveInfo)

        val launcherIntentBare = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        shadowPackageManager.addResolveInfoForIntent(launcherIntentBare, resolveInfo)
    }

    private fun getImageView(loader: DraggableBallLoader): View {
        val field = DraggableBallLoader::class.java.getDeclaredField("imageView")
        field.isAccessible = true
        return field.get(loader) as View
    }

    private fun setCompletionAction(loader: DraggableBallLoader, action: (() -> Unit)?) {
        val field = DraggableBallLoader::class.java.getDeclaredField("completionAction")
        field.isAccessible = true
        field.set(loader, action)
    }

    private fun getCompletionAction(loader: DraggableBallLoader): (() -> Unit)? {
        val field = DraggableBallLoader::class.java.getDeclaredField("completionAction")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return field.get(loader) as? (() -> Unit)
    }

    private fun dispatchTap(view: View, x: Float = 100f, y: Float = 100f) {
        val downTime = SystemClock.uptimeMillis()
        val downEvent = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, x, y, 0)
        val upEvent = MotionEvent.obtain(downTime, downTime + 20, MotionEvent.ACTION_UP, x, y, 0)
        try {
            view.dispatchTouchEvent(downEvent)
            view.dispatchTouchEvent(upEvent)
        } finally {
            downEvent.recycle()
            upEvent.recycle()
        }
    }

    private fun dispatchDrag(
        view: View,
        startX: Float = 100f,
        startY: Float = 100f,
        endX: Float = 100f,
        endY: Float = 100f
    ) {
        val downTime = SystemClock.uptimeMillis()
        val downEvent = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, startX, startY, 0)
        val moveEvent = MotionEvent.obtain(downTime, downTime + 50, MotionEvent.ACTION_MOVE, endX, endY, 0)
        val upEvent = MotionEvent.obtain(downTime, downTime + 100, MotionEvent.ACTION_UP, endX, endY, 0)
        try {
            view.dispatchTouchEvent(downEvent)
            view.dispatchTouchEvent(moveEvent)
            view.dispatchTouchEvent(upEvent)
        } finally {
            downEvent.recycle()
            moveEvent.recycle()
            upEvent.recycle()
        }
    }
}
