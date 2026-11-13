package cn.com.omnimind.androidgui

import android.app.Application
import android.graphics.Rect
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import java.io.ByteArrayOutputStream
import cn.com.omnimind.baselib.runlog.Action
import cn.com.omnimind.baselib.runlog.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import android.content.Context
import android.view.Display
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.robolectric.RuntimeEnvironment
import java.lang.reflect.Proxy

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, manifest = Config.NONE)
class AndroidGuiSafetyGuardRobolectricTest {

    private val displaySize = 1080 to 2400

    @Test
    fun negativeBoundsInXmlAreParsedCorrectly() {
        val xmlWithNegative = """
            <hierarchy>
              <node id="drawer" bounds="[-500,-100][200,800]" visible-to-user="true" />
            </hierarchy>
        """.trimIndent()
        val nodes = AndroidGuiSafetyGuard.flattenNodes(AndroidGuiSafetyGuard.parseHierarchy(xmlWithNegative))
        assertEquals(1, nodes.size)
        assertEquals(-500, nodes[0].bounds.left)
        assertEquals(-100, nodes[0].bounds.top)
        assertEquals(200, nodes[0].bounds.right)
        assertEquals(800, nodes[0].bounds.bottom)
    }

    @Test
    fun treeLacksUsableTextDetectsPresenceOfAccessibleText() {
        val noTextXml = """
            <hierarchy>
              <node id="0" window-id="1" class="android.widget.FrameLayout" bounds="[0,0][1080,2400]">
                <node id="1" window-id="1" class="android.view.View" bounds="[100,100][300,300]" />
              </node>
            </hierarchy>
        """.trimIndent()
        assertTrue(AndroidGuiSafetyGuard.treeLacksUsableText(noTextXml))

        val withTextXml = """
            <hierarchy>
              <node id="0" window-id="1" class="android.widget.FrameLayout" bounds="[0,0][1080,2400]">
                <node id="1" window-id="1" class="android.widget.Button" text="确定" bounds="[100,100][300,200]" />
              </node>
            </hierarchy>
        """.trimIndent()
        assertEquals(false, AndroidGuiSafetyGuard.treeLacksUsableText(withTextXml))
    }

    @Test
    fun singleButtonInsideRootContainerDoesNotTriggerFalseAmbiguity() {
        // 普通结构：全屏根容器 FrameLayout -> 唯一文字登录按钮
        val xml = """
            <hierarchy>
              <node id="root" bounds="[0,0][1080,2400]" visible-to-user="true">
                <node id="btn" text="登录" bounds="[100,500][400,600]" visible-to-user="true" clickable="true" />
              </node>
            </hierarchy>
        """.trimIndent()

        val baseline = AndroidGuiObservationBaseline(
            observationId = "obs_1",
            displayId = 0,
            frameSequence = 1L,
            stateId = "state_1",
            packageName = "com.app",
            activityName = "Login",
            displayWidth = 1080,
            displayHeight = 2400,
            xml = xml,
        )

        val result = AndroidGuiSafetyGuard.prepareGuardedAction(
            action = Action("click", mapOf("target_description" to "登录", "x" to 250.0 / 1080.0 * 1000.0, "y" to 550.0 / 2400.0 * 1000.0)),
            expectedState = null,
            expectedObservationId = null,
            baseline = baseline,
            currentDisplayId = 0,
            currentDisplaySize = displaySize,
            freshXml = xml,
        )

        // 绝不能因为 root 包含登录按钮而后代匹配，导致 root 与按钮都被作为候选报 ambiguous
        assertTrue(result is GuardResult.Allowed)
    }

    @Test
    fun iconButtonWithContentDescOnlyMatchesWithoutText() {
        // 图标按钮仅有 content-desc="搜索"，没有 text
        val iconXml = """
            <hierarchy>
              <node id="search_btn" content-desc="搜索" bounds="[900,100][1000,200]" visible-to-user="true" clickable="true" />
            </hierarchy>
        """.trimIndent()

        val baseline = AndroidGuiObservationBaseline(
            observationId = "obs_icon",
            displayId = 0,
            frameSequence = 1L,
            stateId = "state_icon",
            packageName = "com.app",
            activityName = "Search",
            displayWidth = 1080,
            displayHeight = 2400,
            xml = iconXml,
        )

        val result = AndroidGuiSafetyGuard.prepareGuardedAction(
            action = Action("click", mapOf("target_description" to "搜索", "x" to 950.0 / 1080.0 * 1000.0, "y" to 150.0 / 2400.0 * 1000.0)),
            expectedState = null,
            expectedObservationId = null,
            baseline = baseline,
            currentDisplayId = 0,
            currentDisplaySize = displaySize,
            freshXml = iconXml,
        )

        // 绝不能将 content-desc 目标强制当作 text 匹配导致 missing
        assertTrue(result is GuardResult.Allowed)
    }

    @Test
    fun fullOcclusionByHigherWindowLayerIsDetected() {
        val targetNode = AndroidGuiNode(
            id = "target_btn",
            windowId = 1,
            windowLayer = 0,
            bounds = Rect(100, 500, 400, 600),
            visibleToUser = true,
            clickable = true,
        )
        val dialogNode = AndroidGuiNode(
            id = "dialog_overlay",
            windowId = 2,
            windowLayer = 10,
            bounds = Rect(0, 0, 1080, 2400),
            visibleToUser = true,
            clickable = true,
        )

        val occlusion = AndroidGuiSafetyGuard.checkOcclusion(
            targetNode = targetNode,
            allNodes = listOf(targetNode, dialogNode),
            displaySize = displaySize,
            actionPoint = Pair(250, 550),
        )

        assertEquals(OcclusionType.FULL, occlusion.type)
        assertEquals("dialog_overlay", occlusion.occludingNode?.id)
    }

    @Test
    fun multiBlockersTogetherFullyOccludeTarget() {
        val parent = AndroidGuiNode(id = "root", windowId = 1, bounds = Rect(0, 0, 1080, 2400))
        val target = AndroidGuiNode(
            id = "target",
            windowId = 1,
            drawingOrder = 1,
            bounds = Rect(100, 200, 500, 600),
            visibleToUser = true,
            clickable = true,
            parent = parent,
        )
        // 遮挡块 1：遮挡上半部 [100, 200, 500, 400]
        val blocker1 = AndroidGuiNode(
            id = "b1",
            windowId = 1,
            drawingOrder = 2,
            bounds = Rect(100, 200, 500, 400),
            visibleToUser = true,
            clickable = true,
            parent = parent,
        )
        // 遮挡块 2：遮挡下半部 [100, 400, 500, 600]
        val blocker2 = AndroidGuiNode(
            id = "b2",
            windowId = 1,
            drawingOrder = 3,
            bounds = Rect(100, 400, 500, 600),
            visibleToUser = true,
            clickable = true,
            parent = parent,
        )

        val occlusion = AndroidGuiSafetyGuard.checkOcclusion(
            targetNode = target,
            allNodes = listOf(target, blocker1, blocker2),
            displaySize = displaySize,
            actionPoint = Pair(300, 500),
        )

        // 两个 blocker 累加扣除后净空面积归零，必须判为 FULL 遮挡
        assertEquals(OcclusionType.FULL, occlusion.type)
        assertEquals("fully_occluded_by_blockers", occlusion.reason)
    }

    @Test
    fun partialOcclusionRepositionActionToClearPoint() {
        // baseline 与 fresh：按钮被顶部 banner 遮挡上半部分 [100, 200, 500, 400]，下半部分 [100, 400, 500, 600] 净空
        val xml = """
            <hierarchy>
              <node id="root" window-id="1" bounds="[0,0][1080,2400]" visible-to-user="true">
                <node id="target" drawing-order="1" text="保存" bounds="[100,200][500,600]" visible-to-user="true" clickable="true" />
                <node id="banner" drawing-order="2" bounds="[100,200][500,400]" visible-to-user="true" clickable="true" />
              </node>
            </hierarchy>
        """.trimIndent()

        val baseline = AndroidGuiObservationBaseline(
            observationId = "obs_save",
            displayId = 0,
            frameSequence = 1L,
            stateId = "state_save",
            packageName = "com.app",
            activityName = "Form",
            displayWidth = 1080,
            displayHeight = 2400,
            xml = xml,
        )

        // 原点击坐标位于被遮挡的上半部 (250, 300) 对应归一化坐标 x=231.48, y=125.0
        val originalAction = Action("click", mapOf("target_description" to "保存", "x" to 231.48, "y" to 125.0))

        val result = AndroidGuiSafetyGuard.prepareGuardedAction(
            action = originalAction,
            expectedState = null,
            expectedObservationId = null,
            baseline = baseline,
            currentDisplayId = 0,
            currentDisplaySize = displaySize,
            freshXml = xml,
        )

        // 吸收净空点定位：原点被遮挡但不应只交付拒绝，而是重定位派发坐标至下半部安全净空点
        assertTrue(result is GuardResult.Allowed)
        val allowed = result as GuardResult.Allowed
        assertNotNull(allowed.adjustedCoordinate)
        assertTrue(allowed.adjustedCoordinate!!.second > 400) // 调整后的坐标 Y 位于安全区 > 400
        val adjustedX = (allowed.action.args["x"] as Number).toDouble()
        val adjustedY = (allowed.action.args["y"] as Number).toDouble()
        assertTrue(adjustedY > 400.0 / 2400.0 * 1000.0)
    }

    @Test
    fun nonInteractiveSiblingDoesNotTouchBlockTarget() {
        val parent = AndroidGuiNode(id = "root", windowId = 1, bounds = Rect(0, 0, 1080, 2400))
        val targetButton = AndroidGuiNode(
            id = "btn",
            windowId = 1,
            drawingOrder = 1,
            bounds = Rect(100, 200, 300, 400),
            visibleToUser = true,
            clickable = true,
            parent = parent,
        )
        // 同级但非交互的透明容器或占位布局
        val transparentContainer = AndroidGuiNode(
            id = "container",
            windowId = 1,
            drawingOrder = 5,
            bounds = Rect(100, 200, 300, 400),
            visibleToUser = true,
            clickable = false,
            focusable = false,
            parent = parent,
        )

        val occlusion = AndroidGuiSafetyGuard.checkOcclusion(
            targetNode = targetButton,
            allNodes = listOf(targetButton, transparentContainer),
            displaySize = displaySize,
            actionPoint = Pair(200, 300),
        )

        // 非可交互兄弟节点不阻挡底层触摸
        assertEquals(OcclusionType.NONE, occlusion.type)
    }

