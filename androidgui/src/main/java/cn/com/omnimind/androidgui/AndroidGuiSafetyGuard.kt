package cn.com.omnimind.androidgui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import cn.com.omnimind.baselib.runlog.Action
import cn.com.omnimind.baselib.runlog.State
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
        val baselineNodes = if (!baseline?.xml.isNullOrBlank()) {
            flattenNodes(parseHierarchy(baseline!!.xml))
        } else {
            emptyList()
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
                    }.distinctBy { it.id }

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

                    // 若原点击点被遮挡，但目标存在确证净空点，重定位 Action 坐标至净空点派发
                    if (occlusion.type == OcclusionType.PARTIAL && occlusion.clearPoint != null && occlusion.clearPoint != clickPx) {
                        val clearPx = occlusion.clearPoint
                        val adjustedX = clearPx.first.toDouble() / currentDisplaySize.first * 1000.0
                        val adjustedY = clearPx.second.toDouble() / currentDisplaySize.second * 1000.0
                        val adjustedAction = action.copy(
                            args = action.args + mapOf("x" to adjustedX, "y" to adjustedY),
                        )
                        return GuardResult.Allowed(
                            action = adjustedAction,
                            adjustedCoordinate = clearPx,
                            diagnostics = mapOf(
                                "clear_point_adjusted" to "[${clearPx.first},${clearPx.second}]",
                                "original_coords" to (clickPx?.let { "[${it.first},${it.second}]" } ?: "none"),
                            ),
                        )
                    }

                    return GuardResult.Allowed(action = action)
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

        // 3. 多候选时，尝试使用点击坐标进行唯一消歧；若仍有多个候选包含坐标，或者没有候选包含坐标，坚决拒绝猜选！
        val containing = if (clickPx != null) {
            candidates.filter { it.bounds.contains(clickPx.first, clickPx.second) }
        } else {
            emptyList()
        }

        if (containing.size == 1) {
            return BaselineTargetLookup.Found(containing[0])
        }

        // 无法唯一确定，明确返回 Ambiguous（绝不当成无候选 fallback 漏放）
        return BaselineTargetLookup.Ambiguous(candidates.size)
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
            diffRatio > 0.4
        } finally {
            baselineBitmap.recycle()
            freshBitmap.recycle()
        }
    }.getOrDefault(false)

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
}
