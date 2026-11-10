package cn.com.omnimind.androidgui

import android.app.Application
import android.graphics.Rect
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
}