    @Test
    fun nearestInteractiveParentIsResolved() {
        val parent = AndroidGuiNode(id = "clickable_row", bounds = Rect(0, 100, 1080, 300), clickable = true)
        val child = AndroidGuiNode(id = "icon", bounds = Rect(20, 120, 80, 180), clickable = false, parent = parent)

        val resolved = AndroidGuiSafetyGuard.findNearestInteractiveNode(child)
        assertEquals("clickable_row", resolved.id)
    }

    @Test
    fun clickableParentWithoutTextPreservesChildTextIdentity() {
        val baselineXml = """
            <hierarchy>
              <node id="row" resource-id="com.shop:id/buy_btn_container" clickable="true" bounds="[100,500][400,600]" visible-to-user="true">
                <node id="txt" text="立即购买" bounds="[150,520][350,580]" visible-to-user="true" />
              </node>
            </hierarchy>
        """.trimIndent()

        val freshXml = baselineXml

        val baseline = AndroidGuiObservationBaseline(
            observationId = "obs_1",
            displayId = 0,
            frameSequence = 1L,
            stateId = "state_1",
            packageName = "com.shop",
            activityName = "Detail",
            displayWidth = 1080,
            displayHeight = 2400,
            xml = baselineXml,
        )

        val result = AndroidGuiSafetyGuard.prepareGuardedAction(
            action = Action("click", mapOf("target_description" to "立即购买", "x" to 250.0 / 1080.0 * 1000.0, "y" to 550.0 / 2400.0 * 1000.0)),
            expectedState = null,
            expectedObservationId = null,
            baseline = baseline,
            currentDisplayId = 0,
            currentDisplaySize = displaySize,
            freshXml = freshXml,
        )

        assertTrue(result is GuardResult.Allowed)
    }

    @Test
    fun semanticMatchingRejectsWhenListItemTextChangesDespiteSameResourceId() {
        val baselineXml = """
            <hierarchy>
              <node id="item1" resource-id="com.news:id/item_title" text="科技要闻 A" bounds="[100,500][400,600]" visible-to-user="true" clickable="true" />
            </hierarchy>
        """.trimIndent()

        val freshXml = """
            <hierarchy>
              <node id="item1" resource-id="com.news:id/item_title" text="娱乐八卦 B" bounds="[100,500][400,600]" visible-to-user="true" clickable="true" />
            </hierarchy>
        """.trimIndent()

        val baseline = AndroidGuiObservationBaseline(
            observationId = "obs_news",
            displayId = 0,
            frameSequence = 1L,
            stateId = "state_news",
            packageName = "com.news",
            activityName = "Feed",
            displayWidth = 1080,
            displayHeight = 2400,
            xml = baselineXml,
        )

        val result = AndroidGuiSafetyGuard.prepareGuardedAction(
            action = Action("click", mapOf("target_description" to "科技要闻 A", "x" to 250.0 / 1080.0 * 1000.0, "y" to 550.0 / 2400.0 * 1000.0)),
            expectedState = null,
            expectedObservationId = null,
            baseline = baseline,
            currentDisplayId = 0,
            currentDisplaySize = displaySize,
            freshXml = freshXml,
        )

        assertTrue(result is GuardResult.Rejected)
        assertEquals("action_target_missing", (result as GuardResult.Rejected).failure.message)
    }

    @Test
    fun coordinateMovedOutsideBoundsImmediatelyRejects() {
        val baselineXml = """
            <hierarchy>
              <node id="btn" text="确定" bounds="[100,500][300,560]" visible-to-user="true" clickable="true" />
            </hierarchy>
        """.trimIndent()

        val freshXml = """
            <hierarchy>
              <node id="btn" text="确定" bounds="[100,600][300,660]" visible-to-user="true" clickable="true" />
            </hierarchy>
        """.trimIndent()

        val baseline = AndroidGuiObservationBaseline(
            observationId = "obs_btn",
            displayId = 0,
            frameSequence = 1L,
            stateId = "state_btn",
            packageName = "com.app",
            activityName = "Main",
            displayWidth = 1080,
            displayHeight = 2400,
            xml = baselineXml,
        )

        val result = AndroidGuiSafetyGuard.prepareGuardedAction(
            action = Action("click", mapOf("target_description" to "确定", "x" to 200.0 / 1080.0 * 1000.0, "y" to 530.0 / 2400.0 * 1000.0)),
            expectedState = null,
            expectedObservationId = null,
            baseline = baseline,
            currentDisplayId = 0,
            currentDisplaySize = displaySize,
            freshXml = freshXml,
        )

        assertTrue(result is GuardResult.Rejected)
        assertEquals("action_target_moved", (result as GuardResult.Rejected).failure.message)
    }

    @Test
    fun relativeWindowLayerDoesNotTreatDialogOwnContainerAsOcclusion() {
        val dialogXml = """
            <hierarchy>
              <node id="dialog" window-id="2" window-layer="20" className="android.app.Dialog" bounds="[100,500][980,1500]" visible-to-user="true">
                <node id="canvas_btn" window-id="2" window-layer="20" bounds="[300,1300][780,1450]" visible-to-user="true" clickable="true" />
              </node>
            </hierarchy>
        """.trimIndent()

        val baseline = AndroidGuiObservationBaseline(
            observationId = "obs_dlg",
            displayId = 0,
            frameSequence = 1L,
            stateId = "state_dlg",
            packageName = "com.app",
            activityName = "Main",
            displayWidth = 1080,
            displayHeight = 2400,
            xml = dialogXml,
        )

        val result = AndroidGuiSafetyGuard.prepareGuardedAction(
            action = Action("click", mapOf("x" to 500.0 / 1080.0 * 1000.0, "y" to 1380.0 / 2400.0 * 1000.0)),
            expectedState = null,
            expectedObservationId = null,
            baseline = baseline,
            currentDisplayId = 0,
            currentDisplaySize = displaySize,
            freshXml = dialogXml,
        )

        assertTrue(result is GuardResult.Allowed)
    }

    @Test
    fun rootToResourceOnlyButtonDispatchesSafely() {
        // 非交互全屏 root，无任何属性；子按钮只有 resource-id，无文字
        val xml = """
            <hierarchy>
              <node id="root" bounds="[0,0][1080,2400]" visible-to-user="true">
                <node id="btn" resource-id="com.app:id/submit_action" bounds="[100,500][400,600]" visible-to-user="true" clickable="true" />
              </node>
            </hierarchy>
        """.trimIndent()

        val baseline = AndroidGuiObservationBaseline(
            observationId = "obs_res",
            displayId = 0,
            frameSequence = 1L,
            stateId = "state_res",
            packageName = "com.app",
            activityName = "Main",
            displayWidth = 1080,
            displayHeight = 2400,
            xml = xml,
        )

        // 动作指定 resource-id 与坐标
        val result = AndroidGuiSafetyGuard.prepareGuardedAction(
            action = Action("click", mapOf("node_resource_id" to "com.app:id/submit_action", "x" to 250.0 / 1080.0 * 1000.0, "y" to 550.0 / 2400.0 * 1000.0)),
            expectedState = null,
            expectedObservationId = null,
            baseline = baseline,
            currentDisplayId = 0,
            currentDisplaySize = displaySize,
            freshXml = xml,
        )

        // root 不得因缺属性混入候选，唯一定位到子按钮并正常放行
        assertTrue(result is GuardResult.Allowed)
    }

    @Test
    fun textChangeUnderSameResourceIdAndPositionIsRejected() {
        // baseline 按钮文本为“删除”
        val baselineXml = """
            <hierarchy>
              <node id="btn" resource-id="com.app:id/action_btn" text="删除" bounds="[100,500][400,600]" visible-to-user="true" clickable="true" />
            </hierarchy>
        """.trimIndent()

        // fresh 中按钮文本变更为“删除全部”（同位置、同 resource-id）
        val freshXml = """
            <hierarchy>
              <node id="btn" resource-id="com.app:id/action_btn" text="删除全部" bounds="[100,500][400,600]" visible-to-user="true" clickable="true" />
            </hierarchy>
        """.trimIndent()

        val baseline = AndroidGuiObservationBaseline(
            observationId = "obs_del",
            displayId = 0,
            frameSequence = 1L,
            stateId = "state_del",
            packageName = "com.app",
            activityName = "Main",
            displayWidth = 1080,
            displayHeight = 2400,
            xml = baselineXml,
        )

        val result = AndroidGuiSafetyGuard.prepareGuardedAction(
            action = Action("click", mapOf("target_description" to "删除", "x" to 250.0 / 1080.0 * 1000.0, "y" to 550.0 / 2400.0 * 1000.0)),
            expectedState = null,
            expectedObservationId = null,
            baseline = baseline,
            currentDisplayId = 0,
            currentDisplaySize = displaySize,
            freshXml = freshXml,
        )

        // 精确相等校验：绝不能因 contains("删除") 而放行已变更为“删除全部”的危险替换
        assertTrue(result is GuardResult.Rejected)
        assertEquals("action_target_missing", (result as GuardResult.Rejected).failure.message)
    }

    @Test
    fun twoOverlappingButtonsSameResIdDifferentTextDispatchesSpecifiedB() {
        // baseline 中有两个相同 resource-id、不同文本且重叠的按钮：A="确定" (id=1), B="取消" (id=2)
        val xml = """
            <hierarchy>
              <node id="1" resource-id="com.app:id/dialog_btn" text="确定" bounds="[100,500][400,600]" visible-to-user="true" clickable="true" />
              <node id="2" resource-id="com.app:id/dialog_btn" text="取消" bounds="[100,500][400,600]" visible-to-user="true" clickable="true" />
            </hierarchy>
        """.trimIndent()

        val baseline = AndroidGuiObservationBaseline(
            observationId = "obs_dup",
            displayId = 0,
            frameSequence = 1L,
            stateId = "state_dup",
            packageName = "com.app",
            activityName = "Main",
            displayWidth = 1080,
            displayHeight = 2400,
            xml = xml,
        )

        // 明确携带 B 的 node_id="2" 与其重叠区坐标，无 targetDesc
        val result = AndroidGuiSafetyGuard.prepareGuardedAction(
            action = Action("click", mapOf("node_id" to "2", "node_resource_id" to "com.app:id/dialog_btn", "x" to 250.0 / 1080.0 * 1000.0, "y" to 550.0 / 2400.0 * 1000.0)),
            expectedState = null,
            expectedObservationId = null,
            baseline = baseline,
            currentDisplayId = 0,
            currentDisplaySize = displaySize,
            freshXml = xml,
        )

        // 必须优先验证明确 node_id 与 B 一致，绝不盲目 firstOrNull 取 A
        assertTrue(result is GuardResult.Allowed)
    }

