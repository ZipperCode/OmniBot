package cn.com.omnimind.androidgui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import cn.com.omnimind.baselib.runlog.Action
import cn.com.omnimind.baselib.runlog.State
import cn.com.omnimind.baselib.runlog.OobActionSchema
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader
import kotlin.math.abs
import kotlin.math.hypot

/**
 * 树节点内存结构，携带完整无障碍与窗口/层级/绘制顺序元数据。
 */
data class AndroidGuiNode(
    val id: String,
    val windowId: Int = 0,
    val drawingOrder: Int? = null,
    val windowLayer: Int? = null,
    val windowType: Int? = null,
    val text: String? = null,
    val contentDesc: String? = null,
    val hintText: String? = null,
    val resourceId: String? = null,
    val className: String? = null,
    val packageName: String? = null,
    val bounds: Rect = Rect(),
    val clickable: Boolean = false,
    val longClickable: Boolean = false,
    val focusable: Boolean = false,
    val focused: Boolean = false,
    val scrollable: Boolean = false,
    val editable: Boolean = false,
    val selected: Boolean = false,
    val enabled: Boolean = true,
    val visibleToUser: Boolean = true,
    val checkable: Boolean = false,
    val checked: Boolean = false,
    val children: MutableList<AndroidGuiNode> = mutableListOf(),
    var parent: AndroidGuiNode? = null,
) {
    val isInteractive: Boolean
        get() = clickable || longClickable || focusable || scrollable || editable || checkable
}

/**
 * 遮挡状态枚举。
 */
enum class OcclusionType {
    NONE,
    PARTIAL,
    FULL,
}

/**
 * 遮挡检测结果，若为部分遮挡则提供确证净空坐标点。
 */
data class OcclusionCheckResult(
    val type: OcclusionType,
    val occludingNode: AndroidGuiNode? = null,
    val clearPoint: Pair<Int, Int>? = null,
    val reason: String? = null,
)

/**
 * 守卫执行结果：包含放行（允许派发，可选附带安全净空点调整后的 Action）与拒绝（明确失败结果）。
 */
sealed class GuardResult {
    data class Allowed(
        val action: Action,
        val adjustedCoordinate: Pair<Int, Int>? = null,
        val diagnostics: Map<String, String> = emptyMap(),
    ) : GuardResult()

    data class Rejected(
        val failure: AndroidGuiActionResult,
    ) : GuardResult()
}

/**
 * Baseline 目标查找密封结果：区分找到、无候选、存在歧义、明确身份冲突。
 */
sealed class BaselineTargetLookup {
    data class Found(val node: AndroidGuiNode) : BaselineTargetLookup()
    object NoCandidate : BaselineTargetLookup()
    data class Ambiguous(val count: Int) : BaselineTargetLookup()
    data class ExplicitIdentityMismatch(val reason: String) : BaselineTargetLookup()
}

/**
 * 短生命周期单次观察内存基线。
 */
data class AndroidGuiObservationBaseline(
    val observationId: String,
    val displayId: Int,
    val frameSequence: Long?,
    val stateId: String,
    val packageName: String,
    val activityName: String,
    val displayWidth: Int,
    val displayHeight: Int,
    val xml: String,
    val screenshotJpeg: ByteArray? = null,
    val timestamp: Long = System.currentTimeMillis(),
)

object AndroidGuiSafetyGuard {

    // 支持负数坐标（例如滑动出界或抽屉菜单）
    private val BOUNDS_REGEX = Regex("\\[(-?\\d+),(-?\\d+)\\]\\[(-?\\d+),(-?\\d+)\\]")

