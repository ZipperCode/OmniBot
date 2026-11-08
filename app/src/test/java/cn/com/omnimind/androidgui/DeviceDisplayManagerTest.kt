package cn.com.omnimind.androidgui

import android.app.Application
import android.content.Context
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
    fun `launchActivity never issues force-stop and migrates existing root task`() {
        val stub = FakePrivilegedDisplayServiceStub(
            initialTasks = listOf(
                PrivilegedDisplayServiceStub.TaskSnapshot(
                    taskId = 55,
                    taskKind = "root_task",
                    displayId = 0,
                    userId = 0,
                    packageName = "com.example.calc",
                    childTaskIds = listOf(55),
                )
            )
        )

        val launched = stub.launchActivity(displayId = 3, packageName = "com.example.calc")
        assertTrue("Migrating single existing task returns true", launched)
        assertTrue("Never issues force-stop", stub.executedCommands.none { cmd -> cmd.contains("force-stop") })
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
}

private class FakePrivilegedDisplayBridge(
    var rootTasksResponse: String = "[]",
    var moveRootTaskResponse: String = """{"success":true,"code":"ok","taskId":1,"taskKind":"root_task","sourceDisplayId":3,"targetDisplayId":0,"verified":true}""",
) : PrivilegedDisplayBridge {
    val moveCalls = mutableListOf<Triple<Int, Int, Int>>()

    override suspend fun createVirtualDisplay(width: Int, height: Int, dpi: Int, surface: Surface, flags: Int): Result<Int> =
        Result.success(3)

    override suspend fun releaseVirtualDisplay(displayId: Int) {}
    override suspend fun injectKeyEvent(displayId: Int, keyCode: Int): Boolean = true
    override suspend fun injectTap(displayId: Int, x: Int, y: Int): Boolean = true
    override suspend fun injectSwipe(displayId: Int, x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long): Boolean = true
    override suspend fun launchActivity(displayId: Int, packageName: String): Boolean = true

    override suspend fun getRootTasks(displayId: Int): String = rootTasksResponse

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