    @Test
    fun noIdTrueMultipleCandidatesRejectsWithoutGuessing() {
        // baseline 中有两个完全相同的按钮（同 id、同文本，位置不同且坐标未点击任何一个）
        val xml = """
            <hierarchy>
              <node id="btn1" resource-id="com.app:id/btn" text="加号" bounds="[100,200][300,300]" visible-to-user="true" clickable="true" />
              <node id="btn2" resource-id="com.app:id/btn" text="加号" bounds="[100,500][300,600]" visible-to-user="true" clickable="true" />
            </hierarchy>
        """.trimIndent()

        val baseline = AndroidGuiObservationBaseline(
            observationId = "obs_amb",
            displayId = 0,
            frameSequence = 1L,
            stateId = "state_amb",
            packageName = "com.app",
            activityName = "Main",
            displayWidth = 1080,
            displayHeight = 2400,
            xml = xml,
        )

        // 无 node_id，且点击坐标 (0,0) 未落入任何一个按钮 bounds，无法唯一消歧
        val result = AndroidGuiSafetyGuard.prepareGuardedAction(
            action = Action("click", mapOf("target_description" to "加号", "x" to 0.0, "y" to 0.0)),
            expectedState = null,
            expectedObservationId = null,
            baseline = baseline,
            currentDisplayId = 0,
            currentDisplaySize = displaySize,
            freshXml = xml,
        )

        // 无法唯一确定目标，坚决拒绝，绝不盲目猜选 firstOrNull
        assertTrue(result is GuardResult.Rejected)
        assertEquals("action_target_ambiguous", (result as GuardResult.Rejected).failure.message)
    }

    @Test
    fun twoOverlappingButtonsBothContainingPointRejectsAsAmbiguous() {
        // 两个相同 resource-id 的按钮完全重叠，且点击点均落入两者 bounds
        val xml = """
            <hierarchy>
              <node id="1" resource-id="com.app:id/dup_btn" text="加号" bounds="[100,200][300,400]" visible-to-user="true" clickable="true" />
              <node id="2" resource-id="com.app:id/dup_btn" text="加号" bounds="[100,200][300,400]" visible-to-user="true" clickable="true" />
            </hierarchy>
        """.trimIndent()

        val baseline = AndroidGuiObservationBaseline(
            observationId = "obs_dup_overlap",
            displayId = 0,
            frameSequence = 1L,
            stateId = "state_dup_overlap",
            packageName = "com.app",
            activityName = "Main",
            displayWidth = 1080,
            displayHeight = 2400,
            xml = xml,
            screenshotJpeg = byteArrayOf(1, 2, 3),
        )

        // 点击点落入重叠区 (200, 300)，无明确 node_id
        val result = AndroidGuiSafetyGuard.prepareGuardedAction(
            action = Action("click", mapOf("target_description" to "加号", "node_resource_id" to "com.app:id/dup_btn", "x" to 200.0 / 1080.0 * 1000.0, "y" to 300.0 / 2400.0 * 1000.0)),
            expectedState = null,
            expectedObservationId = null,
            baseline = baseline,
            currentDisplayId = 0,
            currentDisplaySize = displaySize,
            freshXml = xml,
            freshScreenshotJpeg = byteArrayOf(1, 2, 3), // 相同截图
        )

        // 即使截图完全一致，存在真正多候选重叠时，必须严格拒绝 action_target_ambiguous，绝不走视觉 ROI 漏放
        assertTrue(result is GuardResult.Rejected)
        assertEquals("action_target_ambiguous", (result as GuardResult.Rejected).failure.message)
    }

    @Test
    fun explicitNonExistentNodeIdRejectsAsMissing() {
        val xml = """
            <hierarchy>
              <node id="btn1" text="登录" bounds="[100,200][300,400]" visible-to-user="true" clickable="true" />
            </hierarchy>
        """.trimIndent()

        val baseline = AndroidGuiObservationBaseline(
            observationId = "obs_bad_node",
            displayId = 0,
            frameSequence = 1L,
            stateId = "state_bad_node",
            packageName = "com.app",
            activityName = "Main",
            displayWidth = 1080,
            displayHeight = 2400,
            xml = xml,
            screenshotJpeg = byteArrayOf(1, 2, 3),
        )

        // 明确传入了不存在的 node_id="999"
        val result = AndroidGuiSafetyGuard.prepareGuardedAction(
            action = Action("click", mapOf("node_id" to "999", "x" to 200.0 / 1080.0 * 1000.0, "y" to 300.0 / 2400.0 * 1000.0)),
            expectedState = null,
            expectedObservationId = null,
            baseline = baseline,
            currentDisplayId = 0,
            currentDisplaySize = displaySize,
            freshXml = xml,
            freshScreenshotJpeg = byteArrayOf(1, 2, 3),
        )

        // 指定了明确 node_id 但不存在，坚决报 action_target_missing，绝不能当成视觉/OCR目标放行
        assertTrue(result is GuardResult.Rejected)
        assertEquals("action_target_missing", (result as GuardResult.Rejected).failure.message)
    }

    private val context: Context
        get() = RuntimeEnvironment.getApplication()

    private fun createPlatformState(
        xml: String,
        screenshotJpeg: ByteArray? = null,
        displayId: Int = Display.DEFAULT_DISPLAY,
        frameSequence: Long? = 10L,
    ): Any {
        val clazz = Class.forName("cn.com.omnimind.androidgui.AndroidGuiPlatformState")
        val ctor = clazz.declaredConstructors.first { it.parameterCount == 8 }
        ctor.isAccessible = true
        return ctor.newInstance(
            "com.test.app",
            "MainActivity",
            1080,
            2400,
            xml,
            screenshotJpeg,
            displayId,
            frameSequence,
        )
    }

    private fun createFakePlatform(
        onObserve: (captureScreenshot: Boolean, displayId: Int) -> Any,
        onDispatch: (action: Action, displayId: Int) -> AndroidGuiActionResult,
    ): Any {
        val platformClass = Class.forName("cn.com.omnimind.androidgui.AndroidGuiPlatform")
        return Proxy.newProxyInstance(
            platformClass.classLoader,
            arrayOf(platformClass),
        ) { _, method, args ->
            when (method.name) {
                "isAccessibilityEnabled" -> true
                "isReady" -> true
                "displaySize" -> 1080 to 2400
                "screenshotExcludesOverlays" -> true
                "observe" -> {
                    val captureScreenshot = args?.getOrNull(0) as? Boolean ?: true
                    val displayId = args?.getOrNull(1) as? Int ?: Display.DEFAULT_DISPLAY
                    onObserve(captureScreenshot, displayId)
                }
                "dispatch" -> {
                    val action = args[0] as Action
                    val displayId = args.getOrNull(1) as? Int ?: Display.DEFAULT_DISPLAY
                    onDispatch(action, displayId)
                }
                "installedApplications" -> emptyMap<String, String>()
                "inputTarget" -> null
                "inputMethodTop" -> null
                "toString" -> "FakePlatform"
                "hashCode" -> 1
                "equals" -> false
                else -> null
            }
        }
    }

    private fun createEnvironment(
        appContext: Context?,
        platform: Any,
        ocrEngine: AndroidScreenOcrEngine? = null,
    ): AndroidGuiEnvironment {
        val ctor = AndroidGuiEnvironment::class.java.declaredConstructors
            .first { it.parameterCount == 3 }
        ctor.isAccessible = true
        return ctor.newInstance(appContext, platform, ocrEngine) as AndroidGuiEnvironment
    }

    @Test
    fun googlePlayStoreNestedParentChildLabelsDispatchesCorrectlyViaEnvironmentAct() = runBlocking {
        var dispatchCount = 0
        var dispatchedAction: Action? = null

        // 真实谷歌商店嵌套结构：
        // node16 View (clickable=true, focusable=true, bounds=[64,2191][1017,2317])
        //   node17 View (nonclickable, nonfocusable, bounds=[469,2227][613,2280], text="开始体验")
        //     node18 TextView (noninteractive, bounds=[469,2227][613,2280], text="开始体验")
        val googlePlayStoreXml = """
            <hierarchy>
              <node id="16" class="android.view.View" bounds="[64,2191][1017,2317]" clickable="true" focusable="true" visible-to-user="true">
                <node id="17" class="android.view.View" text="开始体验" bounds="[469,2227][613,2280]" visible-to-user="true" clickable="false" focusable="false">
                  <node id="18" class="android.widget.TextView" text="开始体验" bounds="[469,2227][613,2280]" visible-to-user="true" clickable="false" focusable="false" />
                </node>
              </node>
            </hierarchy>
        """.trimIndent()

        val fakePlatform = createFakePlatform(
            onObserve = { _, displayId ->
                createPlatformState(xml = googlePlayStoreXml, displayId = displayId)
            },
            onDispatch = { action, _ ->
                dispatchCount++
                dispatchedAction = action
                AndroidGuiActionResult(true, "ok")
            },
        )

        val env = createEnvironment(context, fakePlatform)
        env.observeWithDiagnostics()

        // 模拟带 target_description 与坐标点击（归一化坐标对应真实点击 (541, 2253)）
        val result = env.act(
            action = Action(
                "click",
                mapOf(
                    "target_description" to "开始体验",
                    "x" to (541.0 / 1080.0 * 1000.0),
                    "y" to (2253.0 / 2400.0 * 1000.0),
                )
            ),
            awaitStabilization = false,
        )

        assertTrue("Expected action to succeed but was: ${result.message}", result.success)
        assertEquals(1, dispatchCount)
        assertEquals("ok", result.message)
        assertNotNull(dispatchedAction)
        assertEquals("click", dispatchedAction?.tool)
        val clickX = (dispatchedAction!!.args["x"] as Number).toInt()
        val clickY = (dispatchedAction!!.args["y"] as Number).toInt()
        val node16TargetBounds = Rect(64, 2191, 1017, 2317)
        assertTrue(
            "Dispatched coordinate ($clickX, $clickY) must fall within unique node 16 target bounds $node16TargetBounds",
            node16TargetBounds.contains(clickX, clickY),
        )
    }