    /**
     * 将 XML 字符串解析为 AndroidGuiNode 树状列表。
     */
    fun parseHierarchy(xml: String): List<AndroidGuiNode> {
        if (xml.isBlank()) return emptyList()
        val roots = mutableListOf<AndroidGuiNode>()
        runCatching {
            val factory = XmlPullParserFactory.newInstance()
            val parser = factory.newPullParser()
            parser.setInput(StringReader(xml))
            var eventType = parser.eventType
            val nodeStack = ArrayDeque<AndroidGuiNode>()

            while (eventType != XmlPullParser.END_DOCUMENT) {
                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        if (parser.name == "node") {
                            val id = parser.getAttributeValue(null, "id").orEmpty()
                            val windowId = parser.getAttributeValue(null, "window-id")?.toIntOrNull() ?: 0
                            val drawingOrder = parser.getAttributeValue(null, "drawing-order")?.toIntOrNull()
                            val windowLayer = parser.getAttributeValue(null, "window-layer")?.toIntOrNull()
                            val windowType = parser.getAttributeValue(null, "window-type")?.toIntOrNull()
                            val text = parser.getAttributeValue(null, "text")
                            val contentDesc = parser.getAttributeValue(null, "content-desc")
                            val hintText = parser.getAttributeValue(null, "hint-text")
                            val resourceId = parser.getAttributeValue(null, "resource-id")
                            val className = parser.getAttributeValue(null, "class")
                            val packageName = parser.getAttributeValue(null, "package")
                            val clickable = parser.getAttributeValue(null, "clickable") == "true"
                            val longClickable = parser.getAttributeValue(null, "long-clickable") == "true"
                            val focusable = parser.getAttributeValue(null, "focusable") == "true"
                            val focused = parser.getAttributeValue(null, "focused") == "true"
                            val scrollable = parser.getAttributeValue(null, "scrollable") == "true"
                            val editable = parser.getAttributeValue(null, "editable") == "true"
                            val selected = parser.getAttributeValue(null, "selected") == "true"
                            val enabled = parser.getAttributeValue(null, "enabled") != "false"
                            val visibleToUser = parser.getAttributeValue(null, "visible-to-user") != "false"
                            val checkable = parser.getAttributeValue(null, "checkable") == "true"
                            val checked = parser.getAttributeValue(null, "checked") == "true"
                            val boundsStr = parser.getAttributeValue(null, "bounds").orEmpty()
                            val bounds = parseBounds(boundsStr)

                            val node = AndroidGuiNode(
                                id = id,
                                windowId = windowId,
                                drawingOrder = drawingOrder,
                                windowLayer = windowLayer,
                                windowType = windowType,
                                text = text,
                                contentDesc = contentDesc,
                                hintText = hintText,
                                resourceId = resourceId,
                                className = className,
                                packageName = packageName,
                                bounds = bounds,
                                clickable = clickable,
                                longClickable = longClickable,
                                focusable = focusable,
                                focused = focused,
                                scrollable = scrollable,
                                editable = editable,
                                selected = selected,
                                enabled = enabled,
                                visibleToUser = visibleToUser,
                                checkable = checkable,
                                checked = checked,
                            )

                            if (nodeStack.isEmpty()) {
                                roots.add(node)
                            } else {
                                val parent = nodeStack.last()
                                node.parent = parent
                                parent.children.add(node)
                            }
                            nodeStack.addLast(node)
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        if (parser.name == "node" && nodeStack.isNotEmpty()) {
                            nodeStack.removeLast()
                        }
                    }
                }
                eventType = parser.next()
            }
        }
        return roots
    }

    /**
     * 平铺遍历所有节点。
     */
    fun flattenNodes(roots: List<AndroidGuiNode>): List<AndroidGuiNode> {
        val list = mutableListOf<AndroidGuiNode>()
        fun collect(node: AndroidGuiNode) {
            list.add(node)
            node.children.forEach(::collect)
        }
        roots.forEach(::collect)
        return list
    }

    /**
     * 寻找最近的可交互祖先节点（与 Python 语义接地逻辑保持一致）。
     */
    fun findNearestInteractiveNode(node: AndroidGuiNode): AndroidGuiNode {
        var curr: AndroidGuiNode? = node
        while (curr != null) {
            if (curr.isInteractive) {
                return curr
            }
            curr = curr.parent
        }
        return node
    }

    /**
     * 判断无障碍树中是否缺少可用文字（用于自动 OCR 触发判断）。
     */
    fun treeLacksUsableText(xml: String): Boolean {
        if (xml.isBlank()) return true
        val nodes = flattenNodes(parseHierarchy(xml))
        val textCount = nodes.count { node ->
            node.visibleToUser && (
                !node.text.isNullOrBlank() ||
                !node.contentDesc.isNullOrBlank() ||
                !node.hintText.isNullOrBlank()
            )
        }
        return textCount == 0
    }

    /**
     * 遮挡判定中枢：
     * 1. 汇总所有确证 blocker（同级高绘序且可交互节点、或高层窗口覆盖实体）；
     * 2. 扣除全部 blocker 得到剩余净空矩形集合；
     * 3. 若未被遮挡返回 NONE；若部分遮挡且 actionPoint 被盖住，计算确证净空点；若全遮挡或净空不可用返回 FULL。
     */
    fun checkOcclusion(
        targetNode: AndroidGuiNode,
        allNodes: List<AndroidGuiNode>,
        displaySize: Pair<Int, Int>,
        actionPoint: Pair<Int, Int>? = null,
    ): OcclusionCheckResult {
        val viewport = Rect(0, 0, displaySize.first, displaySize.second)
        val targetRect = Rect()
        if (!targetRect.setIntersect(targetNode.bounds, viewport) || targetRect.isEmpty) {
            return OcclusionCheckResult(OcclusionType.NONE)
        }

        val blockers = mutableListOf<Rect>()
        var primaryOccludingNode: AndroidGuiNode? = null

        for (candidate in allNodes) {
            if (candidate.id == targetNode.id) continue
            if (!candidate.visibleToUser || candidate.bounds.isEmpty) continue

            // 规则 1：同窗口同级（sibling）且绘制顺序明确更高；必须自身可交互（clickable/focusable 等）才认作触摸遮挡
            val isSiblingTouchBlocker = candidate.windowId == targetNode.windowId &&
                candidate.parent != null &&
                candidate.parent?.id == targetNode.parent?.id &&
                candidate.drawingOrder != null &&
                targetNode.drawingOrder != null &&
                candidate.drawingOrder > targetNode.drawingOrder &&
                candidate.isInteractive

            // 规则 2：更高窗口层，且自身非透明占位、具备交互能力或具备非全屏实体内容
            val isHigherWindowBlocker = candidate.windowLayer != null &&
                targetNode.windowLayer != null &&
                candidate.windowLayer > targetNode.windowLayer &&
                (candidate.isInteractive || candidate.bounds.width() < displaySize.first || candidate.bounds.height() < displaySize.second || !candidate.text.isNullOrBlank())

            if (!isSiblingTouchBlocker && !isHigherWindowBlocker) {
                continue
            }

            val intersect = Rect()
            if (intersect.setIntersect(targetRect, candidate.bounds) && !intersect.isEmpty) {
                blockers.add(intersect)
                if (primaryOccludingNode == null) {
                    primaryOccludingNode = candidate
                }
            }
        }

        if (blockers.isEmpty()) {
            return OcclusionCheckResult(OcclusionType.NONE)
        }

        // 汇总扣除所有 blocker 矩形
        var unoccludedPieces = listOf(targetRect)
        for (blocker in blockers) {
            unoccludedPieces = unoccludedPieces.flatMap { piece -> subtractRect(piece, blocker) }
        }

        val totalUnoccludedArea = unoccludedPieces.sumOf { it.width() * it.height() }

        // 若扣除全部 blocker 后净空面积几乎归零（小于 10x10），判为全遮挡
        if (unoccludedPieces.isEmpty() || totalUnoccludedArea < 100) {
            return OcclusionCheckResult(
                type = OcclusionType.FULL,
                occludingNode = primaryOccludingNode,
                reason = "fully_occluded_by_blockers",
            )
        }

        // 筛选可点击的安全净空子块（长宽均至少 12 像素）
        val safePieces = unoccludedPieces.filter { it.width() >= 12 && it.height() >= 12 }

        if (safePieces.isEmpty()) {
            return OcclusionCheckResult(
                type = OcclusionType.FULL,
                occludingNode = primaryOccludingNode,
                reason = "no_usable_clear_area_remaining",
            )
        }

        if (actionPoint != null) {
            val isActionPointBlocked = blockers.any { it.contains(actionPoint.first, actionPoint.second) }
            if (!isActionPointBlocked) {
                // 原点击点落在安全净空区，安全放行
                return OcclusionCheckResult(
                    type = OcclusionType.PARTIAL,
                    occludingNode = primaryOccludingNode,
                    clearPoint = actionPoint,
                    reason = "partially_occluded_action_point_clear",
                )
            }

            // 原点击点被盖住，从最大净空块中提供确证净空中心
            val bestPiece = safePieces.maxByOrNull { it.width() * it.height() }!!
            val clearCenter = Pair(bestPiece.centerX(), bestPiece.centerY())
            return OcclusionCheckResult(
                type = OcclusionType.PARTIAL,
                occludingNode = primaryOccludingNode,
                clearPoint = clearCenter,
                reason = "partially_occluded_clear_point_found",
            )
        }

        val bestPiece = safePieces.maxByOrNull { it.width() * it.height() }!!
        return OcclusionCheckResult(
            type = OcclusionType.PARTIAL,
            occludingNode = primaryOccludingNode,
            clearPoint = Pair(bestPiece.centerX(), bestPiece.centerY()),
            reason = "partially_occluded",
        )
    }

    /**
     * 矩形差集分解算法：从 subject 中扣除 clip 区域，返回剩余有效子矩形列表。
     */
    private fun subtractRect(subject: Rect, clip: Rect): List<Rect> {
        val intersect = Rect()
        if (!intersect.setIntersect(subject, clip)) {
            return listOf(subject)
        }
        val result = mutableListOf<Rect>()
        if (intersect.top > subject.top) {
            result.add(Rect(subject.left, subject.top, subject.right, intersect.top))
        }
        if (intersect.bottom < subject.bottom) {
            result.add(Rect(subject.left, intersect.bottom, subject.right, subject.bottom))
        }
        if (intersect.left > subject.left) {
            result.add(Rect(subject.left, intersect.top, intersect.left, intersect.bottom))
        }
        if (intersect.right < subject.right) {
            result.add(Rect(intersect.right, intersect.top, subject.right, intersect.bottom))
        }
        return result.filter { it.width() > 0 && it.height() > 0 }
    }

    /**
     * 动作前置安全核验与净空点调整单一入口：
     * - 校验跨 display、快照 observation_id 及 expectedState 与 baseline 一致性；
     * - 依据 baseline 确证目标语义（含子树文字与父子关系），在 fresh 树中消歧定位；
     * - 旧坐标离开已确认目标 bounds 即刻拒绝 action_target_moved；
     * - 全遮挡拒绝；部分遮挡若原坐标被盖住，自动提取确证净空点重写 Action 坐标放行派发；
     * - 树中本就没有的目标（OCR/图像），通过相对新增窗口覆盖与局部 ROI 像素变化判定，不冒充 missing；
     * - 缺新图但有 baseline 图需要 ROI 时明确拒绝。
     */
    fun prepareGuardedAction(
        action: Action,
        expectedState: State?,
        expectedObservationId: String?,
        baseline: AndroidGuiObservationBaseline?,
        currentDisplayId: Int,
        currentDisplaySize: Pair<Int, Int>,
        freshXml: String,
        freshPackageName: String = "",
        freshScreenshotJpeg: ByteArray? = null,
    ): GuardResult {
        // 1. 跨 display 隔离核验
        if (baseline != null && baseline.displayId != currentDisplayId) {
            return GuardResult.Rejected(
                AndroidGuiActionResult(
                    success = false,
                    message = "action_display_mismatch",
                    diagnostics = mapOf(
                        "baseline_display" to baseline.displayId.toString(),
                        "current_display" to currentDisplayId.toString(),
                    ),
                )
            )
        }
        if (baseline != null && (baseline.displayWidth != currentDisplaySize.first || baseline.displayHeight != currentDisplaySize.second)) {
            return GuardResult.Rejected(
                AndroidGuiActionResult(
                    success = false,
                    message = "action_display_mismatch",
                    diagnostics = mapOf(
                        "baseline_resolution" to "${baseline.displayWidth}x${baseline.displayHeight}",
                        "current_resolution" to "${currentDisplaySize.first}x${currentDisplaySize.second}",
                    ),
                )
            )
        }

        // 2. 观测身份核验（旧快照拒绝）
        if (expectedObservationId != null) {
            if (baseline == null || baseline.observationId != expectedObservationId) {
                return GuardResult.Rejected(
                    AndroidGuiActionResult(
                        success = false,
                        message = "action_observation_expired",
                        diagnostics = mapOf(
                            "expected_observation_id" to expectedObservationId,
                            "actual_observation_id" to (baseline?.observationId ?: "none"),
                        ),
                    )
                )
            }
        }

        // 3. 期望状态与基线/实时状态校验
        if (expectedState != null) {
            if (baseline != null && expectedState.stateId.isNotBlank() && expectedState.stateId != baseline.stateId) {
                return GuardResult.Rejected(
                    AndroidGuiActionResult(
                        success = false,
                        message = "action_observation_expired",
                        diagnostics = mapOf(
                            "expected_state_id" to expectedState.stateId,
                            "baseline_state_id" to baseline.stateId,
                        ),
                    )
                )
            }
            if (expectedState.displayWidth != currentDisplaySize.first ||
                expectedState.displayHeight != currentDisplaySize.second
            ) {
                return GuardResult.Rejected(
                    AndroidGuiActionResult(
                        success = false,
                        message = "action_display_mismatch",
                        diagnostics = mapOf(
                            "expected_resolution" to "${expectedState.displayWidth}x${expectedState.displayHeight}",
                            "current_resolution" to "${currentDisplaySize.first}x${currentDisplaySize.second}",
                        ),
                    )
                )
            }
            if (expectedState.packageName.isNotBlank() && freshPackageName.isNotBlank() &&
                expectedState.packageName != freshPackageName
            ) {
                return GuardResult.Rejected(
                    AndroidGuiActionResult(
                        success = false,
                        message = "action_target_missing",
                        diagnostics = mapOf(
                            "reason" to "package_changed",
                            "expected_package" to expectedState.packageName,
                            "current_package" to freshPackageName,
                        ),
                    )
                )
            }
        }

        val freshRoots = parseHierarchy(freshXml)
        val freshNodes = flattenNodes(freshRoots)
        val baselineRoots = if (!baseline?.xml.isNullOrBlank()) {
            parseHierarchy(baseline!!.xml)
        } else {
            emptyList()
        }
        val baselineNodes = if (baselineRoots.isNotEmpty()) {
            flattenNodes(baselineRoots)
        } else {
            emptyList()
        }

        if (action.tool == OobActionSchema.TOOL_SWIPE) {
            return checkSwipePath(
                action = action,
                baseline = baseline,
                currentDisplaySize = currentDisplaySize,
                baselineNodes = baselineNodes,
                baselineRoots = baselineRoots,
                freshNodes = freshNodes,
                freshRoots = freshRoots,
                freshScreenshotJpeg = freshScreenshotJpeg,
            )
        }

        val targetDesc = action.args["target_description"] as? String
        val nodeId = action.args["node_id"]?.toString()
        val resId = action.args["node_resource_id"] as? String
        val rawX = (action.args["x"] as? Number)?.toDouble()
        val rawY = (action.args["y"] as? Number)?.toDouble()

        val clickPx = if (rawX != null && rawY != null) {
            Pair(
                (rawX / 1000.0 * currentDisplaySize.first).toInt(),
                (rawY / 1000.0 * currentDisplaySize.second).toInt(),
            )
        } else {
            null
        }

        val hasTargetClues = !nodeId.isNullOrBlank() || !resId.isNullOrBlank() || !targetDesc.isNullOrBlank()

        if (hasTargetClues) {
            when (val lookup = lookupBaselineTarget(
                nodes = baselineNodes,
                nodeId = nodeId,
                resId = resId,
                desc = targetDesc,
                clickPx = clickPx,
            )) {
                is BaselineTargetLookup.ExplicitIdentityMismatch -> {
                    return GuardResult.Rejected(
                        AndroidGuiActionResult(
                            success = false,
                            message = "action_target_missing",
                            diagnostics = mapOf("reason" to lookup.reason, "target" to (targetDesc ?: resId ?: nodeId ?: "")),
                        )
                    )
                }
                is BaselineTargetLookup.Ambiguous -> {
                    return GuardResult.Rejected(
                        AndroidGuiActionResult(
                            success = false,
                            message = "action_target_ambiguous",
                            diagnostics = mapOf("match_count" to lookup.count.toString(), "target" to (targetDesc ?: resId ?: nodeId ?: "")),
                        )
                    )
                }
                is BaselineTargetLookup.Found -> {
                    val baselineTarget = lookup.node
                    val baselineHasText = !baselineTarget.text.isNullOrBlank()
                    val baselineHasDesc = !baselineTarget.contentDesc.isNullOrBlank()
                    val baselineHasRes = !baselineTarget.resourceId.isNullOrBlank()

                    // 1. 先定位实际匹配语义的节点（杜绝将包含子节点的祖先容器误认为候选）
                    val matchedSemanticNodes = freshNodes.filter { freshNode ->
                        isActualSemanticMatch(
                            freshNode = freshNode,
                            baselineTarget = baselineTarget,
                            baselineHasText = baselineHasText,
                            baselineHasDesc = baselineHasDesc,
                            baselineHasRes = baselineHasRes,
                            targetDesc = targetDesc,
                            resId = resId,
                        )
                    }

                    // 2. 提升至最近的真实可交互祖先并去重
                    val freshCandidates = matchedSemanticNodes.map {
                        findNearestInteractiveNode(it)
                    }.distinctBy {
                        it.windowId to (if (it.id.isNotEmpty()) it.id else System.identityHashCode(it).toString())
                    }

                    if (freshCandidates.isEmpty()) {
                        return GuardResult.Rejected(
                            AndroidGuiActionResult(
                                success = false,
                                message = "action_target_missing",
                                diagnostics = mapOf("target" to (targetDesc ?: resId ?: nodeId ?: "")),
                            )
                        )
                    }
                    val bestTarget = if (freshCandidates.size == 1) {
                        freshCandidates[0]
                    } else {
                        val containingCandidates = freshCandidates.filter {
                            clickPx != null && it.bounds.contains(clickPx.first, clickPx.second)
                        }
                        if (containingCandidates.size == 1) {
                            containingCandidates[0]
                        } else {
                            return GuardResult.Rejected(
                                AndroidGuiActionResult(
                                    success = false,
                                    message = "action_target_ambiguous",
                                    diagnostics = mapOf("match_count" to freshCandidates.size.toString()),
                                )
                            )
                        }
                    }

                    val viewport = Rect(0, 0, currentDisplaySize.first, currentDisplaySize.second)
                    if (!bestTarget.visibleToUser) {
                        return GuardResult.Rejected(
                            AndroidGuiActionResult(
                                success = false,
                                message = "action_target_invisible",
                                diagnostics = mapOf("target_id" to bestTarget.id),
                            )
                        )
                    }
                    if (!bestTarget.enabled) {
                        return GuardResult.Rejected(
                            AndroidGuiActionResult(
                                success = false,
                                message = "action_target_disabled",
                                diagnostics = mapOf("target_id" to bestTarget.id),
                            )
                        )
                    }
                    if (!Rect.intersects(bestTarget.bounds, viewport)) {
                        return GuardResult.Rejected(
                            AndroidGuiActionResult(
                                success = false,
                                message = "action_target_out_of_viewport",
                                diagnostics = mapOf("target_id" to bestTarget.id),
                            )
                        )
                    }

                    // 校验坐标位移：坐标一旦脱离已确认目标的 bounds，坚决拒绝
                    if (clickPx != null && !bestTarget.bounds.contains(clickPx.first, clickPx.second)) {
                        return GuardResult.Rejected(
                            AndroidGuiActionResult(
                                success = false,
                                message = "action_target_moved",
                                diagnostics = mapOf(
                                    "target_id" to bestTarget.id,
                                    "click_coords" to "[${clickPx.first},${clickPx.second}]",
                                    "new_bounds" to bestTarget.bounds.toShortString(),
                                    "reason" to "click_coordinates_outside_target_bounds",
                                ),
                            )
                        )
                    }

                    // 校验遮挡与吸收确证净空点
                    val occlusion = checkOcclusion(bestTarget, freshNodes, currentDisplaySize, clickPx)
                    if (occlusion.type == OcclusionType.FULL) {
                        return GuardResult.Rejected(
                            AndroidGuiActionResult(
                                success = false,
                                message = "action_target_occluded",
                                diagnostics = mapOf(
                                    "target_id" to bestTarget.id,
                                    "occluding_id" to (occlusion.occludingNode?.id ?: "unknown"),
                                    "reason" to (occlusion.reason ?: "full_occlusion"),
                                ),
                            )
                        )
                    }

                    val isLearnableAction = action.tool == OobActionSchema.TOOL_CLICK ||
                        action.tool == OobActionSchema.TOOL_LONG_PRESS ||
                        action.tool == OobActionSchema.TOOL_INPUT_TEXT

                    val hasVerifiedFreshObservation = !expectedObservationId.isNullOrBlank() &&
                        baseline != null &&
                        baseline.observationId == expectedObservationId
                    val confirmedObservationId = if (hasVerifiedFreshObservation) expectedObservationId else null
                    val targetResId = bestTarget.resourceId?.takeIf { it.isNotBlank() }
                    val targetClass = bestTarget.className?.takeIf { it.isNotBlank() }

                    val confirmedDiagnostics = if (
                        isLearnableAction &&
                        confirmedObservationId != null &&
                        targetResId != null &&
                        targetClass != null
                    ) {
                        mapOf(
                            "guard_status" to "target_confirmed",
                            "observation_id" to confirmedObservationId,
                            "target_resource_id" to targetResId,
                            "target_class" to targetClass,
                        )
                    } else {
                        emptyMap()
                    }

                    // 若原点击点被遮挡，但目标存在确证净空点，重定位 Action 坐标至净空点派发
                    if (occlusion.type == OcclusionType.PARTIAL && occlusion.clearPoint != null && occlusion.clearPoint != clickPx) {
                        val clearPx = occlusion.clearPoint
                        val adjustedX = clearPx.first.toDouble() / currentDisplaySize.first * 1000.0
                        val adjustedY = clearPx.second.toDouble() / currentDisplaySize.second * 1000.0
                        val adjustedAction = action.copy(
                            args = action.args + mapOf("x" to adjustedX, "y" to adjustedY),
                        )
                        val adjustedDiagnostics = mapOf(
                            "clear_point_adjusted" to "[${clearPx.first},${clearPx.second}]",
                            "original_coords" to (clickPx?.let { "[${it.first},${it.second}]" } ?: "none"),
                        )
                        return GuardResult.Allowed(
                            action = adjustedAction,
                            adjustedCoordinate = clearPx,
                            diagnostics = adjustedDiagnostics + confirmedDiagnostics,
                        )
                    }

                    return GuardResult.Allowed(
                        action = action,
                        diagnostics = confirmedDiagnostics,
                    )
                }
                is BaselineTargetLookup.NoCandidate -> {
                    // 若调用方传入了明确控件 nodeId，但在 baseline 找不到，必须严格拒绝，绝不当成视觉/OCR目标
                    if (!nodeId.isNullOrBlank()) {
                        return GuardResult.Rejected(
                            AndroidGuiActionResult(
                                success = false,
                                message = "action_target_missing",
                                diagnostics = mapOf("reason" to "explicit_node_id_not_found_in_baseline", "node_id" to nodeId),
                            )
                        )
                    }

                    // 纯 OCR / 图像视觉目标（无明确 a11y 控件）
                    if (clickPx != null) {
                        val baselineMaxLayer = baselineNodes
                            .filter { it.visibleToUser && it.bounds.contains(clickPx.first, clickPx.second) }
                            .mapNotNull { it.windowLayer }
                            .maxOrNull() ?: 0

                        val coveringHighLayerNode = freshNodes.firstOrNull { node ->
                            node.visibleToUser &&
                                node.bounds.contains(clickPx.first, clickPx.second) &&
                                node.windowLayer != null &&
                                node.windowLayer > baselineMaxLayer &&
                                (node.className?.contains("Dialog", ignoreCase = true) == true ||
                                    node.className?.contains("PopupWindow", ignoreCase = true) == true ||
                                    node.isInteractive)
                        }
                        if (coveringHighLayerNode != null) {
                            return GuardResult.Rejected(
                                AndroidGuiActionResult(
                                    success = false,
                                    message = "action_target_occluded",
                                    diagnostics = mapOf(
                                        "reason" to "coordinate_occluded_by_higher_window",
                                        "window_id" to coveringHighLayerNode.windowId.toString(),
                                    ),
                                )
                            )
                        }

                        if (baseline?.screenshotJpeg != null) {
                            if (freshScreenshotJpeg == null || freshScreenshotJpeg.isEmpty()) {
                                return GuardResult.Rejected(
                                    AndroidGuiActionResult(
                                        success = false,
                                        message = "action_observation_expired",
                                        diagnostics = mapOf("reason" to "missing_fresh_screenshot_for_roi_check"),
                                    )
                                )
                            }

                            val roiChanged = checkLocalRoiPixelShift(
                                baselineBytes = baseline.screenshotJpeg,
                                freshBytes = freshScreenshotJpeg,
                                centerX = clickPx.first,
                                centerY = clickPx.second,
                                displayWidth = currentDisplaySize.first,
                                displayHeight = currentDisplaySize.second,
                            )
                            if (roiChanged) {
                                return GuardResult.Rejected(
                                    AndroidGuiActionResult(
                                        success = false,
                                        message = "action_observation_expired",
                                        diagnostics = mapOf("reason" to "target_roi_pixel_changed"),
                                    )
                                )
                            }
                        }
                    }
                    return GuardResult.Allowed(action = action)
                }
            }
        } else if (clickPx != null) {
            // 纯基于坐标点击
            val baselineMaxLayer = baselineNodes
                .filter { it.visibleToUser && it.bounds.contains(clickPx.first, clickPx.second) }
                .mapNotNull { it.windowLayer }
                .maxOrNull() ?: 0

            val coveringHighLayerNode = freshNodes.firstOrNull { node ->
                node.visibleToUser &&
                    node.bounds.contains(clickPx.first, clickPx.second) &&
                    node.windowLayer != null &&
                    node.windowLayer > baselineMaxLayer &&
                    node.isInteractive
            }
            if (coveringHighLayerNode != null) {
                return GuardResult.Rejected(
                    AndroidGuiActionResult(
                        success = false,
                        message = "action_target_occluded",
                        diagnostics = mapOf("reason" to "coordinate_occluded_by_layer"),
                    )
                )
            }

            if (baseline?.screenshotJpeg != null) {
                if (freshScreenshotJpeg == null || freshScreenshotJpeg.isEmpty()) {
                    return GuardResult.Rejected(
                        AndroidGuiActionResult(
                            success = false,
                            message = "action_observation_expired",
                            diagnostics = mapOf("reason" to "missing_fresh_screenshot_for_roi_check"),
                        )
                    )
                }

                val roiChanged = checkLocalRoiPixelShift(
                    baselineBytes = baseline.screenshotJpeg,
                    freshBytes = freshScreenshotJpeg,
                    centerX = clickPx.first,
                    centerY = clickPx.second,
                    displayWidth = currentDisplaySize.first,
                    displayHeight = currentDisplaySize.second,
                )
                if (roiChanged) {
                    return GuardResult.Rejected(
                        AndroidGuiActionResult(
                            success = false,
                            message = "action_observation_expired",
                            diagnostics = mapOf("reason" to "target_roi_pixel_changed"),
                        )
                    )
                }
            }
            return GuardResult.Allowed(action = action)
        }

        return GuardResult.Allowed(action = action)
    }

    /**
     * 保持对仅关心 failure 返回的验证方法的干净兼容。
     */
    fun verifyActionGuard(
        action: Action,
        expectedState: State?,
        expectedObservationId: String?,
        baseline: AndroidGuiObservationBaseline?,
        currentDisplayId: Int,
        currentDisplaySize: Pair<Int, Int>,
        freshXml: String,
        freshPackageName: String = "",
        freshScreenshotJpeg: ByteArray? = null,
    ): AndroidGuiActionResult? {
        return when (val result = prepareGuardedAction(
            action = action,
            expectedState = expectedState,
            expectedObservationId = expectedObservationId,
            baseline = baseline,
            currentDisplayId = currentDisplayId,
            currentDisplaySize = currentDisplaySize,
            freshXml = freshXml,
            freshPackageName = freshPackageName,
            freshScreenshotJpeg = freshScreenshotJpeg,
        )) {
            is GuardResult.Allowed -> null
            is GuardResult.Rejected -> result.failure
        }
    }

    private fun lookupBaselineTarget(
        nodes: List<AndroidGuiNode>,
        nodeId: String?,
        resId: String?,
        desc: String?,
        clickPx: Pair<Int, Int>?,
    ): BaselineTargetLookup {
        if (nodes.isEmpty()) return BaselineTargetLookup.NoCandidate

        // 1. 若提供了明确的 nodeId（仅在 baseline 查找线索时有效），优先联合验证该节点是否与其它线索一致
        if (!nodeId.isNullOrBlank()) {
            val nodeWithId = nodes.firstOrNull { it.id == nodeId }
                ?: return BaselineTargetLookup.ExplicitIdentityMismatch("node_id_not_found:$nodeId")
            val resMatch = resId.isNullOrBlank() || nodeWithId.resourceId == resId
            val descMatch = desc.isNullOrBlank() || matchesDirectText(nodeWithId, desc)
            if (!resMatch || !descMatch) {
                return BaselineTargetLookup.ExplicitIdentityMismatch("node_id_clue_conflict")
            }
            return BaselineTargetLookup.Found(nodeWithId)
        }

        // 2. 未指定明确 nodeId 时：根据 desc 和/或 resId 收集所有匹配候选
        val candidates = nodes.filter { node ->
            var matches = true
            if (!resId.isNullOrBlank() && node.resourceId != resId) {
                matches = false
            }
            if (!desc.isNullOrBlank() && !matchesDirectText(node, desc)) {
                matches = false
            }
            matches
        }

        if (candidates.isEmpty()) return BaselineTargetLookup.NoCandidate
        if (candidates.size == 1) return BaselineTargetLookup.Found(candidates[0])

        // 3. 将多候选按实际可交互目标归并：
        //    若候选自身或祖先可交互，归并至最近可交互节点（同一按钮内的父子/兄弟标签候选）；
        //    独立的 clickable/focusable 子节点或独立非交互节点保留各自目标。
        val targetGroups = LinkedHashMap<String, TargetGroup>()
        for (candidate in candidates) {
            val interactive = findNearestInteractiveNode(candidate)
            val isInteractiveTarget = interactive.isInteractive
            val key = if (isInteractiveTarget) {
                "interactive:${interactive.windowId}:${if (interactive.id.isNotEmpty()) interactive.id else System.identityHashCode(interactive)}"
            } else {
                "node:${candidate.windowId}:${if (candidate.id.isNotEmpty()) candidate.id else System.identityHashCode(candidate)}"
            }
            val group = targetGroups.getOrPut(key) {
                TargetGroup(interactiveTarget = if (isInteractiveTarget) interactive else candidate)
            }
            group.candidates.add(candidate)
        }

        if (targetGroups.size == 1) {
            val singleGroup = targetGroups.values.first()
            return BaselineTargetLookup.Found(selectRepresentative(singleGroup.candidates, clickPx))
        }

        // 4. 存在多个合法不同目标时，尝试使用点击坐标进行唯一消歧；
        //    若坐标未能唯一确定单个目标（多个目标包含坐标，或均未包含坐标），坚决拒绝猜选！
        val containingGroups = if (clickPx != null) {
            targetGroups.values.filter { group ->
                group.candidates.any { it.bounds.contains(clickPx.first, clickPx.second) } ||
                    (group.interactiveTarget.isInteractive && group.interactiveTarget.bounds.contains(clickPx.first, clickPx.second))
            }
        } else {
            emptyList()
        }

        if (containingGroups.size == 1) {
            val singleGroup = containingGroups[0]
            return BaselineTargetLookup.Found(selectRepresentative(singleGroup.candidates, clickPx))
        }

        // 无法唯一确定，明确返回 Ambiguous（绝不当成无候选 fallback 漏放，不盲目猜选）
        return BaselineTargetLookup.Ambiguous(targetGroups.size)
    }

    private class TargetGroup(
        val interactiveTarget: AndroidGuiNode,
        val candidates: MutableList<AndroidGuiNode> = mutableListOf(),
    )

    private fun selectRepresentative(
        groupCandidates: List<AndroidGuiNode>,
        clickPx: Pair<Int, Int>? = null,
    ): AndroidGuiNode {
        if (groupCandidates.size == 1) return groupCandidates[0]

        // 排除作为组内其他候选祖先的容器节点（归并父子标签候选，保留最具体的叶子标签节点）
        val leaves = groupCandidates.filter { candidate ->
            groupCandidates.none { other ->
                other !== candidate && isAncestor(ancestor = candidate, descendant = other)
            }
        }

        val pool = if (leaves.isNotEmpty()) leaves else groupCandidates

        // 若提供坐标，优先选择 bounds 包含点击坐标的候选
        if (clickPx != null) {
            val containing = pool.filter { it.bounds.contains(clickPx.first, clickPx.second) }
            if (containing.size == 1) return containing[0]
            if (containing.isNotEmpty()) {
                return containing.firstOrNull { !it.text.isNullOrBlank() }
                    ?: containing.firstOrNull { !it.contentDesc.isNullOrBlank() }
                    ?: containing[0]
            }
        }

        // 优先保留具有明确文本的节点，其次 contentDesc
        return pool.firstOrNull { !it.text.isNullOrBlank() }
            ?: pool.firstOrNull { !it.contentDesc.isNullOrBlank() }
            ?: pool[0]
    }

    private fun isAncestor(ancestor: AndroidGuiNode, descendant: AndroidGuiNode): Boolean {
        var curr = descendant.parent
        while (curr != null) {
            if (curr === ancestor) return true
            if (ancestor.id.isNotEmpty() && curr.id == ancestor.id) return true
            curr = curr.parent
        }
        return false
    }
    private fun matchesDirectText(node: AndroidGuiNode, expectedText: String): Boolean {
        val d = expectedText.trim()
        return node.text?.contains(d, ignoreCase = true) == true ||
            node.contentDesc?.contains(d, ignoreCase = true) == true ||
            node.hintText?.contains(d, ignoreCase = true) == true
    }

    private fun isActualSemanticMatch(
        freshNode: AndroidGuiNode,
        baselineTarget: AndroidGuiNode,
        baselineHasText: Boolean,
        baselineHasDesc: Boolean,
        baselineHasRes: Boolean,
        targetDesc: String?,
        resId: String?,
    ): Boolean {
        // 1. 若 baseline 目标拥有 resourceId，fresh 节点必须具备完全相等的 resourceId（防止无属性 root 混入）
        if (baselineHasRes) {
            if (freshNode.resourceId != baselineTarget.resourceId) return false
        } else if (!resId.isNullOrBlank()) {
            if (freshNode.resourceId != resId) return false
        }

        // 2. 若 baseline 目标拥有文本，fresh 节点必须 trim 后精确相等，禁止使用 contains 放行文本变更（如“删除”->“删除全部”）
        if (baselineHasText) {
            val expectedText = baselineTarget.text!!.trim()
            val freshText = freshNode.text?.trim()
            if (freshText == null || !freshText.equals(expectedText, ignoreCase = true)) {
                return false
            }
        }

        // 3. 若 baseline 目标拥有 content-desc，fresh 节点必须 trim 后精确相等
        if (baselineHasDesc) {
            val expectedDesc = baselineTarget.contentDesc!!.trim()
            val freshDesc = freshNode.contentDesc?.trim()
            if (freshDesc == null || !freshDesc.equals(expectedDesc, ignoreCase = true)) {
                return false
            }
        }

        // 4. 若调用方传入了 targetDesc，但 baseline 既无 text 也无 contentDesc（如仅有 hint-text）
        if (!targetDesc.isNullOrBlank() && !baselineHasText && !baselineHasDesc) {
            val d = targetDesc.trim()
            val matched = freshNode.text?.trim()?.equals(d, ignoreCase = true) == true ||
                freshNode.contentDesc?.trim()?.equals(d, ignoreCase = true) == true ||
                freshNode.hintText?.trim()?.equals(d, ignoreCase = true) == true
            if (!matched) return false
        }

        return true
    }

    /**
     * 针对点击位置附近 32x32 局部 ROI 进行像素差异核验。
     * 若局部像素发生剧烈变化（如弹窗覆盖或按键消失），返回 true 表示已过期；
     * 画面其他区域（如状态栏时间变化）不会影响本判定。
     */
    private fun checkLocalRoiPixelShift(
        baselineBytes: ByteArray,
        freshBytes: ByteArray,
        centerX: Int,
        centerY: Int,
        displayWidth: Int,
        displayHeight: Int,
        roiRadius: Int = 16,
    ): Boolean = runCatching {
        val left = (centerX - roiRadius).coerceIn(0, displayWidth - 1)
        val top = (centerY - roiRadius).coerceIn(0, displayHeight - 1)
        val right = (centerX + roiRadius).coerceIn(left + 1, displayWidth)
        val bottom = (centerY + roiRadius).coerceIn(top + 1, displayHeight)
        val width = right - left
        val height = bottom - top
        if (width <= 0 || height <= 0) return@runCatching false
        val baselineBitmap = BitmapFactory.decodeByteArray(baselineBytes, 0, baselineBytes.size) ?: return@runCatching false
        val freshBitmap = BitmapFactory.decodeByteArray(freshBytes, 0, freshBytes.size) ?: return@runCatching false

        try {
            checkRoiPixelShiftOnBitmaps(
                baselineBitmap = baselineBitmap,
                freshBitmap = freshBitmap,
                centerX = centerX,
                centerY = centerY,
                displayWidth = displayWidth,
                displayHeight = displayHeight,
                roiRadius = roiRadius,
            )
        } finally {
            baselineBitmap.recycle()
            freshBitmap.recycle()
        }
    }.getOrDefault(false)

    private fun checkRoiPixelShiftOnBitmaps(
        baselineBitmap: Bitmap,
        freshBitmap: Bitmap,
        centerX: Int,
        centerY: Int,
        displayWidth: Int,
        displayHeight: Int,
        roiRadius: Int = 16,
    ): Boolean {
        val left = (centerX - roiRadius).coerceIn(0, displayWidth - 1)
        val top = (centerY - roiRadius).coerceIn(0, displayHeight - 1)
        val right = (centerX + roiRadius).coerceIn(left + 1, displayWidth)
        val bottom = (centerY + roiRadius).coerceIn(top + 1, displayHeight)
        val width = right - left
        val height = bottom - top
        if (width <= 0 || height <= 0) return false

        val basePixels = IntArray(width * height)
        val freshPixels = IntArray(width * height)

        baselineBitmap.getPixels(basePixels, 0, width, left, top, width, height)
        freshBitmap.getPixels(freshPixels, 0, width, left, top, width, height)

        var diffCount = 0
        val total = width * height
        for (i in 0 until total) {
            val p1 = basePixels[i]
            val p2 = freshPixels[i]
            val rDiff = abs(((p1 shr 16) and 0xFF) - ((p2 shr 16) and 0xFF))
            val gDiff = abs(((p1 shr 8) and 0xFF) - ((p2 shr 8) and 0xFF))
            val bDiff = abs((p1 and 0xFF) - (p2 and 0xFF))
            if (rDiff + gDiff + bDiff > 45) {
                diffCount++
            }
        }
        val diffRatio = diffCount.toDouble() / total.toDouble()
        return diffRatio > 0.4
    }

    private fun parseBounds(value: String): Rect {
        val match = BOUNDS_REGEX.matchEntire(value.trim()) ?: return Rect()
        val (left, top, right, bottom) = match.destructured
        return Rect(
            left.toIntOrNull() ?: 0,
            top.toIntOrNull() ?: 0,
            right.toIntOrNull() ?: 0,
            bottom.toIntOrNull() ?: 0,
        )
    }

    private data class SwipeCoordinates(
        val startX: Double,
        val startY: Double,
        val endX: Double,
        val endY: Double,
    )

    private fun decodeSwipeCoordinates(action: Action): SwipeCoordinates? {
        val rawX1 = (action.args["x1"] as? Number)?.toDouble()
        val rawY1 = (action.args["y1"] as? Number)?.toDouble()
        val rawX2 = (action.args["x2"] as? Number)?.toDouble()
        val rawY2 = (action.args["y2"] as? Number)?.toDouble()

        val startX = rawX1 ?: (action.args["x"] as? Number)?.toDouble()
        val startY = rawY1 ?: (action.args["y"] as? Number)?.toDouble()
        val endX = rawX2
        val endY = rawY2

        if (startX != null && startY != null) {
            if (endX != null && endY != null) {
                return SwipeCoordinates(startX, startY, endX, endY)
            }
            val distance = (action.args["distance"] as? Number)?.toDouble() ?: 300.0
            val dir = action.args["direction"]?.toString()?.trim()?.lowercase()
            return when (dir) {
                "up" -> SwipeCoordinates(startX, startY, startX, (startY - distance).coerceIn(0.0, 1000.0))
                "down" -> SwipeCoordinates(startX, startY, startX, (startY + distance).coerceIn(0.0, 1000.0))
                "left" -> SwipeCoordinates(startX, startY, (startX - distance).coerceIn(0.0, 1000.0), startY)
                "right" -> SwipeCoordinates(startX, startY, (startX + distance).coerceIn(0.0, 1000.0), startY)
                else -> null
            }
        }
        return null
    }

    private fun checkSwipePath(
        action: Action,
        baseline: AndroidGuiObservationBaseline?,
        currentDisplaySize: Pair<Int, Int>,
        baselineNodes: List<AndroidGuiNode>,
        baselineRoots: List<AndroidGuiNode>,
        freshNodes: List<AndroidGuiNode>,
        freshRoots: List<AndroidGuiNode>,
        freshScreenshotJpeg: ByteArray?,
    ): GuardResult {
        if (currentDisplaySize.first <= 0 || currentDisplaySize.second <= 0) {
            return GuardResult.Rejected(
                AndroidGuiActionResult(
                    success = false,
                    message = "action_observation_expired",
                    diagnostics = mapOf("reason" to "invalid_display_size"),
                )
            )
        }

        val coords = decodeSwipeCoordinates(action)
            ?: return GuardResult.Rejected(
                AndroidGuiActionResult(
                    success = false,
                    message = "action_coordinate_invalid",
                    diagnostics = mapOf("reason" to "missing_or_invalid_swipe_coordinates"),
                )
            )

        if (!coords.startX.isFinite() || !coords.startY.isFinite() ||
            !coords.endX.isFinite() || !coords.endY.isFinite()
        ) {
            return GuardResult.Rejected(
                AndroidGuiActionResult(
                    success = false,
                    message = "action_coordinate_invalid",
                    diagnostics = mapOf("reason" to "non_finite_swipe_coordinates"),
                )
            )
        }

        if (coords.startX !in 0.0..1000.0 || coords.startY !in 0.0..1000.0 ||
            coords.endX !in 0.0..1000.0 || coords.endY !in 0.0..1000.0
        ) {
            return GuardResult.Rejected(
                AndroidGuiActionResult(
                    success = false,
                    message = "canonical_action_arg_range_invalid",
                    diagnostics = mapOf("reason" to "swipe_coordinates_out_of_range"),
                )
            )
        }

        val startPx = Pair(
            (coords.startX / 1000.0 * currentDisplaySize.first).toInt(),
            (coords.startY / 1000.0 * currentDisplaySize.second).toInt(),
        )
        val endPx = Pair(
            (coords.endX / 1000.0 * currentDisplaySize.first).toInt(),
            (coords.endY / 1000.0 * currentDisplaySize.second).toInt(),
        )
        val midPx = Pair(
            (startPx.first + endPx.first) / 2,
            (startPx.second + endPx.second) / 2,
        )

        val isPureVisual = baselineNodes.isEmpty()
        if (isPureVisual) {
            if (baseline?.screenshotJpeg == null || baseline.screenshotJpeg.isEmpty()) {
                return GuardResult.Rejected(
                    AndroidGuiActionResult(
                        success = false,
                        message = "action_observation_expired",
                        diagnostics = mapOf("reason" to "missing_baseline_screenshot_for_pure_visual_swipe"),
                    )
                )
            }
            if (freshScreenshotJpeg == null || freshScreenshotJpeg.isEmpty()) {
                return GuardResult.Rejected(
                    AndroidGuiActionResult(
                        success = false,
                        message = "action_observation_expired",
                        diagnostics = mapOf("reason" to "missing_fresh_screenshot_for_roi_check"),
                    )
                )
            }

            val baselineBitmap = BitmapFactory.decodeByteArray(baseline.screenshotJpeg, 0, baseline.screenshotJpeg.size)
                ?: return GuardResult.Rejected(
                    AndroidGuiActionResult(
                        success = false,
                        message = "action_observation_expired",
                        diagnostics = mapOf("reason" to "corrupted_baseline_screenshot"),
                    )
                )

            val freshBitmap = BitmapFactory.decodeByteArray(freshScreenshotJpeg, 0, freshScreenshotJpeg.size)
            if (freshBitmap == null) {
                baselineBitmap.recycle()
                return GuardResult.Rejected(
                    AndroidGuiActionResult(
                        success = false,
                        message = "action_observation_expired",
                        diagnostics = mapOf("reason" to "corrupted_fresh_screenshot"),
                    )
                )
            }

            if (baselineBitmap.width != currentDisplaySize.first || baselineBitmap.height != currentDisplaySize.second ||
                freshBitmap.width != currentDisplaySize.first || freshBitmap.height != currentDisplaySize.second
            ) {
                baselineBitmap.recycle()
                freshBitmap.recycle()
                return GuardResult.Rejected(
                    AndroidGuiActionResult(
                        success = false,
                        message = "action_display_mismatch",
                        diagnostics = mapOf(
                            "baseline_bitmap_size" to "${baselineBitmap.width}x${baselineBitmap.height}",
                            "fresh_bitmap_size" to "${freshBitmap.width}x${freshBitmap.height}",
                            "current_display_size" to "${currentDisplaySize.first}x${currentDisplaySize.second}",
                        ),
                    )
                )
            }

            try {
                // fresh 窗口可确证的高层实体覆盖路径即拒绝
                val freshCoveringEntities = freshNodes.filter { node ->
                    node.visibleToUser && !node.bounds.isEmpty &&
                        (node.isInteractive || !node.text.isNullOrBlank() ||
                            node.className?.contains("Dialog", ignoreCase = true) == true ||
                            node.className?.contains("PopupWindow", ignoreCase = true) == true ||
                            (node.windowLayer != null && node.windowLayer > 0))
                }
                for (entity in freshCoveringEntities) {
                    if (lineIntersectsRect(startPx, endPx, entity.bounds)) {
                        return GuardResult.Rejected(
                            AndroidGuiActionResult(
                                success = false,
                                message = "action_swipe_obstructed",
                                diagnostics = mapOf(
                                    "reason" to "swipe_path_obstructed_by_fresh_entity",
                                    "blocker_id" to entity.id,
                                    "blocker_bounds" to entity.bounds.toString(),
                                ),
                            )
                        )
                    }
                }

                // 复用 checkRoiPixelShiftOnBitmaps 分别核验起点、中点、终点；任一点变动拒绝
                val startChanged = checkRoiPixelShiftOnBitmaps(
                    baselineBitmap = baselineBitmap,
                    freshBitmap = freshBitmap,
                    centerX = startPx.first,
                    centerY = startPx.second,
                    displayWidth = currentDisplaySize.first,
                    displayHeight = currentDisplaySize.second,
                )
                if (startChanged) {
                    return GuardResult.Rejected(
                        AndroidGuiActionResult(
                            success = false,
                            message = "action_observation_expired",
                            diagnostics = mapOf("reason" to "start_point_roi_pixel_changed"),
                        )
                    )
                }

                val midChanged = checkRoiPixelShiftOnBitmaps(
                    baselineBitmap = baselineBitmap,
                    freshBitmap = freshBitmap,
                    centerX = midPx.first,
                    centerY = midPx.second,
                    displayWidth = currentDisplaySize.first,
                    displayHeight = currentDisplaySize.second,
                )
                if (midChanged) {
                    return GuardResult.Rejected(
                        AndroidGuiActionResult(
                            success = false,
                            message = "action_observation_expired",
                            diagnostics = mapOf("reason" to "mid_point_roi_pixel_changed"),
                        )
                    )
                }

                val endChanged = checkRoiPixelShiftOnBitmaps(
                    baselineBitmap = baselineBitmap,
                    freshBitmap = freshBitmap,
                    centerX = endPx.first,
                    centerY = endPx.second,
                    displayWidth = currentDisplaySize.first,
                    displayHeight = currentDisplaySize.second,
                )
                if (endChanged) {
                    return GuardResult.Rejected(
                        AndroidGuiActionResult(
                            success = false,
                            message = "action_observation_expired",
                            diagnostics = mapOf("reason" to "end_point_roi_pixel_changed"),
                        )
                    )
                }
            } finally {
                baselineBitmap.recycle()
                freshBitmap.recycle()
            }

            return GuardResult.Allowed(action = action)
        }

        // 有 XML 结构分支
        val targetDesc = action.args["target_description"] as? String
        val nodeId = action.args["node_id"]?.toString()
        val resId = action.args["node_resource_id"] as? String
        val hasTargetClues = !targetDesc.isNullOrBlank() || !nodeId.isNullOrBlank() || !resId.isNullOrBlank()

        if (hasTargetClues) {
            val lookup = lookupBaselineTarget(
                nodes = baselineNodes,
                nodeId = nodeId,
                resId = resId,
                desc = targetDesc,
                clickPx = startPx,
            )
            when (lookup) {
                is BaselineTargetLookup.ExplicitIdentityMismatch -> {
                    return GuardResult.Rejected(
                        AndroidGuiActionResult(
                            success = false,
                            message = "action_target_missing",
                            diagnostics = mapOf("reason" to lookup.reason, "target" to (targetDesc ?: resId ?: nodeId ?: "")),
                        )
                    )
                }
                is BaselineTargetLookup.Ambiguous -> {
                    return GuardResult.Rejected(
                        AndroidGuiActionResult(
                            success = false,
                            message = "action_target_ambiguous",
                            diagnostics = mapOf("match_count" to lookup.count.toString(), "target" to (targetDesc ?: resId ?: nodeId ?: "")),
                        )
                    )
                }
                is BaselineTargetLookup.NoCandidate -> {
                    return GuardResult.Rejected(
                        AndroidGuiActionResult(
                            success = false,
                            message = "action_target_missing",
                            diagnostics = mapOf("reason" to "target_not_found_in_baseline", "target" to (targetDesc ?: resId ?: nodeId ?: "")),
                        )
                    )
                }
                is BaselineTargetLookup.Found -> {
                    val baselineTargetNode = lookup.node
                    val freshTargetCandidates = freshNodes.filter { freshNode ->
                        isActualSemanticMatch(
                            freshNode = freshNode,
                            baselineTarget = baselineTargetNode,
                            baselineHasText = !baselineTargetNode.text.isNullOrBlank(),
                            baselineHasDesc = !baselineTargetNode.contentDesc.isNullOrBlank(),
                            baselineHasRes = !baselineTargetNode.resourceId.isNullOrBlank(),
                            targetDesc = targetDesc,
                            resId = resId,
                        )
                    }
                    if (freshTargetCandidates.isEmpty()) {
                        return GuardResult.Rejected(
                            AndroidGuiActionResult(
                                success = false,
                                message = "action_target_missing",
                                diagnostics = mapOf("target" to (targetDesc ?: resId ?: nodeId ?: "")),
                            )
                        )
                    }
                    if (freshTargetCandidates.size > 1) {
                        val containing = freshTargetCandidates.filter { it.bounds.contains(startPx.first, startPx.second) }
                        if (containing.size != 1) {
                            return GuardResult.Rejected(
                                AndroidGuiActionResult(
                                    success = false,
                                    message = "action_target_ambiguous",
                                    diagnostics = mapOf("match_count" to freshTargetCandidates.size.toString(), "target" to (targetDesc ?: resId ?: nodeId ?: "")),
                                )
                            )
                        }
                    }
                }
            }
        }

        // 选择 baseline 包含起点的最小 scrollable 祖先（无则所在窗口根）
        val containingNodes = baselineNodes.filter { it.visibleToUser && it.bounds.contains(startPx.first, startPx.second) }
        val scrollableCandidates = containingNodes.filter { it.scrollable }
        val baselineContainer: AndroidGuiNode = if (scrollableCandidates.isNotEmpty()) {
            scrollableCandidates.minByOrNull { it.bounds.width() * it.bounds.height() }!!
        } else if (containingNodes.isNotEmpty()) {
            val containingRoots = containingNodes.map { node ->
                var root = node
                while (root.parent != null) {
                    root = root.parent!!
                }
                root
            }.distinctBy { it.windowId to (if (it.id.isNotEmpty()) it.id else System.identityHashCode(it).toString()) }

            if (containingRoots.size == 1) {
                containingRoots[0]
            } else {
                return GuardResult.Rejected(
                    AndroidGuiActionResult(
                        success = false,
                        message = "action_target_ambiguous",
                        diagnostics = mapOf("reason" to "multiple_baseline_roots_contain_start_point", "count" to containingRoots.size.toString()),
                    )
                )
            }
        } else {
            return GuardResult.Rejected(
                AndroidGuiActionResult(
                    success = false,
                    message = "action_target_missing",
                    diagnostics = mapOf("reason" to "start_point_outside_all_baseline_nodes"),
                )
            )
        }

        val baselineHasText = !baselineContainer.text.isNullOrBlank()
        val baselineHasDesc = !baselineContainer.contentDesc.isNullOrBlank()
        val baselineHasRes = !baselineContainer.resourceId.isNullOrBlank()

        val matchedFreshContainers = if (baselineHasRes || baselineHasText || baselineHasDesc) {
            freshNodes.filter { freshNode ->
                isActualSemanticMatch(
                    freshNode = freshNode,
                    baselineTarget = baselineContainer,
                    baselineHasText = baselineHasText,
                    baselineHasDesc = baselineHasDesc,
                    baselineHasRes = baselineHasRes,
                    targetDesc = targetDesc,
                    resId = resId ?: baselineContainer.resourceId,
                ) && (!baselineContainer.scrollable || freshNode.scrollable)
            }
        } else if (baselineContainer.scrollable) {
            freshNodes.filter { freshNode ->
                freshNode.scrollable &&
                    (baselineContainer.className == null || freshNode.className == baselineContainer.className)
            }
        } else {
            freshRoots.filter { freshRoot ->
                freshRoot.windowId == baselineContainer.windowId &&
                    (baselineContainer.className == null || freshRoot.className == baselineContainer.className)
            }
        }

        if (matchedFreshContainers.isEmpty()) {
            return GuardResult.Rejected(
                AndroidGuiActionResult(
                    success = false,
                    message = "action_target_missing",
                    diagnostics = mapOf("target" to (targetDesc ?: baselineContainer.resourceId ?: baselineContainer.id)),
                )
            )
        }

        val freshContainer = if (matchedFreshContainers.size == 1) {
            matchedFreshContainers[0]
        } else {
            val containing = matchedFreshContainers.filter { it.bounds.contains(startPx.first, startPx.second) }
            if (containing.size == 1) {
                containing[0]
            } else {
                return GuardResult.Rejected(
                    AndroidGuiActionResult(
                        success = false,
                        message = "action_target_ambiguous",
                        diagnostics = mapOf("match_count" to matchedFreshContainers.size.toString()),
                    )
                )
            }
        }

        if (!freshContainer.visibleToUser) {
            return GuardResult.Rejected(
                AndroidGuiActionResult(
                    success = false,
                    message = "action_target_invisible",
                    diagnostics = mapOf("target_id" to freshContainer.id),
                )
            )
        }
        if (!freshContainer.enabled) {
            return GuardResult.Rejected(
                AndroidGuiActionResult(
                    success = false,
                    message = "action_target_disabled",
                    diagnostics = mapOf("target_id" to freshContainer.id),
                )
            )
        }
        val viewport = Rect(0, 0, currentDisplaySize.first, currentDisplaySize.second)
        if (!Rect.intersects(freshContainer.bounds, viewport)) {
            return GuardResult.Rejected(
                AndroidGuiActionResult(
                    success = false,
                    message = "action_target_out_of_viewport",
                    diagnostics = mapOf("target_id" to freshContainer.id),
                )
            )
        }
        if (!freshContainer.bounds.contains(startPx.first, startPx.second)) {
            return GuardResult.Rejected(
                AndroidGuiActionResult(
                    success = false,
                    message = "action_target_moved",
                    diagnostics = mapOf(
                        "reason" to "start_point_outside_target_bounds",
                        "start_point" to "${startPx.first},${startPx.second}",
                        "target_bounds" to freshContainer.bounds.toString(),
                    ),
                )
            )
        }

        val blockers = collectSwipeBlockers(freshContainer, freshNodes, currentDisplaySize)
        for ((occludingNode, blockerRect) in blockers) {
            if (lineIntersectsRect(startPx, endPx, blockerRect)) {
                return GuardResult.Rejected(
                    AndroidGuiActionResult(
                        success = false,
                        message = "action_swipe_obstructed",
                        diagnostics = mapOf(
                            "reason" to "swipe_path_obstructed_by_blocker",
                            "blocker_id" to occludingNode.id,
                            "blocker_bounds" to occludingNode.bounds.toString(),
                            "swipe_start" to "${startPx.first},${startPx.second}",
                            "swipe_end" to "${endPx.first},${endPx.second}",
                        ),
                    )
                )
            }
        }

        return GuardResult.Allowed(action = action)
    }

    private fun collectSwipeBlockers(
        targetNode: AndroidGuiNode,
        allNodes: List<AndroidGuiNode>,
        displaySize: Pair<Int, Int>,
    ): List<Pair<AndroidGuiNode, Rect>> {
        val viewport = Rect(0, 0, displaySize.first, displaySize.second)
        val blockers = mutableListOf<Pair<AndroidGuiNode, Rect>>()
        val targetLayer = targetNode.windowLayer ?: 0

        val targetAncestorsAndSelf = mutableListOf<AndroidGuiNode>()
        var curr: AndroidGuiNode? = targetNode
        while (curr != null) {
            targetAncestorsAndSelf.add(curr)
            curr = curr.parent
        }

        for (candidate in allNodes) {
            val isSameTarget = candidate === targetNode ||
                (candidate.id.isNotEmpty() && candidate.id == targetNode.id && candidate.windowId == targetNode.windowId)
            if (isSameTarget) continue

            val isTargetAncestor = targetAncestorsAndSelf.any { ancestor ->
                ancestor === candidate ||
                    (ancestor.id.isNotEmpty() && ancestor.id == candidate.id && ancestor.windowId == candidate.windowId)
            }
            if (isTargetAncestor) continue

            if (isAncestor(ancestor = targetNode, descendant = candidate)) continue
            if (!candidate.visibleToUser || candidate.bounds.isEmpty) continue

            // 规则 1：同窗口同级（sibling）且绘制顺序明确更高；必须自身可交互才认作触摸遮挡
            val isSiblingTouchBlocker = targetAncestorsAndSelf.any { ancestor ->
                candidate.windowId == ancestor.windowId &&
                    candidate.parent != null &&
                    ancestor.parent != null &&
                    (candidate.parent === ancestor.parent || (candidate.parent?.id?.isNotEmpty() == true && candidate.parent?.id == ancestor.parent?.id)) &&
                    candidate.drawingOrder != null &&
                    ancestor.drawingOrder != null &&
                    candidate.drawingOrder > ancestor.drawingOrder &&
                    candidate.isInteractive
            }

            // 规则 2：更高窗口层，且自身非透明占位、具备交互能力或具备非全屏实体内容
            val isHigherWindowBlocker = candidate.windowLayer != null &&
                candidate.windowLayer > targetLayer &&
                (candidate.isInteractive || candidate.bounds.width() < displaySize.first || candidate.bounds.height() < displaySize.second || !candidate.text.isNullOrBlank())

            // 附加 Dialog/PopupWindow 判定（只要跨窗口且为弹窗类型或具备弹窗类名）
            val isDialogWindowBlocker = candidate.windowId != targetNode.windowId &&
                (candidate.className?.contains("Dialog", ignoreCase = true) == true ||
                    candidate.className?.contains("PopupWindow", ignoreCase = true) == true) &&
                (candidate.isInteractive || candidate.bounds.width() < displaySize.first || candidate.bounds.height() < displaySize.second || !candidate.text.isNullOrBlank())

            if (!isSiblingTouchBlocker && !isHigherWindowBlocker && !isDialogWindowBlocker) {
                continue
            }

            val intersect = Rect()
            if (intersect.setIntersect(candidate.bounds, viewport) && !intersect.isEmpty) {
                blockers.add(candidate to intersect)
            }
        }
        return blockers
    }

    private fun lineIntersectsRect(p1: Pair<Int, Int>, p2: Pair<Int, Int>, rect: Rect): Boolean {
        if (rect.isEmpty) return false
        if (p1.first in rect.left..rect.right && p1.second in rect.top..rect.bottom) return true
        if (p2.first in rect.left..rect.right && p2.second in rect.top..rect.bottom) return true

        val segMinX = minOf(p1.first, p2.first)
        val segMaxX = maxOf(p1.first, p2.first)
        val segMinY = minOf(p1.second, p2.second)
        val segMaxY = maxOf(p1.second, p2.second)

        if (segMaxX < rect.left || segMinX > rect.right || segMaxY < rect.top || segMinY > rect.bottom) {
            return false
        }

        val rL = rect.left.toDouble()
        val rR = rect.right.toDouble()
        val rT = rect.top.toDouble()
        val rB = rect.bottom.toDouble()

        val x1 = p1.first.toDouble()
        val y1 = p1.second.toDouble()
        val x2 = p2.first.toDouble()
        val y2 = p2.second.toDouble()

        if (segmentsIntersect(x1, y1, x2, y2, rL, rT, rR, rT)) return true
        if (segmentsIntersect(x1, y1, x2, y2, rR, rT, rR, rB)) return true
        if (segmentsIntersect(x1, y1, x2, y2, rR, rB, rL, rB)) return true
        if (segmentsIntersect(x1, y1, x2, y2, rL, rB, rL, rT)) return true

        return false
    }

    private fun segmentsIntersect(
        x1: Double, y1: Double, x2: Double, y2: Double,
        x3: Double, y3: Double, x4: Double, y4: Double,
    ): Boolean {
        fun ccw(ax: Double, ay: Double, bx: Double, by: Double, cx: Double, cy: Double): Double {
            return (bx - ax) * (cy - ay) - (by - ay) * (cx - ax)
        }
        val d1 = ccw(x3, y3, x4, y4, x1, y1)
        val d2 = ccw(x3, y3, x4, y4, x2, y2)
        val d3 = ccw(x1, y1, x2, y2, x3, y3)
        val d4 = ccw(x1, y1, x2, y2, x4, y4)

        if (((d1 > 0 && d2 < 0) || (d1 < 0 && d2 > 0)) &&
            ((d3 > 0 && d4 < 0) || (d3 < 0 && d4 > 0))
        ) {
            return true
        }
        fun onSegment(px: Double, py: Double, qx: Double, qy: Double, rx: Double, ry: Double): Boolean {
            return qx >= minOf(px, rx) - 1e-7 && qx <= maxOf(px, rx) + 1e-7 &&
                qy >= minOf(py, ry) - 1e-7 && qy <= maxOf(py, ry) + 1e-7
        }
        if (abs(d1) < 1e-7 && onSegment(x3, y3, x1, y1, x4, y4)) return true
        if (abs(d2) < 1e-7 && onSegment(x3, y3, x2, y2, x4, y4)) return true
        if (abs(d3) < 1e-7 && onSegment(x1, y1, x3, y3, x2, y2)) return true
        if (abs(d4) < 1e-7 && onSegment(x1, y1, x4, y4, x2, y2)) return true

        return false
    }
}
