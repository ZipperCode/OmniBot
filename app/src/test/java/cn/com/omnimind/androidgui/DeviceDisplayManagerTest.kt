package cn.com.omnimind.androidgui

import android.app.Application
import android.content.Context
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import org.robolectric.Shadows
import android.os.Build
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.view.Display
import android.view.Surface
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DeviceDisplayManagerTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        DeviceDisplayManager.destroyVirtualDisplay()
        DeviceDisplayManager.displayBridge = null
        DeviceDisplayManager.privilegedService = null
        DisplayInputCoordinator.resetForTesting()
        setVirtualDisplayForTesting(Display.DEFAULT_DISPLAY, null)
    }

    @After
    fun tearDown() {
        DeviceDisplayManager.destroyVirtualDisplay()
        DeviceDisplayManager.displayBridge = null
        DeviceDisplayManager.privilegedService = null
        DisplayInputCoordinator.resetForTesting()
        setVirtualDisplayForTesting(Display.DEFAULT_DISPLAY, null)
    }

    private fun setVirtualDisplayForTesting(displayId: Int, ownerId: String?) {
        val field = DeviceDisplayManager::class.java.getDeclaredField("activeVirtualDisplayOwner").apply { isAccessible = true }
        val idField = DeviceDisplayManager::class.java.getDeclaredField("activeVirtualDisplayId").apply { isAccessible = true }
        field.set(DeviceDisplayManager, ownerId)
        idField.setInt(DeviceDisplayManager, displayId)
        if (ownerId != null && displayId != Display.DEFAULT_DISPLAY) {
            DisplayInputCoordinator.registerVirtualDisplayOwnership(displayId, ownerId)
        }
    }

    @Test
    fun `presentToUser move not verified keeps display and task without destroying`() = runBlocking {
        setVirtualDisplayForTesting(3, "session-A")

        val fakeBridge = FakePrivilegedDisplayBridge(
            rootTasksResponse = """[
                {"taskId":201,"taskKind":"root_task","displayId":3,"userId":0,"packageName":"com.example.calc","childTaskIds":[201]}
            ]""",
            moveRootTaskResponse = """{"success":false,"code":"state_change_unconfirmed","taskId":201,"taskKind":"root_task","sourceDisplayId":3,"targetDisplayId":0,"verified":false}"""
        )
        DeviceDisplayManager.displayBridge = fakeBridge

        val result = DeviceDisplayManager.presentToUser(
            context = context,
            packageName = "com.example.calc",
            taskId = 201,
            sessionOwnerId = "session-A",
            leaseOwnerId = "lease-A",
        )

        assertFalse(result.success)
        assertEquals("false", result.diagnostics["verified"])
        assertEquals("state_change_unconfirmed", result.diagnostics["code"])
        assertEquals("201", result.diagnostics["taskId"])
        assertEquals(3, DeviceDisplayManager.activeVirtualDisplayId())
    }

    @Test
    fun `presentToUser with multiple candidate tasks returns ambiguous_task without destroying display`() = runBlocking {
        setVirtualDisplayForTesting(3, "session-A")

        val fakeBridge = FakePrivilegedDisplayBridge(
            rootTasksResponse = """[
                {"taskId":101,"taskKind":"root_task","displayId":3,"userId":0,"packageName":"com.example.multi","childTaskIds":[101]},
                {"taskId":102,"taskKind":"root_task","displayId":3,"userId":0,"packageName":"com.example.multi","childTaskIds":[102]}
            ]"""
        )
        DeviceDisplayManager.displayBridge = fakeBridge

        val result = DeviceDisplayManager.presentToUser(
            context = context,
            packageName = "com.example.multi",
            taskId = null,
            sessionOwnerId = "session-A",
            leaseOwnerId = "lease-A",
        )

        assertFalse(result.success)
        assertEquals("ambiguous_task", result.diagnostics["code"])
        assertEquals("3", result.diagnostics["sourceDisplayId"])
        assertEquals(3, DeviceDisplayManager.activeVirtualDisplayId())
    }

    @Test
    fun `presentToUser successful move keeps virtual display without immediate destroy`() = runBlocking {
        setVirtualDisplayForTesting(3, "session-A")

        val fakeBridge = FakePrivilegedDisplayBridge(
            rootTasksResponse = """[
                {"taskId":201,"taskKind":"root_task","displayId":3,"userId":0,"packageName":"com.example.calc","childTaskIds":[201]}
            ]""",
            moveRootTaskResponse = """{"success":true,"code":"ok","taskId":201,"taskKind":"root_task","sourceDisplayId":3,"targetDisplayId":0,"verified":true}"""
        )
        DeviceDisplayManager.displayBridge = fakeBridge

        val result = DeviceDisplayManager.presentToUser(
            context = context,
            packageName = "com.example.calc",
            taskId = 201,
            sessionOwnerId = "session-A",
            leaseOwnerId = "lease-A",
        )

        assertTrue(result.success)
        assertEquals("true", result.diagnostics["verified"])
        assertEquals("ok", result.diagnostics["code"])
        assertEquals("201", result.diagnostics["taskId"])
        assertEquals("3", result.diagnostics["sourceDisplayId"])
        assertEquals("0", result.diagnostics["targetDisplayId"])
        assertEquals(3, DeviceDisplayManager.activeVirtualDisplayId())
    }

    @Test
    fun `presentToUser guard canPresent false returns display_busy and performs no move`() = runBlocking {
        setVirtualDisplayForTesting(3, "session-A")

        val fakeBridge = FakePrivilegedDisplayBridge(
            rootTasksResponse = """[
                {"taskId":201,"taskKind":"root_task","displayId":3,"userId":0,"packageName":"com.example.calc","childTaskIds":[201]}
            ]""",
            moveRootTaskResponse = """{"success":true,"code":"ok","taskId":201,"taskKind":"root_task","sourceDisplayId":3,"targetDisplayId":0,"verified":true}"""
        )
        DeviceDisplayManager.displayBridge = fakeBridge

        val result = DeviceDisplayManager.presentToUser(
            context = context,
            packageName = "com.example.calc",
            taskId = 201,
            sessionOwnerId = "session-A",
            leaseOwnerId = "lease-A",
            canPresent = { false },
        )

        assertFalse(result.success)
        assertEquals("display_busy", result.message)
        assertEquals("display_busy", result.diagnostics["code"])
        assertEquals(0, fakeBridge.moveCalls.size)
        assertEquals(3, DeviceDisplayManager.activeVirtualDisplayId())
    }

    @Test
    fun `VirtualScreenFrameSource draws real frame with aspect ratio letterboxing and respects recycle mutual exclusion`() {
        val source = VirtualScreenFrameSource(200, 100)
        val dstBitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        dstBitmap.eraseColor(Color.BLACK)
        val canvas = Canvas(dstBitmap)
        val destination = Rect(0, 0, 100, 100)

        // 1. Initial state without bitmap: returns false safely
        assertFalse("Unstarted frame source without bitmap returns false", source.drawLatestFrame(canvas, destination, afterSequence = -1L))

        // 2. Supply a real bitmap (200x100) filled with RED into latestBitmap
        val realBitmap = Bitmap.createBitmap(200, 100, Bitmap.Config.ARGB_8888)
        realBitmap.eraseColor(Color.RED)
        val bitmapField = VirtualScreenFrameSource::class.java.getDeclaredField("latestBitmap").apply { isAccessible = true }
        bitmapField.set(source, realBitmap)

        // Draw onto 100x100 canvas: must succeed and scale letterboxed (2:1 aspect ratio inside 1:1 box)
        val drawn = source.drawLatestFrame(canvas, destination, afterSequence = -1L)
        assertTrue("Drawing valid bitmap onto canvas must return true", drawn)

        // Center pixel (50, 50) must be drawn with RED
        assertEquals(Color.RED, dstBitmap.getPixel(50, 50))
        // Top letterbox margin (50, 10) must remain untouched BLACK
        assertEquals(Color.BLACK, dstBitmap.getPixel(50, 10))

        // 3. Recycle mutual exclusion: when bitmap is recycled, drawLatestFrame returns false without crashing
        realBitmap.recycle()
        assertTrue(realBitmap.isRecycled)
        val drawnRecycled = source.drawLatestFrame(canvas, destination, afterSequence = -1L)
        assertFalse("Drawing recycled bitmap must return false safely without throwing", drawnRecycled)

        // 4. Close source: subsequent draw must return false
        source.close()
        val drawnClosed = source.drawLatestFrame(canvas, destination, afterSequence = -1L)
        assertFalse("Closed source must return false", drawnClosed)
    }

    @Test
    fun `VirtualScreenFrameSource sequence watermark prevents stale frame drawing and admits fresh frame`() {
        val source = VirtualScreenFrameSource(200, 100)
        val dstBitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        dstBitmap.eraseColor(Color.BLACK)
        val canvas = Canvas(dstBitmap)
        val destination = Rect(0, 0, 100, 100)

        val bitmapField = VirtualScreenFrameSource::class.java.getDeclaredField("latestBitmap").apply { isAccessible = true }
        val seqField = VirtualScreenFrameSource::class.java.getDeclaredField("latestFrameSequence").apply { isAccessible = true }

        // Initial sequence is 0L
        assertEquals(0L, source.currentFrameSequence())

        // Supply frame 1 (RED) at sequence 1L
        val frame1 = Bitmap.createBitmap(200, 100, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        bitmapField.set(source, frame1)
        seqField.setLong(source, 1L)
        assertEquals(1L, source.currentFrameSequence())

        // 1. Watermark is 1L (e.g. captured at unlock time). Frame 1 sequence 1L <= 1L watermark.
        // Must return false and NOT draw onto canvas (canvas stays BLACK).
        val drawnStale = source.drawLatestFrame(canvas, destination, afterSequence = 1L)
        assertFalse("Stale frame <= watermark must return false", drawnStale)
        assertEquals("Canvas must remain unwritten BLACK", Color.BLACK, dstBitmap.getPixel(50, 50))

        // 2. Normal draw with afterSequence = 0L (below current sequence 1L) succeeds
        val drawnNormal = source.drawLatestFrame(canvas, destination, afterSequence = 0L)
        assertTrue("Frame > watermark must be drawn", drawnNormal)
        assertEquals("Canvas center should be drawn RED", Color.RED, dstBitmap.getPixel(50, 50))

        // Reset canvas to black to clearly distinguish frame 2
        dstBitmap.eraseColor(Color.BLACK)
        assertEquals(Color.BLACK, dstBitmap.getPixel(50, 50))

        // Supply frame 2 (BLUE) at sequence 2L
        val frame2 = Bitmap.createBitmap(200, 100, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        frame1.recycle()
        bitmapField.set(source, frame2)
        seqField.setLong(source, 2L)
        assertEquals(2L, source.currentFrameSequence())

        // 3. With the same watermark 1L, sequence 2L > 1L must succeed and draw frame 2 (BLUE)
        val drawnFresh = source.drawLatestFrame(canvas, destination, afterSequence = 1L)
        assertTrue("Subsequent fresh frame > watermark must draw", drawnFresh)
        assertEquals("Canvas center should be drawn BLUE", Color.BLUE, dstBitmap.getPixel(50, 50))

        // 4. Closed source cannot draw even with fresh sequence
        source.close()
        val drawnClosed = source.drawLatestFrame(canvas, destination, afterSequence = 1L)
        assertFalse("Closed source must return false", drawnClosed)
    }

    @Test
    fun `DeviceDisplayManager virtualFrameSequence and drawVirtualFrame enforce session and sequence filtering`() {
        setVirtualDisplayForTesting(3, "session-A")
        val source = VirtualScreenFrameSource(200, 100)
        val frameSourceField = DeviceDisplayManager::class.java.getDeclaredField("frameSource").apply { isAccessible = true }
        frameSourceField.set(DeviceDisplayManager, source)

        val dstBitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLACK) }
        val canvas = Canvas(dstBitmap)
        val destination = Rect(0, 0, 100, 100)

        val bitmapField = VirtualScreenFrameSource::class.java.getDeclaredField("latestBitmap").apply { isAccessible = true }
        val seqField = VirtualScreenFrameSource::class.java.getDeclaredField("latestFrameSequence").apply { isAccessible = true }

        try {
            // Mismatched owner / display returns null sequence
            assertEquals(null, DeviceDisplayManager.virtualFrameSequence(3, "session-B"))
            assertEquals(null, DeviceDisplayManager.virtualFrameSequence(2, "session-A"))
            assertEquals(null, DeviceDisplayManager.virtualFrameSequence(Display.DEFAULT_DISPLAY, "session-A"))

            // Valid matching owner and display returns current sequence
            assertEquals(0L, DeviceDisplayManager.virtualFrameSequence(3, "session-A"))

            // Supply frame 1 (GREEN) at sequence 1L
            val frame1 = Bitmap.createBitmap(200, 100, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GREEN) }
            bitmapField.set(source, frame1)
            seqField.setLong(source, 1L)

            assertEquals(1L, DeviceDisplayManager.virtualFrameSequence(3, "session-A"))

            // drawVirtualFrame checks owner/display
            assertFalse(DeviceDisplayManager.drawVirtualFrame(3, "session-B", canvas, destination, afterSequence = 0L))
            assertFalse(DeviceDisplayManager.drawVirtualFrame(2, "session-A", canvas, destination, afterSequence = 0L))

            // drawVirtualFrame <= watermark (1L <= 1L) returns false without drawing
            assertFalse(DeviceDisplayManager.drawVirtualFrame(3, "session-A", canvas, destination, afterSequence = 1L))
            assertEquals(Color.BLACK, dstBitmap.getPixel(50, 50))

            // drawVirtualFrame > watermark (1L > 0L) draws frame
            assertTrue(DeviceDisplayManager.drawVirtualFrame(3, "session-A", canvas, destination, afterSequence = 0L))
            assertEquals(Color.GREEN, dstBitmap.getPixel(50, 50))
        } finally {
            frameSourceField.set(DeviceDisplayManager, null)
            source.close()
        }
    }

    @Test
    fun `launchActivity never issues force-stop and migrates existing root task between virtual displays`() {
        val stub = FakePrivilegedDisplayServiceStub(
            initialTasks = listOf(
                PrivilegedDisplayServiceStub.TaskSnapshot(
                    taskId = 55,
                    taskKind = "root_task",
                    displayId = 2,
                    userId = 0,
                    packageName = "com.example.calc",
                    childTaskIds = listOf(55),
                )
            )
        )

        val launched = stub.launchActivity(displayId = 3, packageName = "com.example.calc")
        assertTrue("Migrating single existing task between virtual displays returns true", launched)
        assertTrue("Never issues force-stop", stub.executedCommands.none { cmd -> cmd.contains("force-stop") })
    }

    @Test
    fun `launchActivity refuses to migrate task from main display to virtual display`() {
        val stub = FakePrivilegedDisplayServiceStub(
            initialTasks = listOf(
                PrivilegedDisplayServiceStub.TaskSnapshot(
                    taskId = 55,
                    taskKind = "root_task",
                    displayId = 0,
                    userId = 0,
                    packageName = "com.example.calc",
                    childTaskIds = listOf(55),
                    visible = false,
                    isFocused = false,
                )
            )
        )

        val launched = stub.launchActivity(displayId = 3, packageName = "com.example.calc")
        assertFalse("Refuses to migrate task from main display to virtual display", launched)
        assertEquals("Task remains on main display 0", 0, stub.tasksOnDisplays[0]?.single()?.displayId)
    }

    @Test
    fun `launchActivity refuses to migrate task if existing task on main display is in foreground`() {
        val stub = FakePrivilegedDisplayServiceStub(
            initialTasks = listOf(
                PrivilegedDisplayServiceStub.TaskSnapshot(
                    taskId = 55,
                    taskKind = "root_task",
                    displayId = 0,
                    userId = 0,
                    packageName = "com.example.calc",
                    childTaskIds = listOf(55),
                    visible = true,
                    isFocused = true,
                )
            )
        )

        val launched = stub.launchActivity(displayId = 3, packageName = "com.example.calc")
        assertFalse("Refuses to migrate task when in foreground on main display", launched)
        assertEquals("Task remains on main display 0", 0, stub.tasksOnDisplays[0]?.single()?.displayId)
    }


    @Test
    fun `launchActivity with multiple candidates refuses to guess and never calls force-stop`() {
        val stub = FakePrivilegedDisplayServiceStub(
            initialTasks = listOf(
                PrivilegedDisplayServiceStub.TaskSnapshot(1, "root_task", 0, 0, "com.example.multi", listOf(1)),
                PrivilegedDisplayServiceStub.TaskSnapshot(2, "root_task", 0, 0, "com.example.multi", listOf(2)),
            )
        )

        val launched = stub.launchActivity(displayId = 3, packageName = "com.example.multi")
        assertFalse("Refuses to guess ambiguous candidates", launched)
        assertTrue("Never issues force-stop", stub.executedCommands.none { cmd -> cmd.contains("force-stop") })
    }

    @Test
    fun `launchActivity with no existing task starts via am start without force-stop or CLEAR_TASK`() {
        val stub = FakePrivilegedDisplayServiceStub(initialTasks = emptyList())

        val launched = stub.launchActivity(displayId = 3, packageName = "com.example.newapp")
        assertTrue(launched)
        assertTrue("Never issues force-stop", stub.executedCommands.none { cmd -> cmd.contains("force-stop") })
        assertTrue("Never adds CLEAR_TASK", stub.executedCommands.none { cmd -> cmd.any { it.contains("CLEAR_TASK") } })
        assertTrue("Launches via am start with display", stub.executedCommands.any { cmd ->
            cmd.contains("/system/bin/am") && cmd.contains("start") && cmd.contains("3")
        })
    }

    @Test
    fun `moveRootTask validates exact childTaskIds set matching on readback`() {
        val fakeAtmServer = object {
            @Suppress("UNUSED_PARAMETER")
            fun getAllRootTaskInfosOnDisplay(displayId: Int): List<Any> = emptyList()
            @Suppress("UNUSED_PARAMETER")
            fun moveRootTaskToDisplay(taskId: Int, displayId: Int) {}
            @Suppress("UNUSED_PARAMETER")
            fun setFocusedTask(taskId: Int) {}
        }

        val getAllMethod = fakeAtmServer.javaClass.getMethod("getAllRootTaskInfosOnDisplay", Int::class.javaPrimitiveType)
        val moveMethod = fakeAtmServer.javaClass.getMethod("moveRootTaskToDisplay", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
        val focusMethod = fakeAtmServer.javaClass.getMethod("setFocusedTask", Int::class.javaPrimitiveType)

        val stub = object : PrivilegedDisplayServiceStub(allowedClientUid = 10000) {
            val tasksOnDisplays = mutableMapOf(
                3 to mutableListOf(
                    TaskSnapshot(
                        taskId = 88,
                        taskKind = "root_task",
                        displayId = 3,
                        userId = 0,
                        packageName = "com.example.calc",
                        childTaskIds = listOf(881, 882),
                    )
                ),
                0 to mutableListOf(
                    TaskSnapshot(
                        taskId = 88,
                        taskKind = "root_task",
                        displayId = 0,
                        userId = 0,
                        packageName = "com.example.calc",
                        childTaskIds = listOf(881, 882),
                    )
                ),
            )

            override fun isDisplayAllowed(displayId: Int): Boolean = true
            override fun queryRootTasksInternal(displayId: Int): List<TaskSnapshot> =
                tasksOnDisplays[displayId] ?: emptyList()
            override fun probeAtmOps(): AtmOps =
                AtmOps.Modern(fakeAtmServer, getAllMethod, moveMethod, focusMethod)
        }

        val resultStr = stub.moveRootTask(taskId = 88, sourceDisplayId = 3, targetDisplayId = 0)
        assertTrue("Move must succeed and be verified when childTaskIds match", resultStr.contains(""""verified":true"""))
        assertTrue(resultStr.contains(""""code":"ok""""))
    }

    @Test
    fun `getRootTasks extracts packageName from inherited and direct fields across root task hierarchy`() = runBlocking {
        var currentTasks: List<Any> = emptyList()

        val fakeAtmServer = object {
            @Suppress("UNUSED_PARAMETER")
            fun getAllRootTaskInfosOnDisplay(displayId: Int): List<Any> = currentTasks
            @Suppress("UNUSED_PARAMETER")
            fun moveRootTaskToDisplay(taskId: Int, displayId: Int) {}
            @Suppress("UNUSED_PARAMETER")
            fun setFocusedTask(taskId: Int) {}
        }

        val getAllMethod = fakeAtmServer.javaClass.getMethod("getAllRootTaskInfosOnDisplay", Int::class.javaPrimitiveType)
        val moveMethod = fakeAtmServer.javaClass.getMethod("moveRootTaskToDisplay", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
        val focusMethod = fakeAtmServer.javaClass.getMethod("setFocusedTask", Int::class.javaPrimitiveType)

        val stub = object : PrivilegedDisplayServiceStub(allowedClientUid = 0) {
            override fun isDisplayAllowed(displayId: Int): Boolean = true
            override fun probeAtmOps(): AtmOps =
                AtmOps.Modern(fakeAtmServer, getAllMethod, moveMethod, focusMethod)
        }

        DeviceDisplayManager.privilegedService = stub

        // 1. 继承公开字段回归：topActivity 声明在 FakeTaskInfo 基类中，RootTaskInfo 作为子类继承
        currentTasks = listOf(
            FakeRootTaskInfo(
                taskId = 221,
                displayId = 21,
                topActivity = ComponentName("com.google.android.calculator", "com.android.calculator2.Calculator"),
            )
        )
        val rootTasksInheritedTop = DeviceDisplayManager.getRootTasks(21)
        assertEquals(1, rootTasksInheritedTop.size)
        assertEquals("com.google.android.calculator", rootTasksInheritedTop[0].packageName)
        assertEquals(221, rootTasksInheritedTop[0].taskId)

        // 验证 service.getRootTasks 返回的真实 JSON 包含正确的 packageName
        val jsonStr = stub.getRootTasks(21)
        assertTrue(jsonStr.contains(""""packageName":"com.google.android.calculator""""))

        // 2. 继承公开字段：baseActivity 回退（topActivity 为空）
        currentTasks = listOf(
            FakeRootTaskInfo(
                taskId = 221,
                displayId = 21,
                topActivity = null,
                baseActivity = ComponentName("com.google.android.calculator", "com.android.calculator2.Calculator"),
            )
        )
        val rootTasksInheritedBase = DeviceDisplayManager.getRootTasks(21)
        assertEquals("com.google.android.calculator", rootTasksInheritedBase[0].packageName)

        // 3. 继承公开字段：baseIntent 回退（topActivity 与 baseActivity 为空）
        currentTasks = listOf(
            FakeRootTaskInfo(
                taskId = 221,
                displayId = 21,
                topActivity = null,
                baseActivity = null,
                baseIntent = Intent().apply {
                    component = ComponentName("com.google.android.calculator", "com.android.calculator2.Calculator")
                },
            )
        )
        val rootTasksInheritedIntent = DeviceDisplayManager.getRootTasks(21)
        assertEquals("com.google.android.calculator", rootTasksInheritedIntent[0].packageName)

        // 4. Modern RootTaskInfo 专属字段：childTaskNames 读取
        currentTasks = listOf(
            FakeRootTaskInfo(
                taskId = 221,
                displayId = 21,
                topActivity = null,
                baseActivity = null,
                baseIntent = null,
                childTaskNames = arrayOf("com.google.android.calculator/com.android.calculator2.Calculator"),
            )
        )
        val rootTasksChildNames = DeviceDisplayManager.getRootTasks(21)
        assertEquals("com.google.android.calculator", rootTasksChildNames[0].packageName)

        // 5. 直接声明字段（无继承关系），确保直接字段不回归
        currentTasks = listOf(
            FakeDirectTaskInfo(
                taskId = 223,
                displayId = 21,
                topActivity = ComponentName("com.example.direct", "com.example.direct.MainActivity"),
            )
        )
        val rootTasksDirect = DeviceDisplayManager.getRootTasks(21)
        assertEquals("com.example.direct", rootTasksDirect[0].packageName)

        // 6. 非公开字段回退：protected topActivity 在基类中，通过受保护字段逐级回退查找
        currentTasks = listOf(
            FakeNonPublicRootTaskInfo(
                topActivity = ComponentName("com.example.nonpublic", "com.example.nonpublic.MainActivity"),
            )
        )
        val rootTasksNonPublic = DeviceDisplayManager.getRootTasks(21)
        assertEquals("com.example.nonpublic", rootTasksNonPublic[0].packageName)
    }

    @Test
    fun `getRootTasks extracts packageName for legacy StackInfo with taskNames without regression`() = runBlocking {
        val legacyStack = FakeLegacyStackInfo(
            stackId = 224,
            displayId = 21,
            taskNames = arrayOf("com.example.legacy/com.example.legacy.MainActivity"),
        )

        val fakeAtmServer = object {
            fun getAllStackInfos(): List<Any> = listOf(legacyStack)
            @Suppress("UNUSED_PARAMETER")
            fun moveStackToDisplay(taskId: Int, displayId: Int) {}
            @Suppress("UNUSED_PARAMETER")
            fun setFocusedStack(taskId: Int) {}
        }

        val getAllMethod = fakeAtmServer.javaClass.getMethod("getAllStackInfos")
        val moveMethod = fakeAtmServer.javaClass.getMethod("moveStackToDisplay", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
        val focusMethod = fakeAtmServer.javaClass.getMethod("setFocusedStack", Int::class.javaPrimitiveType)

        val stub = object : PrivilegedDisplayServiceStub(allowedClientUid = 0) {
            override fun isDisplayAllowed(displayId: Int): Boolean = true
            override fun probeAtmOps(): AtmOps =
                AtmOps.Stack(fakeAtmServer, getAllMethod, moveMethod, focusMethod)
        }

        DeviceDisplayManager.privilegedService = stub

        val tasks = DeviceDisplayManager.getRootTasks(21)
        assertEquals(1, tasks.size)
        assertEquals("com.example.legacy", tasks[0].packageName)
        assertEquals(224, tasks[0].taskId)
    }

    private fun <T> anyNonNull(default: T): T {
        org.mockito.ArgumentMatchers.any<T>()
        return default
    }

    private fun registerApp(
        packageName: String,
        enabled: Boolean = true,
        suspended: Boolean = false,
        hasLauncher: Boolean = true,
    ) {
        val shadowPm = Shadows.shadowOf(context.packageManager)
        val appFlags = if (suspended) {
            ApplicationInfo.FLAG_INSTALLED or ApplicationInfo.FLAG_SUSPENDED
        } else {
            ApplicationInfo.FLAG_INSTALLED
        }
        val appInfo = ApplicationInfo().apply {
            this.packageName = packageName
            this.enabled = enabled
            this.flags = appFlags
        }
        val activities = if (hasLauncher) {
            val actInfo = ActivityInfo().apply {
                this.name = "$packageName.MainActivity"
                this.packageName = packageName
                this.applicationInfo = appInfo
                this.enabled = enabled
                this.exported = true
            }
            arrayOf(actInfo)
        } else {
            emptyArray()
        }
        val pi = PackageInfo().apply {
            this.packageName = packageName
            this.applicationInfo = appInfo
            this.activities = activities
        }
        shadowPm.installPackage(pi)
        if (hasLauncher) {
            val resolveInfo = ResolveInfo().apply {
                this.activityInfo = activities[0]
            }
            val launcherIntent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
                setPackage(packageName)
            }
            shadowPm.addResolveInfoForIntent(launcherIntent, resolveInfo)
        }
    }

    @Test
    fun `launchAppOnDisplay returns package_not_installed when package does not exist without dispatching`() = runBlocking {
        val fakeBridge = FakePrivilegedDisplayBridge()
        DeviceDisplayManager.displayBridge = fakeBridge

        val result = DeviceDisplayManager.launchAppOnDisplay(
            context = context,
            packageName = "com.uninstalled.app",
            displayId = 3,
            timeoutMs = 0L,
        )

        assertFalse("Uninstalled app must fail", result.success)
        assertEquals("package_not_installed", result.diagnostics["code"])
        assertEquals("com.uninstalled.app", result.diagnostics["packageName"])
        assertEquals("3", result.diagnostics["targetDisplayId"])
        assertEquals(0, fakeBridge.launchCalls.size)
        assertEquals(0, fakeBridge.moveCalls.size)
    }
    @Test
    fun `launchAppOnDisplay returns package_state_unavailable when getPackageInfo throws unexpected error without dispatching`() = runBlocking {
        val fakeBridge = FakePrivilegedDisplayBridge()
        DeviceDisplayManager.displayBridge = fakeBridge

        val mockPm = org.mockito.Mockito.mock(PackageManager::class.java)
        val dummyFlags = PackageManager.PackageInfoFlags.of(0)
        org.mockito.Mockito.`when`(mockPm.getPackageInfo(anyNonNull(""), anyNonNull(0)))
            .thenThrow(SecurityException("Mock security exception"))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            org.mockito.Mockito.`when`(mockPm.getPackageInfo(anyNonNull(""), anyNonNull(dummyFlags)))
                .thenThrow(SecurityException("Mock security exception"))
        }
        val throwingContext = object : android.content.ContextWrapper(context) {
            override fun getPackageManager(): PackageManager = mockPm
        }

        val result = DeviceDisplayManager.launchAppOnDisplay(
            context = throwingContext,
            packageName = "com.throwing.app",
            displayId = 3,
            timeoutMs = 0L,
        )

        assertFalse("Unexpected failure must return false", result.success)
        assertEquals("package_state_unavailable", result.diagnostics["code"])
        assertEquals("com.throwing.app", result.diagnostics["packageName"])
        assertEquals(0, fakeBridge.launchCalls.size)
        assertEquals(0, fakeBridge.moveCalls.size)
    }

    @Test
    fun `launchAppOnDisplay returns app_suspended when package is suspended without dispatching`() = runBlocking {
        val pkgName = "com.tencent.android.qqdownloader"
        registerApp(pkgName, enabled = true, suspended = true, hasLauncher = true)

        val fakeBridge = FakePrivilegedDisplayBridge()
        DeviceDisplayManager.displayBridge = fakeBridge

        val result = DeviceDisplayManager.launchAppOnDisplay(
            context = context,
            packageName = pkgName,
            displayId = 3,
            timeoutMs = 0L,
        )

        assertFalse("Suspended app must fail without dispatching", result.success)
        assertEquals("app_suspended", result.diagnostics["code"])
        assertEquals(pkgName, result.diagnostics["packageName"])
        assertEquals(0, fakeBridge.launchCalls.size)
        assertEquals(0, fakeBridge.moveCalls.size)
    }
    @Test
    fun `launchAppOnDisplay returns app_disabled when package is disabled without dispatching`() = runBlocking {
        val pkgName = "com.example.disabledapp"
        registerApp(pkgName, enabled = false, suspended = false, hasLauncher = true)

        val fakeBridge = FakePrivilegedDisplayBridge()
        DeviceDisplayManager.displayBridge = fakeBridge

        val result = DeviceDisplayManager.launchAppOnDisplay(
            context = context,
            packageName = pkgName,
            displayId = 3,
            timeoutMs = 0L,
        )

        assertFalse("Disabled app must fail without dispatching", result.success)
        assertEquals("app_disabled", result.diagnostics["code"])
        assertEquals(pkgName, result.diagnostics["packageName"])
        assertEquals(0, fakeBridge.launchCalls.size)
        assertEquals(0, fakeBridge.moveCalls.size)
    }
    @Test
    fun `launchAppOnDisplay returns package_no_launcher when package has no launcher activity without dispatching`() = runBlocking {
        val pkgName = "com.example.backgroundservice"
        registerApp(pkgName, enabled = true, suspended = false, hasLauncher = false)

        val fakeBridge = FakePrivilegedDisplayBridge()
        DeviceDisplayManager.displayBridge = fakeBridge

        val result = DeviceDisplayManager.launchAppOnDisplay(
            context = context,
            packageName = pkgName,
            displayId = 3,
            timeoutMs = 0L,
        )

        assertFalse("App without launcher must fail without dispatching", result.success)
        assertEquals("package_no_launcher", result.diagnostics["code"])
        assertEquals(pkgName, result.diagnostics["packageName"])
        assertEquals(0, fakeBridge.launchCalls.size)
        assertEquals(0, fakeBridge.moveCalls.size)
    }

    @Test
    fun `launchAppOnDisplay refuses to migrate task when package already has task on main display`() = runBlocking {
        val pkgName = "com.example.apponmain"
        registerApp(pkgName, enabled = true, suspended = false, hasLauncher = true)

        val fakeBridge = FakePrivilegedDisplayBridge(
            rootTasksByDisplay = mapOf(
                0 to """[{"taskId":88,"taskKind":"root_task","displayId":0,"userId":0,"packageName":"$pkgName","childTaskIds":[88],"visible":false,"isFocused":false}]""",
                3 to "[]",
            )
        )
        DeviceDisplayManager.displayBridge = fakeBridge

        val result = DeviceDisplayManager.launchAppOnDisplay(
            context = context,
            packageName = pkgName,
            displayId = 3,
            timeoutMs = 0L,
        )

        assertFalse("Must not migrate task from main display to virtual display", result.success)
        assertEquals("app_has_task_on_main_display", result.diagnostics["code"])
        assertEquals("88", result.diagnostics["taskId"])
        assertEquals(0, fakeBridge.launchCalls.size)
        assertEquals(0, fakeBridge.moveCalls.size)
    }

    @Test
    fun `launchAppOnDisplay fails when package not detected on target display and does not report false success`() = runBlocking {
        val pkgName = "com.example.failinglaunch"
        registerApp(pkgName, enabled = true, suspended = false, hasLauncher = true)

        val fakeBridge = FakePrivilegedDisplayBridge(rootTasksResponse = "[]")
        DeviceDisplayManager.displayBridge = fakeBridge

        val result = DeviceDisplayManager.launchAppOnDisplay(
            context = context,
            packageName = pkgName,
            displayId = 3,
            timeoutMs = 0L,
        )

        assertFalse("Must return failure when package does not appear on target display", result.success)
        assertEquals("app_not_detected_on_display", result.diagnostics["code"])
        assertEquals(1, fakeBridge.launchCalls.size)
    }

    @Test
    fun `launchAppOnDisplay fails with app_dropped_to_main_display when system drops target to main display`() = runBlocking {
        val pkgName = "com.example.droppedtomain"
        registerApp(pkgName, enabled = true, suspended = false, hasLauncher = true)

        lateinit var fakeBridge: FakePrivilegedDisplayBridge
        fakeBridge = FakePrivilegedDisplayBridge(
            rootTasksByDisplay = mapOf(0 to "[]", 3 to "[]"),
            onLaunchActivity = {
                fakeBridge.rootTasksByDisplay = mapOf(
                    0 to """[{"taskId":77,"taskKind":"root_task","displayId":0,"userId":0,"packageName":"$pkgName","childTaskIds":[77],"visible":true,"isFocused":true}]""",
                    3 to "[]",
                )
            }
        )
        DeviceDisplayManager.displayBridge = fakeBridge

        val result = DeviceDisplayManager.launchAppOnDisplay(
            context = context,
            packageName = pkgName,
            displayId = 3,
            timeoutMs = 0L,
        )

        assertFalse("Must return failure when app drops to main display", result.success)
        assertEquals("app_dropped_to_main_display", result.diagnostics["code"])
        assertEquals("0", result.diagnostics["actualDisplayId"])
        assertEquals("3", result.diagnostics["targetDisplayId"])
    }

    @Test
    fun `launchAppOnDisplay succeeds only when package is confirmed appeared on target display`() = runBlocking {
        val pkgName = "com.example.successapp"
        registerApp(pkgName, enabled = true, suspended = false, hasLauncher = true)

        val fakeBridge = FakePrivilegedDisplayBridge(
            rootTasksByDisplay = mapOf(
                0 to "[]",
                3 to """[{"taskId":99,"taskKind":"root_task","displayId":3,"userId":0,"packageName":"$pkgName","childTaskIds":[99],"visible":true,"isFocused":true}]""",
            )
        )
        DeviceDisplayManager.displayBridge = fakeBridge

        val result = DeviceDisplayManager.launchAppOnDisplay(
            context = context,
            packageName = pkgName,
            displayId = 3,
            timeoutMs = 0L,
        )

        assertTrue("Must succeed when confirmed on target display", result.success)
        assertEquals("ok", result.diagnostics["code"])
        assertEquals("open_app_dispatched_on_display:3", result.message)
    }

    @Test
    fun `launchAppOnDisplay on default display confirms package appeared and does not report false success on timeout`() = runBlocking {
        val pkgName = "com.example.mainlaunch"
        registerApp(pkgName, enabled = true, suspended = false, hasLauncher = true)

        val fakeBridge = FakePrivilegedDisplayBridge(rootTasksResponse = "[]")
        DeviceDisplayManager.displayBridge = fakeBridge

        val resultFail = DeviceDisplayManager.launchAppOnDisplay(
            context = context,
            packageName = pkgName,
            displayId = 0,
            timeoutMs = 0L,
        )

        assertFalse("Default display launch must not report false success on timeout", resultFail.success)
        assertEquals("app_not_detected_on_display", resultFail.diagnostics["code"])

        // Now simulate package appeared on default display 0
        fakeBridge.rootTasksResponse = """[
            {"taskId":101,"taskKind":"root_task","displayId":0,"userId":0,"packageName":"$pkgName","childTaskIds":[101],"visible":true,"isFocused":true}
        ]"""

        val resultSuccess = DeviceDisplayManager.launchAppOnDisplay(
            context = context,
            packageName = pkgName,
            displayId = 0,
            timeoutMs = 0L,
        )

        assertTrue("Default display launch succeeds when package confirmed on default display", resultSuccess.success)
        assertEquals("ok", resultSuccess.diagnostics["code"])
        assertEquals("open_app_dispatched", resultSuccess.message)
    }
    @Test
    fun `launchAppOnDisplay fails when package task on target display is neither visible nor focused`() = runBlocking {
        val pkgName = "com.example.dormantapp"
        registerApp(pkgName, enabled = true, suspended = false, hasLauncher = true)

        val fakeBridge = FakePrivilegedDisplayBridge(
            rootTasksByDisplay = mapOf(
                0 to "[]",
                3 to """[{"taskId":99,"taskKind":"root_task","displayId":3,"userId":0,"packageName":"$pkgName","childTaskIds":[99],"visible":false,"isFocused":false}]""",
            )
        )
        DeviceDisplayManager.displayBridge = fakeBridge

        val result = DeviceDisplayManager.launchAppOnDisplay(
            context = context,
            packageName = pkgName,
            displayId = 3,
            timeoutMs = 0L,
        )

        assertFalse("Dormant non-visible task must not report success", result.success)
        assertEquals("app_not_detected_on_display", result.diagnostics["code"])
    }

    @Test
    fun `launchAppOnDisplay does not treat unknown taskDisplay as main display success`() = runBlocking {
        val pkgName = "com.example.unknowndisplay"
        registerApp(pkgName, enabled = true, suspended = false, hasLauncher = true)

        val fakeBridge = FakePrivilegedDisplayBridge(rootTasksResponse = "[]")
        DeviceDisplayManager.displayBridge = fakeBridge

        val result = DeviceDisplayManager.launchAppOnDisplay(
            context = context,
            packageName = pkgName,
            displayId = 0,
            timeoutMs = 0L,
        )

        assertFalse("Unknown task display must never verify any display as success", result.success)
        assertEquals("app_not_detected_on_display", result.diagnostics["code"])
    }
}

private class FakePrivilegedDisplayBridge(
    var rootTasksResponse: String = "[]",
    var moveRootTaskResponse: String = """{"success":true,"code":"ok","taskId":1,"taskKind":"root_task","sourceDisplayId":3,"targetDisplayId":0,"verified":true}""",
    var rootTasksByDisplay: Map<Int, String> = emptyMap(),
    var launchActivityResult: Boolean = true,
    var onLaunchActivity: (() -> Unit)? = null,
) : PrivilegedDisplayBridge {
    val moveCalls = mutableListOf<Triple<Int, Int, Int>>()
    val launchCalls = mutableListOf<Pair<Int, String>>()

    override suspend fun createVirtualDisplay(width: Int, height: Int, dpi: Int, surface: Surface, flags: Int): Result<Int> =
        Result.success(3)

    override suspend fun releaseVirtualDisplay(displayId: Int) {}
    override suspend fun injectKeyEvent(displayId: Int, keyCode: Int): Boolean = true
    override suspend fun injectTap(displayId: Int, x: Int, y: Int): Boolean = true
    override suspend fun injectSwipe(displayId: Int, x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long): Boolean = true
    override suspend fun launchActivity(displayId: Int, packageName: String): Boolean {
        launchCalls.add(displayId to packageName)
        onLaunchActivity?.invoke()
        return launchActivityResult
    }

    override suspend fun getRootTasks(displayId: Int): String =
        rootTasksByDisplay[displayId] ?: rootTasksResponse

    override suspend fun moveRootTask(taskId: Int, sourceDisplayId: Int, targetDisplayId: Int): String {
        moveCalls.add(Triple(taskId, sourceDisplayId, targetDisplayId))
        return moveRootTaskResponse
    }
}

private class FakePrivilegedDisplayServiceStub(
    val initialTasks: List<PrivilegedDisplayServiceStub.TaskSnapshot> = emptyList(),
) : PrivilegedDisplayServiceStub(allowedClientUid = 10000) {
    val executedCommands = mutableListOf<List<String>>()
    val tasksOnDisplays = mutableMapOf<Int, MutableList<PrivilegedDisplayServiceStub.TaskSnapshot>>()

    init {
        initialTasks.forEach { task ->
            tasksOnDisplays.computeIfAbsent(task.displayId) { mutableListOf() }.add(task)
        }
    }

    override fun isDisplayAllowed(displayId: Int): Boolean = true

    override fun queryRootTasksInternal(displayId: Int): List<PrivilegedDisplayServiceStub.TaskSnapshot> =
        tasksOnDisplays[displayId] ?: emptyList()

    override fun moveRootTask(taskId: Int, sourceDisplayId: Int, targetDisplayId: Int): String {
        val sourceList = tasksOnDisplays[sourceDisplayId]
        val task = sourceList?.firstOrNull { it.taskId == taskId }
        if (task != null) {
            sourceList.remove(task)
            val moved = task.copy(displayId = targetDisplayId)
            tasksOnDisplays.computeIfAbsent(targetDisplayId) { mutableListOf() }.add(moved)
            return """{"success":true,"code":"ok","taskId":$taskId,"taskKind":"${task.taskKind}","sourceDisplayId":$sourceDisplayId,"targetDisplayId":$targetDisplayId,"verified":true}"""
        }
        return """{"success":false,"code":"task_not_found","taskId":$taskId,"taskKind":"unknown","sourceDisplayId":$sourceDisplayId,"targetDisplayId":$targetDisplayId,"verified":false}"""
    }

    override fun executeRootCommand(vararg command: String): Boolean {
        executedCommands.add(command.toList())
        return true
    }
}

// 模拟 AOSP TaskInfo 基类，topActivity/baseActivity/baseIntent 作为公开成员声明在基类中
open class FakeTaskInfo(
    @JvmField var taskId: Int = 221,
    @JvmField var displayId: Int = 21,
    @JvmField var userId: Int = 0,
    @JvmField var topActivity: ComponentName? = null,
    @JvmField var baseActivity: ComponentName? = null,
    @JvmField var baseIntent: Intent? = null,
)

// 模拟 AOSP 11+ RootTaskInfo，继承 TaskInfo，自身仅包含 childTaskIds/childTaskNames/visible/isFocused
class FakeRootTaskInfo(
    taskId: Int = 221,
    displayId: Int = 21,
    userId: Int = 0,
    topActivity: ComponentName? = null,
    baseActivity: ComponentName? = null,
    baseIntent: Intent? = null,
    @JvmField var childTaskIds: IntArray = intArrayOf(221),
    @JvmField var childTaskNames: Array<String> = emptyArray(),
    @JvmField var visible: Boolean = true,
    @JvmField var isFocused: Boolean = true,
) : FakeTaskInfo(taskId, displayId, userId, topActivity, baseActivity, baseIntent)

// 模拟非公开字段 ROM：topActivity 在基类中受保护声明
open class FakeNonPublicTaskInfo(
    @JvmField protected var topActivity: ComponentName? = null,
) {
    @JvmField var taskId: Int = 222
    @JvmField var displayId: Int = 21
    @JvmField var userId: Int = 0
}

class FakeNonPublicRootTaskInfo(
    topActivity: ComponentName? = null,
    @JvmField var childTaskIds: IntArray = intArrayOf(222),
    @JvmField var visible: Boolean = true,
    @JvmField var isFocused: Boolean = true,
) : FakeNonPublicTaskInfo(topActivity)

// 模拟直接声明字段（未通过继承），确保直接字段不回归
class FakeDirectTaskInfo(
    @JvmField var taskId: Int = 223,
    @JvmField var displayId: Int = 21,
    @JvmField var userId: Int = 0,
    @JvmField var topActivity: ComponentName? = null,
    @JvmField var childTaskIds: IntArray = intArrayOf(223),
    @JvmField var visible: Boolean = true,
    @JvmField var isFocused: Boolean = true,
)

// 模拟 Android 10 旧版 StackInfo（使用 taskNames 与 taskIds）
class FakeLegacyStackInfo(
    @JvmField var stackId: Int = 224,
    @JvmField var displayId: Int = 21,
    @JvmField var userId: Int = 0,
    @JvmField var taskIds: IntArray = intArrayOf(224),
    @JvmField var taskNames: Array<String> = arrayOf("com.example.legacy/MainActivity"),
    @JvmField var visible: Boolean = true,
    @JvmField var isFocused: Boolean = true,
)