    @Test
    fun siblingButtonsWithSameTextRejectedAsAmbiguousWithoutCoordinateViaEnvironmentAct() = runBlocking {
        var dispatchCount = 0

        // 同名兄弟不同 target 按钮：
        // btn1 与 btn2 为两个同名独立可交互按钮，未传入坐标或坐标未落入任何一个按钮，绝不能合并
        val siblingButtonsXml = """
            <hierarchy>
              <node id="btn1" class="android.view.View" bounds="[64,1000][1017,1126]" clickable="true" focusable="true" visible-to-user="true">
                <node id="txt1" class="android.widget.TextView" text="开始体验" bounds="[469,1036][613,1089]" visible-to-user="true" />
              </node>
              <node id="btn2" class="android.view.View" bounds="[64,2191][1017,2317]" clickable="true" focusable="true" visible-to-user="true">
                <node id="txt2" class="android.widget.TextView" text="开始体验" bounds="[469,2227][613,2280]" visible-to-user="true" />
              </node>
            </hierarchy>
        """.trimIndent()

        val fakePlatform = createFakePlatform(
            onObserve = { _, displayId ->
                createPlatformState(xml = siblingButtonsXml, displayId = displayId)
            },
            onDispatch = { _, _ ->
                dispatchCount++
                AndroidGuiActionResult(true, "ok")
            },
        )

        val env = createEnvironment(context, fakePlatform)
        env.observeWithDiagnostics()

        // 坐标落在空白处 (0, 0)，无法消歧
        val result = env.act(
            action = Action(
                "click",
                mapOf(
                    "target_description" to "开始体验",
                    "x" to 0.0,
                    "y" to 0.0,
                )
            ),
            awaitStabilization = false,
        )

        assertFalse(result.success)
        assertEquals("action_target_ambiguous", result.message)
        assertEquals(0, dispatchCount)
    }

    @Test
    fun siblingButtonsDisambiguatedByCoordinateDispatchesSingleTargetViaEnvironmentAct() = runBlocking {
        var dispatchCount = 0
        var dispatchedAction: Action? = null

        val siblingButtonsXml = """
            <hierarchy>
              <node id="btn1" class="android.view.View" bounds="[64,1000][1017,1126]" clickable="true" focusable="true" visible-to-user="true">
                <node id="txt1" class="android.widget.TextView" text="开始体验" bounds="[469,1036][613,1089]" visible-to-user="true" />
              </node>
              <node id="btn2" class="android.view.View" bounds="[64,2191][1017,2317]" clickable="true" focusable="true" visible-to-user="true">
                <node id="txt2" class="android.widget.TextView" text="开始体验" bounds="[469,2227][613,2280]" visible-to-user="true" />
              </node>
            </hierarchy>
        """.trimIndent()

        val fakePlatform = createFakePlatform(
            onObserve = { _, displayId ->
                createPlatformState(xml = siblingButtonsXml, displayId = displayId)
            },
            onDispatch = { action, _ ->
                dispatchCount++
                dispatchedAction = action
                AndroidGuiActionResult(true, "ok")
            },
        )

        val env = createEnvironment(context, fakePlatform)
        env.observeWithDiagnostics()

        // 坐标落在 btn2 内部 (541, 2253)，唯一消歧到 btn2
        val result = env.act(
            action = Action(
                "click",
                mapOf(
                    "target_description" to "开始体验",
                    "x" to (541.0 / 1080.0 * 1000.0),
                    "y" to (2253.0 / 2400.0 * 1000.0),
                )
            ),
            awaitStabilization = false,
        )

        assertTrue(result.success)
        assertEquals(1, dispatchCount)
        assertNotNull(dispatchedAction)
        assertEquals("click", dispatchedAction?.tool)
        val clickX = (dispatchedAction!!.args["x"] as Number).toInt()
        val clickY = (dispatchedAction!!.args["y"] as Number).toInt()
        val btn2TargetBounds = Rect(64, 2191, 1017, 2317)
        assertTrue(
            "Dispatched coordinate ($clickX, $clickY) must fall within btn2 target bounds $btn2TargetBounds",
            btn2TargetBounds.contains(clickX, clickY),
        )
    }

    @Test
    fun independentClickableChildInsideClickableContainerNotMergedAndRejectedViaEnvironmentAct() = runBlocking {
        var dispatchCount = 0

        // 独立可交互子节点：
        // 外层 card 可交互且具备相同文本，内层 inner_btn 也是独立可交互（clickable=true）控件且具备相同文本
        // 两者不应被错误合并；当点击点同时落在重叠区时，仍应被拒绝为 ambiguous
        val nestedInteractiveXml = """
            <hierarchy>
              <node id="card" class="android.view.View" bounds="[64,1000][1017,1500]" clickable="true" focusable="true" visible-to-user="true" text="开始体验">
                <node id="inner_btn" class="android.widget.Button" bounds="[100,1100][400,1200]" clickable="true" focusable="true" visible-to-user="true" text="开始体验" />
              </node>
            </hierarchy>
        """.trimIndent()

        val fakePlatform = createFakePlatform(
            onObserve = { _, displayId ->
                createPlatformState(xml = nestedInteractiveXml, displayId = displayId)
            },
            onDispatch = { _, _ ->
                dispatchCount++
                AndroidGuiActionResult(true, "ok")
            },
        )

        val env = createEnvironment(context, fakePlatform)
        env.observeWithDiagnostics()

        // 点击坐标位于 inner_btn 内部 (200, 1150)，由于 card 与 inner_btn 均为合法候选且均包含坐标，拒绝猜选
        val result = env.act(
            action = Action(
                "click",
                mapOf(
                    "target_description" to "开始体验",
                    "x" to (200.0 / 1080.0 * 1000.0),
                    "y" to (1150.0 / 2400.0 * 1000.0),
                )
            ),
            awaitStabilization = false,
        )

        assertFalse(result.success)
        assertEquals("action_target_ambiguous", result.message)
        assertEquals(0, dispatchCount)
    }

    private fun createTestBitmap(
        width: Int = 1080,
        height: Int = 2400,
        color: Int = Color.RED,
        fillScreen: Boolean = true,
        drawRect: Rect? = null,
        rectColor: Int = Color.BLUE,
    ): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        if (fillScreen) {
            canvas.drawColor(color)
        }
        if (drawRect != null) {
            val paint = Paint().apply { this.color = rectColor }
            canvas.drawRect(drawRect, paint)
        }
        return ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
            bitmap.recycle()
            out.toByteArray()
        }
    }

    @Test
    fun swipeObstructedByMidpointDialogPopupRejectsAndDispatchesZeroViaEnvironmentAct() = runBlocking {
        var dispatchCount = 0
        var observeCount = 0

        // 基线状态：全屏可滚动容器（列表），无任何弹窗遮挡
        val baselineXml = """
            <hierarchy>
              <node id="scroll_container" class="android.widget.ScrollView" scrollable="true" bounds="[0,0][1080,2400]" window-id="1" window-layer="10" visible-to-user="true">
                <node id="content" class="android.widget.LinearLayout" bounds="[0,0][1080,4800]" visible-to-user="true">
                  <node id="item1" text="条目1" bounds="[50,200][1030,400]" visible-to-user="true" />
                  <node id="item2" text="条目2" bounds="[50,2000][1030,2200]" visible-to-user="true" />
                </node>
              </node>
            </hierarchy>
        """.trimIndent()

        // 实时状态：中途弹出 Dialog 遮挡滑动轨迹正中央 [100,800][980,1600]
        // 滑动起点 (540, 1920) 与终点 (540, 480) 均落在弹窗外部，但滑动线段正中穿过高层弹窗！
        val freshXml = """
            <hierarchy>
              <node id="scroll_container" class="android.widget.ScrollView" scrollable="true" bounds="[0,0][1080,2400]" window-id="1" window-layer="10" visible-to-user="true">
                <node id="content" class="android.widget.LinearLayout" bounds="[0,0][1080,4800]" visible-to-user="true">
                  <node id="item1" text="条目1" bounds="[50,200][1030,400]" visible-to-user="true" />
                  <node id="item2" text="条目2" bounds="[50,2000][1030,2200]" visible-to-user="true" />
                </node>
              </node>
              <node id="dialog" class="android.app.Dialog" window-id="2" window-layer="20" bounds="[100,800][980,1600]" visible-to-user="true" clickable="true">
                <node id="dialog_title" text="更新提醒" bounds="[200,900][880,1050]" visible-to-user="true" />
              </node>
            </hierarchy>
        """.trimIndent()

        val fakePlatform = createFakePlatform(
            onObserve = { _, displayId ->
                observeCount++
                if (observeCount == 1) {
                    createPlatformState(xml = baselineXml, displayId = displayId)
                } else {
                    createPlatformState(xml = freshXml, displayId = displayId)
                }
            },
            onDispatch = { _, _ ->
                dispatchCount++
                AndroidGuiActionResult(true, "ok")
            },
        )

        val env = createEnvironment(context, fakePlatform)
        val obs = env.observeWithDiagnostics()

        // 滑动从 Y=1920 (800) 滑向 Y=480 (200)，线段与中途出现的弹窗相交，必须拦截并拒绝派发
        val result = env.act(
            action = Action(
                "swipe",
                mapOf(
                    "x1" to 500.0,
                    "y1" to 800.0,
                    "x2" to 500.0,
                    "y2" to 200.0,
                )
            ),
            expectedObservationId = obs.extra["observation_id"] as? String,
            awaitStabilization = false,
        )

        assertFalse("预期弹窗遮挡时滑动被拒绝，但执行成功", result.success)
        assertEquals("action_swipe_obstructed", result.message)
        assertEquals("发生弹窗遮挡时底层派发次数必须为0", 0, dispatchCount)
    }

    @Test
    fun swipeObstructedByInteractiveSiblingBlockerRejectsAndDispatchesZeroViaEnvironmentAct() = runBlocking {
        var dispatchCount = 0
        var observeCount = 0

        // 同一窗口中，具备更高绘序且可交互的兄弟遮挡层（如悬浮横幅）
        val baselineXml = """
            <hierarchy>
              <node id="root" class="android.widget.FrameLayout" bounds="[0,0][1080,2400]" window-id="1" visible-to-user="true">
                <node id="scroll_list" class="android.widget.ScrollView" scrollable="true" bounds="[0,0][1080,2400]" window-id="1" drawing-order="1" visible-to-user="true" />
              </node>
            </hierarchy>
        """.trimIndent()

        val freshXml = """
            <hierarchy>
              <node id="root" class="android.widget.FrameLayout" bounds="[0,0][1080,2400]" window-id="1" visible-to-user="true">
                <node id="scroll_list" class="android.widget.ScrollView" scrollable="true" bounds="[0,0][1080,2400]" window-id="1" drawing-order="1" visible-to-user="true" />
                <node id="floating_banner" class="android.view.View" bounds="[200,900][880,1500]" window-id="1" drawing-order="2" clickable="true" visible-to-user="true" />
              </node>
            </hierarchy>
        """.trimIndent()

        val fakePlatform = createFakePlatform(
            onObserve = { _, displayId ->
                observeCount++
                if (observeCount == 1) {
                    createPlatformState(xml = baselineXml, displayId = displayId)
                } else {
                    createPlatformState(xml = freshXml, displayId = displayId)
                }
            },
            onDispatch = { _, _ ->
                dispatchCount++
                AndroidGuiActionResult(true, "ok")
            },
        )

        val env = createEnvironment(context, fakePlatform)
        val obs = env.observeWithDiagnostics()

        val result = env.act(
            action = Action(
                "swipe",
                mapOf(
                    "x1" to 500.0,
                    "y1" to 750.0,
                    "x2" to 500.0,
                    "y2" to 250.0,
                )
            ),
            expectedObservationId = obs.extra["observation_id"] as? String,
            awaitStabilization = false,
        )

        assertFalse(result.success)
        assertEquals("action_swipe_obstructed", result.message)
        assertEquals(0, dispatchCount)
    }

    @Test
    fun swipeNotObstructedByNonInteractiveSiblingAllowsAndDispatchesViaEnvironmentAct() = runBlocking {
        var dispatchCount = 0
        var observeCount = 0

        // 非可交互兄弟覆盖物（如纯透明渐变视图），不阻挡滑动触摸手势
        val baselineXml = """
            <hierarchy>
              <node id="root" class="android.widget.FrameLayout" bounds="[0,0][1080,2400]" window-id="1" visible-to-user="true">
                <node id="scroll_list" class="android.widget.ScrollView" scrollable="true" bounds="[0,0][1080,2400]" window-id="1" drawing-order="1" visible-to-user="true" />
              </node>
            </hierarchy>
        """.trimIndent()

        val freshXml = """
            <hierarchy>
              <node id="root" class="android.widget.FrameLayout" bounds="[0,0][1080,2400]" window-id="1" visible-to-user="true">
                <node id="scroll_list" class="android.widget.ScrollView" scrollable="true" bounds="[0,0][1080,2400]" window-id="1" drawing-order="1" visible-to-user="true" />
                <node id="gradient_shadow" class="android.view.View" bounds="[200,900][880,1500]" window-id="1" drawing-order="2" clickable="false" focusable="false" visible-to-user="true" />
              </node>
            </hierarchy>
        """.trimIndent()

        val fakePlatform = createFakePlatform(
            onObserve = { _, displayId ->
                observeCount++
                if (observeCount == 1) {
                    createPlatformState(xml = baselineXml, displayId = displayId)
                } else {
                    createPlatformState(xml = freshXml, displayId = displayId)
                }
            },
            onDispatch = { _, _ ->
                dispatchCount++
                AndroidGuiActionResult(true, "ok")
            },
        )

        val env = createEnvironment(context, fakePlatform)
        val obs = env.observeWithDiagnostics()

        val result = env.act(
            action = Action(
                "swipe",
                mapOf(
                    "x1" to 500.0,
                    "y1" to 750.0,
                    "x2" to 500.0,
                    "y2" to 250.0,
                )
            ),
            expectedObservationId = obs.extra["observation_id"] as? String,
            awaitStabilization = false,
        )

        assertTrue("非可交互兄弟节点不应阻挡滑动", result.success)
        assertEquals(1, dispatchCount)
    }

    @Test
    fun swipeNormalScrollableContainerClearPathDispatchesSuccessfullyViaEnvironmentAct() = runBlocking {
        var dispatchCount = 0
        val scrollXml = """
            <hierarchy>
              <node id="scroll_view" class="android.widget.ScrollView" scrollable="true" bounds="[0,0][1080,2400]" window-id="1" visible-to-user="true">
                <node id="item1" text="条目1" bounds="[50,200][1030,400]" visible-to-user="true" />
              </node>
            </hierarchy>
        """.trimIndent()

        val fakePlatform = createFakePlatform(
            onObserve = { _, displayId ->
                createPlatformState(xml = scrollXml, displayId = displayId)
            },
            onDispatch = { _, _ ->
                dispatchCount++
                AndroidGuiActionResult(true, "ok")
            },
        )

        val env = createEnvironment(context, fakePlatform)
        val obs = env.observeWithDiagnostics()

        val result = env.act(
            action = Action(
                "swipe",
                mapOf(
                    "x1" to 500.0,
                    "y1" to 800.0,
                    "x2" to 500.0,
                    "y2" to 200.0,
                )
            ),
            expectedObservationId = obs.extra["observation_id"] as? String,
            awaitStabilization = false,
        )

        assertTrue(result.success)
        assertEquals(1, dispatchCount)
    }


    @Test
    fun swipeTargetMissingInFreshRejectsAndDispatchesZeroViaEnvironmentAct() = runBlocking {
        var dispatchCount = 0
        var observeCount = 0

        val baselineXml = """
            <hierarchy>
              <node id="container" resource-id="com.test.app:id/main_scroll_list" class="android.widget.ScrollView" scrollable="true" bounds="[0,0][1080,2400]" visible-to-user="true" />
            </hierarchy>
        """.trimIndent()

        // fresh 中目标控件丢失（如页面跳转或被清空）
        val freshXml = """
            <hierarchy>
              <node id="empty_root" class="android.widget.FrameLayout" bounds="[0,0][1080,2400]" visible-to-user="true" />
            </hierarchy>
        """.trimIndent()

        val fakePlatform = createFakePlatform(
            onObserve = { _, displayId ->
                observeCount++
                if (observeCount == 1) {
                    createPlatformState(xml = baselineXml, displayId = displayId)
                } else {
                    createPlatformState(xml = freshXml, displayId = displayId)
                }
            },
            onDispatch = { _, _ ->
                dispatchCount++
                AndroidGuiActionResult(true, "ok")
            },
        )

        val env = createEnvironment(context, fakePlatform)
        val obs = env.observeWithDiagnostics()

        val result = env.act(
            action = Action(
                "swipe",
                mapOf(
                    "node_resource_id" to "com.test.app:id/main_scroll_list",
                    "x1" to 500.0,
                    "y1" to 800.0,
                    "x2" to 500.0,
                    "y2" to 200.0,
                )
            ),
            expectedObservationId = obs.extra["observation_id"] as? String,
            awaitStabilization = false,
        )

        assertFalse(result.success)
        assertEquals("action_target_missing", result.message)
        assertEquals(0, dispatchCount)
    }

    @Test
    fun swipeTargetAmbiguousInFreshRejectsAndDispatchesZeroViaEnvironmentAct() = runBlocking {
        var dispatchCount = 0
        var observeCount = 0

        val baselineXml = """
            <hierarchy>
              <node id="list" resource-id="com.test.app:id/scroll_list" class="android.widget.ScrollView" scrollable="true" bounds="[0,0][1080,2400]" visible-to-user="true" />
            </hierarchy>
        """.trimIndent()

        // fresh 中出现两个同名可滚动列表，且起点 (540, 1920) 落在两者中间空白处，无法消歧
        val freshXml = """
            <hierarchy>
              <node id="list1" resource-id="com.test.app:id/scroll_list" class="android.widget.ScrollView" scrollable="true" bounds="[0,0][500,2400]" visible-to-user="true" />
              <node id="list2" resource-id="com.test.app:id/scroll_list" class="android.widget.ScrollView" scrollable="true" bounds="[580,0][1080,2400]" visible-to-user="true" />
            </hierarchy>
        """.trimIndent()

        val fakePlatform = createFakePlatform(
            onObserve = { _, displayId ->
                observeCount++
                if (observeCount == 1) {
                    createPlatformState(xml = baselineXml, displayId = displayId)
                } else {
                    createPlatformState(xml = freshXml, displayId = displayId)
                }
            },
            onDispatch = { _, _ ->
                dispatchCount++
                AndroidGuiActionResult(true, "ok")
            },
        )

        val env = createEnvironment(context, fakePlatform)
        val obs = env.observeWithDiagnostics()

        val result = env.act(
            action = Action(
                "swipe",
                mapOf(
                    "node_resource_id" to "com.test.app:id/scroll_list",
                    "x1" to 500.0,
                    "y1" to 800.0,
                    "x2" to 500.0,
                    "y2" to 200.0,
                )
            ),
            expectedObservationId = obs.extra["observation_id"] as? String,
            awaitStabilization = false,
        )

        assertFalse(result.success)
        assertEquals("action_target_ambiguous", result.message)
        assertEquals(0, dispatchCount)
    }

    @Test
    fun pureVisualSwipeStartPointRoiChangeRejectsAndDispatchesZeroViaEnvironmentAct() = runBlocking {
        var dispatchCount = 0
        var observeCount = 0

        // 纯视觉（XML 无节点），基线为红色底图
        val baselineScreenshot = createTestBitmap(color = Color.RED)
        // fresh 截图在起点 (540, 1920) 附近发生剧烈像素变化（绘制大蓝块）
        val freshScreenshot = createTestBitmap(
            color = Color.RED,
            drawRect = Rect(500, 1850, 580, 1950),
            rectColor = Color.BLUE,
        )

        val fakePlatform = createFakePlatform(
            onObserve = { _, displayId ->
                observeCount++
                if (observeCount == 1) {
                    createPlatformState(xml = "<hierarchy />", screenshotJpeg = baselineScreenshot, displayId = displayId)
                } else {
                    createPlatformState(xml = "<hierarchy />", screenshotJpeg = freshScreenshot, displayId = displayId)
                }
            },
            onDispatch = { _, _ ->
                dispatchCount++
                AndroidGuiActionResult(true, "ok")
            },
        )

        val env = createEnvironment(context, fakePlatform)
        val obs = env.observeWithDiagnostics()

        val result = env.act(
            action = Action(
                "swipe",
                mapOf(
                    "x1" to 500.0,
                    "y1" to 800.0,
                    "x2" to 500.0,
                    "y2" to 200.0,
                )
            ),
            expectedObservationId = obs.extra["observation_id"] as? String,
            awaitStabilization = false,
        )

        assertFalse(result.success)
        assertEquals("action_observation_expired", result.message)
        assertEquals(0, dispatchCount)
    }

    @Test
    fun pureVisualSwipeMidpointRoiChangeRejectsAndDispatchesZeroViaEnvironmentAct() = runBlocking {
        var dispatchCount = 0
        var observeCount = 0

        val baselineScreenshot = createTestBitmap(color = Color.RED)
        // 中点为 (540, 1200)，起点与终点均未改变，仅中点发生像素剧烈变化
        val freshScreenshot = createTestBitmap(
            color = Color.RED,
            drawRect = Rect(500, 1150, 580, 1250),
            rectColor = Color.BLUE,
        )

        val fakePlatform = createFakePlatform(
            onObserve = { _, displayId ->
                observeCount++
                if (observeCount == 1) {
                    createPlatformState(xml = "<hierarchy />", screenshotJpeg = baselineScreenshot, displayId = displayId)
                } else {
                    createPlatformState(xml = "<hierarchy />", screenshotJpeg = freshScreenshot, displayId = displayId)
                }
            },
            onDispatch = { _, _ ->
                dispatchCount++
                AndroidGuiActionResult(true, "ok")
            },
        )

        val env = createEnvironment(context, fakePlatform)
        val obs = env.observeWithDiagnostics()

        val result = env.act(
            action = Action(
                "swipe",
                mapOf(
                    "x1" to 500.0,
                    "y1" to 800.0,
                    "x2" to 500.0,
                    "y2" to 200.0,
                )
            ),
            expectedObservationId = obs.extra["observation_id"] as? String,
            awaitStabilization = false,
        )

        assertFalse(result.success)
        assertEquals("action_observation_expired", result.message)
        assertEquals(0, dispatchCount)
    }

    @Test
    fun pureVisualSwipeEndPointRoiChangeRejectsAndDispatchesZeroViaEnvironmentAct() = runBlocking {
        var dispatchCount = 0
        var observeCount = 0

        val baselineScreenshot = createTestBitmap(color = Color.RED)
        // 终点为 (540, 480)，终点附近发生像素变化
        val freshScreenshot = createTestBitmap(
            color = Color.RED,
            drawRect = Rect(500, 430, 580, 530),
            rectColor = Color.BLUE,
        )

        val fakePlatform = createFakePlatform(
            onObserve = { _, displayId ->
                observeCount++
                if (observeCount == 1) {
                    createPlatformState(xml = "<hierarchy />", screenshotJpeg = baselineScreenshot, displayId = displayId)
                } else {
                    createPlatformState(xml = "<hierarchy />", screenshotJpeg = freshScreenshot, displayId = displayId)
                }
            },
            onDispatch = { _, _ ->
                dispatchCount++
                AndroidGuiActionResult(true, "ok")
            },
        )

        val env = createEnvironment(context, fakePlatform)
        val obs = env.observeWithDiagnostics()

        val result = env.act(
            action = Action(
                "swipe",
                mapOf(
                    "x1" to 500.0,
                    "y1" to 800.0,
                    "x2" to 500.0,
                    "y2" to 200.0,
                )
            ),
            expectedObservationId = obs.extra["observation_id"] as? String,
            awaitStabilization = false,
        )

        assertFalse(result.success)
        assertEquals("action_observation_expired", result.message)
        assertEquals(0, dispatchCount)
    }

    @Test
    fun pureVisualSwipeMissingScreenshotRejectsAndDispatchesZeroViaEnvironmentAct() = runBlocking {
        var dispatchCount = 0
        var observeCount = 0

        val baselineScreenshot = createTestBitmap(color = Color.RED)

        val fakePlatform = createFakePlatform(
            onObserve = { _, displayId ->
                observeCount++
                if (observeCount == 1) {
                    createPlatformState(xml = "<hierarchy />", screenshotJpeg = baselineScreenshot, displayId = displayId)
                } else {
                    // fresh 缺失截图
                    createPlatformState(xml = "<hierarchy />", screenshotJpeg = null, displayId = displayId)
                }
            },
            onDispatch = { _, _ ->
                dispatchCount++
                AndroidGuiActionResult(true, "ok")
            },
        )

        val env = createEnvironment(context, fakePlatform)
        val obs = env.observeWithDiagnostics()

        val result = env.act(
            action = Action(
                "swipe",
                mapOf(
                    "x1" to 500.0,
                    "y1" to 800.0,
                    "x2" to 500.0,
                    "y2" to 200.0,
                )
            ),
            expectedObservationId = obs.extra["observation_id"] as? String,
            awaitStabilization = false,
        )

        assertFalse(result.success)
        assertEquals("action_observation_expired", result.message)
        assertEquals(0, dispatchCount)
    }

    @Test
    fun pureVisualSwipeUnchangedIntactScreenshotsDispatchesSuccessfullyViaEnvironmentAct() = runBlocking {
        var dispatchCount = 0
        var observeCount = 0

        val baselineScreenshot = createTestBitmap(color = Color.RED)
        val freshScreenshot = createTestBitmap(color = Color.RED)

        val fakePlatform = createFakePlatform(
            onObserve = { _, displayId ->
                observeCount++
                if (observeCount == 1) {
                    createPlatformState(xml = "<hierarchy />", screenshotJpeg = baselineScreenshot, displayId = displayId)
                } else {
                    createPlatformState(xml = "<hierarchy />", screenshotJpeg = freshScreenshot, displayId = displayId)
                }
            },
            onDispatch = { _, _ ->
                dispatchCount++
                AndroidGuiActionResult(true, "ok")
            },
        )

        val env = createEnvironment(context, fakePlatform)
        val obs = env.observeWithDiagnostics()

        val result = env.act(
            action = Action(
                "swipe",
                mapOf(
                    "x1" to 500.0,
                    "y1" to 800.0,
                    "x2" to 500.0,
                    "y2" to 200.0,
                )
            ),
            expectedObservationId = obs.extra["observation_id"] as? String,
            awaitStabilization = false,
        )

        assertTrue(result.success)
        assertEquals(1, dispatchCount)
    }

    @Test
    fun pureVisualSwipeFreshEntityCoveringPathRejectsAndDispatchesZeroViaEnvironmentAct() = runBlocking {
        var dispatchCount = 0
        var observeCount = 0

        val baselineScreenshot = createTestBitmap(color = Color.RED)
        val freshScreenshot = createTestBitmap(color = Color.RED)

        // 纯视觉基线无节点，但在 fresh 中确证出现一个可交互弹窗节点横跨滑动路径
        val freshXmlWithEntity = """
            <hierarchy>
              <node id="popup" class="android.app.Dialog" window-id="2" window-layer="20" bounds="[100,800][980,1600]" visible-to-user="true" clickable="true" />
            </hierarchy>
        """.trimIndent()

        val fakePlatform = createFakePlatform(
            onObserve = { _, displayId ->
                observeCount++
                if (observeCount == 1) {
                    createPlatformState(xml = "<hierarchy />", screenshotJpeg = baselineScreenshot, displayId = displayId)
                } else {
                    createPlatformState(xml = freshXmlWithEntity, screenshotJpeg = freshScreenshot, displayId = displayId)
                }
            },
            onDispatch = { _, _ ->
                dispatchCount++
                AndroidGuiActionResult(true, "ok")
            },
        )

        val env = createEnvironment(context, fakePlatform)
        val obs = env.observeWithDiagnostics()

        val result = env.act(
            action = Action(
                "swipe",
                mapOf(
                    "x1" to 500.0,
                    "y1" to 800.0,
                    "x2" to 500.0,
                    "y2" to 200.0,
                )
            ),
            expectedObservationId = obs.extra["observation_id"] as? String,
            awaitStabilization = false,
        )

        assertFalse(result.success)
        assertEquals("action_swipe_obstructed", result.message)
        assertEquals(0, dispatchCount)
    }

    @Test
    fun pureVisualSwipeCorruptedScreenshotRejectsAndDispatchesZeroViaEnvironmentAct() = runBlocking {
        var dispatchCount = 0
        var observeCount = 0

        val baselineScreenshot = createTestBitmap(color = Color.RED)
        // 损坏的截图字节数组（无法解码为有效 Bitmap）
        val corruptedFreshScreenshot = byteArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9)

        val fakePlatform = createFakePlatform(
            onObserve = { _, displayId ->
                observeCount++
                if (observeCount == 1) {
                    createPlatformState(xml = "<hierarchy />", screenshotJpeg = baselineScreenshot, displayId = displayId)
                } else {
                    createPlatformState(xml = "<hierarchy />", screenshotJpeg = corruptedFreshScreenshot, displayId = displayId)
                }
            },
            onDispatch = { _, _ ->
                dispatchCount++
                AndroidGuiActionResult(true, "ok")
            },
        )

        val env = createEnvironment(context, fakePlatform)
        val obs = env.observeWithDiagnostics()

        val result = env.act(
            action = Action(
                "swipe",
                mapOf(
                    "x1" to 500.0,
                    "y1" to 800.0,
                    "x2" to 500.0,
                    "y2" to 200.0,
                )
            ),
            expectedObservationId = obs.extra["observation_id"] as? String,
            awaitStabilization = false,
        )

        assertFalse("损坏的截图字节数组不得静默放行", result.success)
        assertTrue(
            "损坏截图拒绝原因应属于观测/显示不匹配集合: ${result.message}",
            result.message in setOf("action_observation_expired", "action_display_mismatch")
        )
        assertEquals(0, dispatchCount)
    }

    @Test
    fun swipeBaselineResolutionMismatchRejectsAndDispatchesZeroViaEnvironmentAct() = runBlocking {
        val baseline = AndroidGuiObservationBaseline(
            observationId = "obs_res_mismatch",
            displayId = 0,
            frameSequence = 1L,
            stateId = "state_res_mismatch",
            packageName = "com.test.app",
            activityName = "MainActivity",
            displayWidth = 720, // baseline 尺寸与当前屏幕 1080x2400 不一致
            displayHeight = 1280,
            xml = "<hierarchy />",
        )

        val guardResult = AndroidGuiSafetyGuard.prepareGuardedAction(
            action = Action("swipe", mapOf("x1" to 500.0, "y1" to 800.0, "x2" to 500.0, "y2" to 200.0)),
            expectedState = null,
            expectedObservationId = "obs_res_mismatch",
            baseline = baseline,
            currentDisplayId = 0,
            currentDisplaySize = displaySize,
            freshXml = "<hierarchy />",
        )

        assertTrue(guardResult is GuardResult.Rejected)
        val failure = (guardResult as GuardResult.Rejected).failure
        assertFalse(failure.success)
        assertEquals("action_display_mismatch", failure.message)
    }

    @Test
    fun swipeObstructedByAnonymousMidpointDialogWithoutIdRejectsAndDispatchesZeroViaEnvironmentAct() = runBlocking {
        var dispatchCount = 0
        var observeCount = 0

        // 基线状态：全树节点均无 id 属性（空字符串 id）的可滚动容器
        val baselineXml = """
            <hierarchy>
              <node class="android.widget.ScrollView" scrollable="true" bounds="[0,0][1080,2400]" window-id="1" window-layer="10" visible-to-user="true">
                <node class="android.widget.LinearLayout" bounds="[0,0][1080,4800]" visible-to-user="true">
                  <node text="条目1" bounds="[50,200][1030,400]" visible-to-user="true" />
                  <node text="条目2" bounds="[50,2000][1030,2200]" visible-to-user="true" />
                </node>
              </node>
            </hierarchy>
        """.trimIndent()

        // 实时状态：中途弹出无 id 的高层 Dialog 遮挡滑动轨迹正中央 [100,800][980,1600]
        val freshXml = """
            <hierarchy>
              <node class="android.widget.ScrollView" scrollable="true" bounds="[0,0][1080,2400]" window-id="1" window-layer="10" visible-to-user="true">
                <node class="android.widget.LinearLayout" bounds="[0,0][1080,4800]" visible-to-user="true">
                  <node text="条目1" bounds="[50,200][1030,400]" visible-to-user="true" />
                  <node text="条目2" bounds="[50,2000][1030,2200]" visible-to-user="true" />
                </node>
              </node>
              <node class="android.app.Dialog" window-id="2" window-layer="20" bounds="[100,800][980,1600]" visible-to-user="true" clickable="true">
                <node text="更新提醒" bounds="[200,900][880,1050]" visible-to-user="true" />
              </node>
            </hierarchy>
        """.trimIndent()

        val fakePlatform = createFakePlatform(
            onObserve = { _, displayId ->
                observeCount++
                if (observeCount == 1) {
                    createPlatformState(xml = baselineXml, displayId = displayId)
                } else {
                    createPlatformState(xml = freshXml, displayId = displayId)
                }
            },
            onDispatch = { _, _ ->
                dispatchCount++
                AndroidGuiActionResult(true, "ok")
            },
        )

        val env = createEnvironment(context, fakePlatform)
        val obs = env.observeWithDiagnostics()

        val result = env.act(
            action = Action(
                "swipe",
                mapOf(
                    "x1" to 500.0,
                    "y1" to 800.0,
                    "x2" to 500.0,
                    "y2" to 200.0,
                )
            ),
            expectedObservationId = obs.extra["observation_id"] as? String,
            awaitStabilization = false,
        )

        assertFalse("全树无id时中途高层弹窗遮挡必须被拒绝", result.success)
        assertEquals("action_swipe_obstructed", result.message)
        assertEquals("无id高层弹窗遮挡时底层派发次数必须为0", 0, dispatchCount)
    }

    @Test
    fun actWithConfirmedBaselineTargetPopulatesTargetConfirmedDiagnostics() = runBlocking {
        val buttonXml = """
            <hierarchy>
              <node class="android.widget.FrameLayout" bounds="[0,0][1080,2400]" visible-to-user="true">
                <node id="1" resource-id="com.example:id/submit_btn" class="android.widget.Button" text="确定" bounds="[100,200][500,400]" visible-to-user="true" clickable="true" />
              </node>
            </hierarchy>
        """.trimIndent()

        val fakePlatform = createFakePlatform(
            onObserve = { _, displayId ->
                createPlatformState(xml = buttonXml, displayId = displayId)
            },
            onDispatch = { _, _ ->
                AndroidGuiActionResult(true, "ok")
            },
        )

        val env = createEnvironment(context, fakePlatform)
        val obs = env.observeWithDiagnostics()
        val obsId = obs.extra["observation_id"] as String

        val result = env.act(
            action = Action(
                tool = "click",
                args = mapOf(
                    "x" to 250.0 / 1080.0 * 1000.0,
                    "y" to 300.0 / 2400.0 * 1000.0,
                    "node_resource_id" to "com.example:id/submit_btn",
                    "target_description" to "确定",
                ),
            ),
            expectedObservationId = obsId,
            awaitStabilization = false,
        )

        assertTrue(result.success)
        assertEquals("ok", result.message)
        assertEquals("target_confirmed", result.diagnostics["guard_status"])
        assertEquals(obsId, result.diagnostics["observation_id"])
        assertEquals("com.example:id/submit_btn", result.diagnostics["target_resource_id"])
        assertEquals("android.widget.Button", result.diagnostics["target_class"])
        assertEquals("host_completed", result.diagnostics["state_stabilization"])
        assertEquals("runtime_delegated", result.diagnostics["state_stabilization_result"])
    }

    @Test
    fun actWhenDispatchFailsPreservesTargetConfirmedDiagnosticsWithoutMarkingSuccess() = runBlocking {
        val buttonXml = """
            <hierarchy>
              <node class="android.widget.FrameLayout" bounds="[0,0][1080,2400]" visible-to-user="true">
                <node id="1" resource-id="com.example:id/submit_btn" class="android.widget.Button" text="确定" bounds="[100,200][500,400]" visible-to-user="true" clickable="true" />
              </node>
            </hierarchy>
        """.trimIndent()

        val fakePlatform = createFakePlatform(
            onObserve = { _, displayId ->
                createPlatformState(xml = buttonXml, displayId = displayId)
            },
            onDispatch = { _, _ ->
                AndroidGuiActionResult(
                    success = false,
                    message = "input_gesture_rejected",
                    diagnostics = mapOf("reason" to "hardware_busy"),
                )
            },
        )

        val env = createEnvironment(context, fakePlatform)
        val obs = env.observeWithDiagnostics()
        val obsId = obs.extra["observation_id"] as String

        val result = env.act(
            action = Action(
                tool = "click",
                args = mapOf(
                    "x" to 250.0 / 1080.0 * 1000.0,
                    "y" to 300.0 / 2400.0 * 1000.0,
                    "node_resource_id" to "com.example:id/submit_btn",
                ),
            ),
            expectedObservationId = obsId,
            awaitStabilization = false,
        )

        assertFalse(result.success)
        assertEquals("input_gesture_rejected", result.message)
        assertEquals("target_confirmed", result.diagnostics["guard_status"])
        assertEquals(obsId, result.diagnostics["observation_id"])
        assertEquals("com.example:id/submit_btn", result.diagnostics["target_resource_id"])
        assertEquals("android.widget.Button", result.diagnostics["target_class"])
        assertEquals("hardware_busy", result.diagnostics["reason"])
    }

    @Test
    fun actWithoutExpectedObservationIdOrTargetCluesDoesNotSetTargetConfirmed() = runBlocking {
        val buttonXml = """
            <hierarchy>
              <node class="android.widget.FrameLayout" bounds="[0,0][1080,2400]" visible-to-user="true">
                <node id="1" resource-id="com.example:id/submit_btn" class="android.widget.Button" text="确定" bounds="[100,200][500,400]" visible-to-user="true" clickable="true" />
              </node>
            </hierarchy>
        """.trimIndent()

        val fakePlatform = createFakePlatform(
            onObserve = { _, displayId ->
                createPlatformState(xml = buttonXml, displayId = displayId)
            },
            onDispatch = { _, _ ->
                AndroidGuiActionResult(true, "ok")
            },
        )

        val env = createEnvironment(context, fakePlatform)
        val obs = env.observeWithDiagnostics()
        val obsId = obs.extra["observation_id"] as String

        // 无 expectedObservationId
        val resultNoExpectedObs = env.act(
            action = Action(
                tool = "click",
                args = mapOf(
                    "x" to 250.0 / 1080.0 * 1000.0,
                    "y" to 300.0 / 2400.0 * 1000.0,
                    "node_resource_id" to "com.example:id/submit_btn",
                ),
            ),
            expectedObservationId = null,
            awaitStabilization = false,
        )
        assertTrue(resultNoExpectedObs.success)
        assertNull(resultNoExpectedObs.diagnostics["guard_status"])

        // 纯坐标点击
        val resultPureCoord = env.act(
            action = Action(
                tool = "click",
                args = mapOf(
                    "x" to 250.0 / 1080.0 * 1000.0,
                    "y" to 300.0 / 2400.0 * 1000.0,
                ),
            ),
            expectedObservationId = obsId,
            awaitStabilization = false,
        )
        assertTrue(resultPureCoord.success)
        assertNull(resultPureCoord.diagnostics["guard_status"])
    }

    @Test
    fun actWithLongPressAndInputTextPopulatesTargetConfirmed() = runBlocking {
        val formXml = """
            <hierarchy>
              <node class="android.widget.FrameLayout" bounds="[0,0][1080,2400]" visible-to-user="true">
                <node id="1" resource-id="com.example:id/long_btn" class="android.widget.Button" bounds="[100,200][500,400]" visible-to-user="true" long-clickable="true" />
                <node id="2" resource-id="com.example:id/input_box" class="android.widget.EditText" bounds="[100,500][800,700]" visible-to-user="true" editable="true" />
              </node>
            </hierarchy>
        """.trimIndent()

        val fakePlatform = createFakePlatform(
            onObserve = { _, displayId ->
                createPlatformState(xml = formXml, displayId = displayId)
            },
            onDispatch = { _, _ ->
                AndroidGuiActionResult(true, "ok")
            },
        )

        val env = createEnvironment(context, fakePlatform)
        val obs = env.observeWithDiagnostics()
        val obsId = obs.extra["observation_id"] as String

        val resultLongPress = env.act(
            action = Action(
                tool = "long_press",
                args = mapOf(
                    "x" to 250.0 / 1080.0 * 1000.0,
                    "y" to 300.0 / 2400.0 * 1000.0,
                    "duration_ms" to 800,
                    "node_resource_id" to "com.example:id/long_btn",
                ),
            ),
            expectedObservationId = obsId,
            awaitStabilization = false,
        )
        assertTrue(resultLongPress.success)
        assertEquals("target_confirmed", resultLongPress.diagnostics["guard_status"])
        assertEquals("com.example:id/long_btn", resultLongPress.diagnostics["target_resource_id"])
        assertEquals("android.widget.Button", resultLongPress.diagnostics["target_class"])

        val resultInput = env.act(
            action = Action(
                tool = "input_text",
                args = mapOf(
                    "node_resource_id" to "com.example:id/input_box",
                    "text" to "confidential_text",
                ),
            ),
            expectedObservationId = obsId,
            awaitStabilization = false,
        )
        assertTrue(resultInput.success)
        assertEquals("target_confirmed", resultInput.diagnostics["guard_status"])
        assertEquals("com.example:id/input_box", resultInput.diagnostics["target_resource_id"])
        assertEquals("android.widget.EditText", resultInput.diagnostics["target_class"])
        assertNull(resultInput.diagnostics["text"])
        assertNull(resultInput.diagnostics["confidential_text"])
    }

    @Test
    fun actWithInteractiveAncestorPromotionReflectsOperatingTargetAndRejectsNoIdAncestor() = runBlocking {
        val buttonWithLabelXml = """
            <hierarchy>
              <node class="android.widget.FrameLayout" bounds="[0,0][1080,2400]" visible-to-user="true">
                <node id="btn" resource-id="com.example:id/checkout_btn" class="android.widget.Button" bounds="[100,200][500,400]" visible-to-user="true" clickable="true">
                  <node id="label" resource-id="com.example:id/checkout_label" class="android.widget.TextView" text="去结算" bounds="[150,250][450,350]" visible-to-user="true" />
                </node>
              </node>
            </hierarchy>
        """.trimIndent()

        val buttonWithoutResIdXml = """
            <hierarchy>
              <node class="android.widget.FrameLayout" bounds="[0,0][1080,2400]" visible-to-user="true">
                <node id="btn" class="android.widget.Button" bounds="[100,200][500,400]" visible-to-user="true" clickable="true">
                  <node id="label" resource-id="com.example:id/checkout_label" class="android.widget.TextView" text="去结算" bounds="[150,250][450,350]" visible-to-user="true" />
                </node>
              </node>
            </hierarchy>
        """.trimIndent()

        var currentXml = buttonWithLabelXml
        val fakePlatform = createFakePlatform(
            onObserve = { _, displayId ->
                createPlatformState(xml = currentXml, displayId = displayId)
            },
            onDispatch = { _, _ ->
                AndroidGuiActionResult(true, "ok")
            },
        )

        val env = createEnvironment(context, fakePlatform)
        val obs1 = env.observeWithDiagnostics()
        val obsId1 = obs1.extra["observation_id"] as String

        val resultPromoted = env.act(
            action = Action(
                tool = "click",
                args = mapOf(
                    "x" to 300.0 / 1080.0 * 1000.0,
                    "y" to 300.0 / 2400.0 * 1000.0,
                    "target_description" to "去结算",
                ),
            ),
            expectedObservationId = obsId1,
            awaitStabilization = false,
        )
        assertTrue(resultPromoted.success)
        assertEquals("target_confirmed", resultPromoted.diagnostics["guard_status"])
        assertEquals("com.example:id/checkout_btn", resultPromoted.diagnostics["target_resource_id"])
        assertEquals("android.widget.Button", resultPromoted.diagnostics["target_class"])

        currentXml = buttonWithoutResIdXml
        val obs2 = env.observeWithDiagnostics()
        val obsId2 = obs2.extra["observation_id"] as String

        val resultNoIdAncestor = env.act(
            action = Action(
                tool = "click",
                args = mapOf(
                    "x" to 300.0 / 1080.0 * 1000.0,
                    "y" to 300.0 / 2400.0 * 1000.0,
                    "target_description" to "去结算",
                ),
            ),
            expectedObservationId = obsId2,
            awaitStabilization = false,
        )
        assertTrue(resultNoIdAncestor.success)
        assertNull(resultNoIdAncestor.diagnostics["guard_status"])
    }

    @Test
    fun actWithTargetMissingOrInvisibleInFreshRejectsWithoutTargetConfirmed() = runBlocking {
        val baselineXml = """
            <hierarchy>
              <node class="android.widget.FrameLayout" bounds="[0,0][1080,2400]" visible-to-user="true">
                <node id="1" resource-id="com.example:id/submit_btn" class="android.widget.Button" text="确定" bounds="[100,200][500,400]" visible-to-user="true" clickable="true" />
              </node>
            </hierarchy>
        """.trimIndent()
        val emptyFreshXml = "<hierarchy />"
        val invisibleFreshXml = """
            <hierarchy>
              <node class="android.widget.FrameLayout" bounds="[0,0][1080,2400]" visible-to-user="true">
                <node id="1" resource-id="com.example:id/submit_btn" class="android.widget.Button" text="确定" bounds="[100,200][500,400]" visible-to-user="false" clickable="true" />
              </node>
            </hierarchy>
        """.trimIndent()

        var observeCount = 0
        var freshXml = emptyFreshXml
        val fakePlatform = createFakePlatform(
            onObserve = { _, displayId ->
                observeCount++
                if (observeCount == 1) {
                    createPlatformState(xml = baselineXml, displayId = displayId)
                } else {
                    createPlatformState(xml = freshXml, displayId = displayId)
                }
            },
            onDispatch = { _, _ ->
                AndroidGuiActionResult(true, "ok")
            },
        )

        val env = createEnvironment(context, fakePlatform)
        val obs = env.observeWithDiagnostics()
        val obsId = obs.extra["observation_id"] as String

        val resultMissing = env.act(
            action = Action(
                tool = "click",
                args = mapOf(
                    "x" to 250.0 / 1080.0 * 1000.0,
                    "y" to 300.0 / 2400.0 * 1000.0,
                    "node_resource_id" to "com.example:id/submit_btn",
                ),
            ),
            expectedObservationId = obsId,
            awaitStabilization = false,
        )
        assertFalse(resultMissing.success)
        assertEquals("action_target_missing", resultMissing.message)
        assertNull(resultMissing.diagnostics["guard_status"])

        observeCount = 0
        freshXml = invisibleFreshXml
        val obs2 = env.observeWithDiagnostics()
        val obsId2 = obs2.extra["observation_id"] as String

        val resultInvisible = env.act(
            action = Action(
                tool = "click",
                args = mapOf(
                    "x" to 250.0 / 1080.0 * 1000.0,
                    "y" to 300.0 / 2400.0 * 1000.0,
                    "node_resource_id" to "com.example:id/submit_btn",
                ),
            ),
            expectedObservationId = obsId2,
            awaitStabilization = false,
        )
        assertFalse(resultInvisible.success)
        assertEquals("action_target_invisible", resultInvisible.message)
        assertNull(resultInvisible.diagnostics["guard_status"])
    }

    @Test
    fun actWithClearPointAdjustedRetainsAdjustedCoordsAndSetsTargetConfirmed() = runBlocking {
        val occludedXml = """
            <hierarchy>
              <node id="root" window-id="1" bounds="[0,0][1080,2400]" visible-to-user="true">
                <node id="target" resource-id="com.app:id/save_btn" class="android.widget.Button" drawing-order="1" text="保存" bounds="[100,200][500,600]" visible-to-user="true" clickable="true" />
                <node id="banner" drawing-order="2" bounds="[100,200][500,400]" visible-to-user="true" clickable="true" />
              </node>
            </hierarchy>
        """.trimIndent()

        val fakePlatform = createFakePlatform(
            onObserve = { _, displayId ->
                createPlatformState(xml = occludedXml, displayId = displayId)
            },
            onDispatch = { _, _ ->
                AndroidGuiActionResult(true, "ok")
            },
        )

        val env = createEnvironment(context, fakePlatform)
        val obs = env.observeWithDiagnostics()
        val obsId = obs.extra["observation_id"] as String

        val result = env.act(
            action = Action(
                tool = "click",
                args = mapOf(
                    "target_description" to "保存",
                    "x" to 250.0 / 1080.0 * 1000.0,
                    "y" to 300.0 / 2400.0 * 1000.0,
                ),
            ),
            expectedObservationId = obsId,
            awaitStabilization = false,
        )

        assertTrue(result.success)
        assertNotNull(result.diagnostics["clear_point_adjusted"])
        assertNotNull(result.diagnostics["original_coords"])
        assertEquals("target_confirmed", result.diagnostics["guard_status"])
        assertEquals(obsId, result.diagnostics["observation_id"])
        assertEquals("com.app:id/save_btn", result.diagnostics["target_resource_id"])
        assertEquals("android.widget.Button", result.diagnostics["target_class"])
    }
}
