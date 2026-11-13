from __future__ import annotations

from pathlib import Path
import sys
import unittest

tests_dir = Path(__file__).resolve().parent
if str(tests_dir) not in sys.path:
    sys.path.insert(0, str(tests_dir))

import test_screen_perception_behavior as perception_module


class ScreenCollectionBehaviorTest(unittest.TestCase):
    """多屏采集纯函数提取与跨页合并行为测试套件。

    复用既有 ScreenPerceptionBehaviorTest 加载 fixture，不继承其 TestCase 避免 discover 重复原 38 个用例。
    """

    @classmethod
    def setUpClass(cls) -> None:
        perception_module.ScreenPerceptionBehaviorTest.setUpClass()

    @classmethod
    def tearDownClass(cls) -> None:
        perception_module.ScreenPerceptionBehaviorTest.tearDownClass()

    def test_duplicate_messages_preserved_on_same_page(self) -> None:
        """单页内同文不同位置条目必须完整保留，不得被集合去重。"""
        from omniflow.core.model import Observation
        from omniflow.runtime.screen_collection import extract_page_lines

        xml = (
            '<hierarchy rotation="0">'
            '<node class="android.widget.TextView" text="哈哈" bounds="[100,200][500,260]" id="t1" />'
            '<node class="android.widget.TextView" text="哈哈" bounds="[100,400][500,460]" id="t2" />'
            "</hierarchy>"
        )
        obs = Observation.from_value(
            {
                "xml": xml,
                "display": {"width": 1000, "height": 1000},
                "extra": {"display": {"width": 1000, "height": 1000}, "state_id": "state_dup_page"},
            }
        )
        lines = extract_page_lines(obs, (0.0, 0.0, 1000.0, 1000.0), include_ocr=False)
        self.assertEqual(len(lines), 2)
        self.assertEqual(lines[0]["text"], "哈哈")
        self.assertEqual(lines[1]["text"], "哈哈")
        self.assertEqual(lines[0]["source"], "xml")
        self.assertEqual(lines[1]["source"], "xml")
        self.assertEqual(lines[0]["state_id"], "state_dup_page")
        self.assertEqual(lines[1]["state_id"], "state_dup_page")
        self.assertEqual(lines[0]["bounds"], [100.0, 200.0, 500.0, 260.0])
        self.assertEqual(lines[1]["bounds"], [100.0, 400.0, 500.0, 460.0])

    def test_duplicate_messages_preserved_across_pages_merge(self) -> None:
        """跨页滚动时保留后续再次出现的同名聊天条目，不作全局文本集合去重。"""
        from omniflow.runtime.screen_collection import merge_page_lines

        # Page 0 提取结果
        page_0 = [
            {"text": "Msg A", "bounds": [50.0, 150.0, 500.0, 200.0], "source": "xml", "state_id": "s0"},
            {"text": "Msg B", "bounds": [50.0, 250.0, 500.0, 300.0], "source": "xml", "state_id": "s0"},
            {"text": "Msg C", "bounds": [50.0, 350.0, 500.0, 400.0], "source": "xml", "state_id": "s0"},
            {"text": "Msg D", "bounds": [50.0, 450.0, 500.0, 500.0], "source": "xml", "state_id": "s0"},
        ]
        # Page 1 提取结果：Msg C, Msg D 重叠，随后出现重复内容 Msg A 和新内容 Msg E
        page_1 = [
            {"text": "Msg C", "bounds": [50.0, 150.0, 500.0, 200.0], "source": "xml", "state_id": "s1"},
            {"text": "Msg D", "bounds": [50.0, 250.0, 500.0, 300.0], "source": "xml", "state_id": "s1"},
            {"text": "Msg A", "bounds": [50.0, 350.0, 500.0, 400.0], "source": "xml", "state_id": "s1"},
            {"text": "Msg E", "bounds": [50.0, 450.0, 500.0, 500.0], "source": "xml", "state_id": "s1"},
        ]

        new_items, uncertain = merge_page_lines(page_0, page_1, direction="down")
        self.assertFalse(uncertain)
        self.assertEqual([it["text"] for it in new_items], ["Msg A", "Msg E"])

    def test_xml_ocr_overlap_deduplication_xml_priority(self) -> None:
        """同文且重叠 >= 80% 的 XML/OCR 条目只保留一个，且 XML 优先。"""
        from omniflow.core.model import Observation
        from omniflow.runtime.screen_collection import extract_page_lines

        xml = (
            '<hierarchy rotation="0">'
            '<node class="android.widget.TextView" text="去结算" bounds="[100,200][400,260]" id="btn" />'
            "</hierarchy>"
        )
        obs = Observation.from_value(
            {
                "state_id": "state_overlap",
                "xml": xml,
                "display": {"width": 1000, "height": 1000},
                "extra": {
                    "display": {"width": 1000, "height": 1000},
                    "ocr": {
                        "status": "ready",
                        "items": [
                            {
                                "text": "去结算",
                                "bounds": [105, 205, 395, 255],
                                "confidence": 0.95,
                                "source": "ocr",
                            }
                        ],
                    },
                },
            }
        )
        lines = extract_page_lines(obs, (0.0, 0.0, 1000.0, 1000.0), include_ocr=True)
        self.assertEqual(len(lines), 1)
        self.assertEqual(lines[0]["text"], "去结算")
        self.assertEqual(lines[0]["source"], "xml")
        self.assertEqual(lines[0]["bounds"], [100.0, 200.0, 400.0, 260.0])

    def test_prune_hidden_and_password_ancestors(self) -> None:
        """剪枝不可见节点与密码子树，隐藏或密码祖先下的所有子孙节点均不可提取。"""
        from omniflow.core.model import Observation
        from omniflow.runtime.screen_collection import extract_page_lines

        xml = (
            '<hierarchy rotation="0">'
            '<node class="android.widget.FrameLayout" password="true">'
            '  <node class="android.widget.TextView" text="SecretPwd123" bounds="[100,100][400,150]" />'
            "</node>"
            '<node class="android.widget.LinearLayout" visible="false">'
            '  <node class="android.widget.TextView" text="HiddenContent" bounds="[100,200][400,250]" />'
            "</node>"
            '<node class="android.widget.RelativeLayout" displayed="false">'
            '  <node class="android.widget.TextView" text="NotDisplayed" bounds="[100,300][400,350]" />'
            "</node>"
            '<node class="android.widget.FrameLayout" visible-to-user="false">'
            '  <node class="android.widget.TextView" text="NotVisibleToUser" bounds="[100,400][400,450]" />'
            "</node>"
            '<node class="android.widget.TextView" text="NormalItem" bounds="[100,500][400,550]" />'
            "</hierarchy>"
        )
        obs = Observation.from_value(
            {
                "state_id": "state_prune",
                "xml": xml,
                "display": {"width": 1000, "height": 1000},
                "extra": {"display": {"width": 1000, "height": 1000}},
            }
        )
        lines = extract_page_lines(obs, (0.0, 0.0, 1000.0, 1000.0), include_ocr=False)
        self.assertEqual(len(lines), 1)
        self.assertEqual(lines[0]["text"], "NormalItem")

    def test_region_filtering_and_intersection(self) -> None:
        """区域相交过滤：完全在区域外的条目排除，相交条目保留；非法 region 返回空列表。"""
        from omniflow.core.model import Observation
        from omniflow.runtime.screen_collection import extract_page_lines

        xml = (
            '<hierarchy rotation="0">'
            '<node class="android.widget.TextView" text="AboveRegion" bounds="[100,50][400,120]" />'
            '<node class="android.widget.TextView" text="TopIntersect" bounds="[100,180][400,250]" />'
            '<node class="android.widget.TextView" text="InsideRegion" bounds="[100,400][400,480]" />'
            '<node class="android.widget.TextView" text="BottomIntersect" bounds="[100,780][400,850]" />'
            '<node class="android.widget.TextView" text="BelowRegion" bounds="[100,880][400,950]" />'
            "</hierarchy>"
        )
        obs = Observation.from_value(
            {
                "state_id": "state_region",
                "xml": xml,
                "display": {"width": 1000, "height": 1000},
                "extra": {"display": {"width": 1000, "height": 1000}},
            }
        )
        region = (100.0, 200.0, 900.0, 800.0)
        lines = extract_page_lines(obs, region, include_ocr=False)
        texts = [it["text"] for it in lines]
        self.assertEqual(texts, ["TopIntersect", "InsideRegion", "BottomIntersect"])

        # 非法 region（无正面积）
        invalid_region = (800.0, 200.0, 200.0, 800.0)
        self.assertEqual(extract_page_lines(obs, invalid_region, include_ocr=False), [])

    def test_ocr_low_confidence_and_status_not_ready(self) -> None:
        """OCR 低置信度 (<0.5) 或 status!=ready 项不参与，失败保留 XML。"""
        from omniflow.core.model import Observation
        from omniflow.runtime.screen_collection import extract_page_lines

        xml = (
            '<hierarchy rotation="0">'
            '<node class="android.widget.TextView" text="XmlTitle" bounds="[100,100][400,150]" />'
            "</hierarchy>"
        )
        # 1. OCR status 为 unavailable 时，跳过 OCR，保留 XML
        obs_unavail = Observation.from_value(
            {
                "state_id": "state_ocr_unavail",
                "xml": xml,
                "display": {"width": 1000, "height": 1000},
                "extra": {
                    "display": {"width": 1000, "height": 1000},
                    "ocr": {
                        "status": "unavailable",
                        "items": [
                            {"text": "OcrText", "bounds": [100, 300, 400, 350], "confidence": 0.99, "source": "ocr"}
                        ],
                    },
                },
            }
        )
        lines_unavail = extract_page_lines(obs_unavail, (0.0, 0.0, 1000.0, 1000.0), include_ocr=True)
        self.assertEqual(len(lines_unavail), 1)
        self.assertEqual(lines_unavail[0]["text"], "XmlTitle")

        # 2. OCR 置信度 < 0.5 排除，>= 0.5 保留
        obs_ready = Observation.from_value(
            {
                "state_id": "state_ocr_ready",
                "xml": "",
                "display": {"width": 1000, "height": 1000},
                "extra": {
                    "display": {"width": 1000, "height": 1000},
                    "ocr": {
                        "status": "ready",
                        "items": [
                            {"text": "LowConf", "bounds": [100, 200, 400, 250], "confidence": 0.49, "source": "ocr"},
                            {"text": "HighConf", "bounds": [100, 400, 400, 450], "confidence": 0.50, "source": "ocr"},
                        ],
                    },
                },
            }
        )
        lines_ready = extract_page_lines(obs_ready, (0.0, 0.0, 1000.0, 1000.0), include_ocr=True)
        self.assertEqual(len(lines_ready), 1)
        self.assertEqual(lines_ready[0]["text"], "HighConf")

    def test_suffix_prefix_overlap_merge_down(self) -> None:
        """阅读后页（down，手指上滑）：相邻页最大有序后缀/前缀重叠（>=2项）去重。"""
        from omniflow.runtime.screen_collection import merge_page_lines

        page_0 = [
            {"text": f"ITEM_{i:02d}", "bounds": [100.0, float(i * 100), 500.0, float(i * 100 + 50)], "source": "xml", "state_id": "p0"}
            for i in range(1, 7)
        ]
        # page_1 包含 ITEM_05, ITEM_06 作为前缀
        page_1 = [
            {"text": f"ITEM_{i:02d}", "bounds": [100.0, float((i - 4) * 100), 500.0, float((i - 4) * 100 + 50)], "source": "xml", "state_id": "p1"}
            for i in range(5, 11)
        ]

        new_items, uncertain = merge_page_lines(page_0, page_1, direction="down")
        self.assertFalse(uncertain)
        self.assertEqual(
            [it["text"] for it in new_items],
            ["ITEM_07", "ITEM_08", "ITEM_09", "ITEM_10"],
        )

    def test_fixed_header_and_footer_excluded_from_scrolling(self) -> None:
        """固定顶/底部同位置条目仅作为边界排除，不干扰正文重叠匹配且不重复添加。"""
        from omniflow.runtime.screen_collection import merge_page_lines

        header = {"text": "聊天窗口", "bounds": [0.0, 0.0, 1000.0, 80.0], "source": "xml", "state_id": "s"}
        footer = {"text": "发送", "bounds": [800.0, 920.0, 980.0, 980.0], "source": "xml", "state_id": "s"}

        page_0 = [
            header,
            {"text": "Msg 1", "bounds": [50.0, 150.0, 500.0, 200.0], "source": "xml", "state_id": "s0"},
            {"text": "Msg 2", "bounds": [50.0, 250.0, 500.0, 300.0], "source": "xml", "state_id": "s0"},
            {"text": "Msg 3", "bounds": [50.0, 350.0, 500.0, 400.0], "source": "xml", "state_id": "s0"},
            footer,
        ]
        page_1 = [
            header,
            {"text": "Msg 2", "bounds": [50.0, 150.0, 500.0, 200.0], "source": "xml", "state_id": "s1"},
            {"text": "Msg 3", "bounds": [50.0, 250.0, 500.0, 300.0], "source": "xml", "state_id": "s1"},
            {"text": "Msg 4", "bounds": [50.0, 350.0, 500.0, 400.0], "source": "xml", "state_id": "s1"},
            footer,
        ]

        new_items, uncertain = merge_page_lines(page_0, page_1, direction="down")
        self.assertFalse(uncertain)
        self.assertEqual([it["text"] for it in new_items], ["Msg 4"])

    def test_up_direction_reading_order(self) -> None:
        """反向 up 阅读按实际屏页顶到底顺序返回，携带正常顺序（非对称内容验证）。"""
        from omniflow.runtime.screen_collection import merge_page_lines

        # Page 0 (当前基线屏): 头部为 Alpha, Beta，尾部有多个不同条目
        page_0 = [
            {"text": "Alpha", "bounds": [100.0, 100.0, 500.0, 150.0], "source": "xml", "state_id": "p0"},
            {"text": "Beta", "bounds": [100.0, 200.0, 500.0, 250.0], "source": "xml", "state_id": "p0"},
            {"text": "Gamma", "bounds": [100.0, 300.0, 500.0, 350.0], "source": "xml", "state_id": "p0"},
            {"text": "Delta", "bounds": [100.0, 400.0, 500.0, 450.0], "source": "xml", "state_id": "p0"},
            {"text": "Epsilon", "bounds": [100.0, 500.0, 500.0, 550.0], "source": "xml", "state_id": "p0"},
        ]
        # Page 1 (向上滑出更早的前文屏): 尾部为 Alpha, Beta (与 Page 0 头部重叠)，前部有 4 个新增条目
        page_1 = [
            {"text": "Prev1", "bounds": [100.0, 100.0, 500.0, 150.0], "source": "xml", "state_id": "p1"},
            {"text": "Prev2", "bounds": [100.0, 200.0, 500.0, 250.0], "source": "xml", "state_id": "p1"},
            {"text": "Prev3", "bounds": [100.0, 300.0, 500.0, 350.0], "source": "xml", "state_id": "p1"},
            {"text": "Prev4", "bounds": [100.0, 400.0, 500.0, 450.0], "source": "xml", "state_id": "p1"},
            {"text": "Alpha", "bounds": [100.0, 500.0, 500.0, 550.0], "source": "xml", "state_id": "p1"},
            {"text": "Beta", "bounds": [100.0, 600.0, 500.0, 650.0], "source": "xml", "state_id": "p1"},
        ]

        new_items, uncertain = merge_page_lines(page_0, page_1, direction="up")
        self.assertFalse(uncertain)
        # 结果必须为屏内自顶向下的真实顺序，绝不可倒序
        self.assertEqual([it["text"] for it in new_items], ["Prev1", "Prev2", "Prev3", "Prev4"])
    def test_no_sufficient_overlap_and_identical_page(self) -> None:
        """重叠不足 (<2项) 时保留整页并标 uncertain=true；整页完全相同判无新项 ([], false)。"""
        from omniflow.runtime.screen_collection import merge_page_lines

        page_0 = [
            {"text": "ITEM_01", "bounds": [100.0, 100.0, 500.0, 150.0], "source": "xml", "state_id": "p0"},
            {"text": "ITEM_02", "bounds": [100.0, 200.0, 500.0, 250.0], "source": "xml", "state_id": "p0"},
            {"text": "ITEM_03", "bounds": [100.0, 300.0, 500.0, 350.0], "source": "xml", "state_id": "p0"},
        ]

        # 1. 只有 1 项重叠 (<2)，无法确定重叠边界，保留当前页所有条目并标 uncertain=true
        page_1_few_overlap = [
            {"text": "ITEM_03", "bounds": [100.0, 100.0, 500.0, 150.0], "source": "xml", "state_id": "p1"},
            {"text": "ITEM_04", "bounds": [100.0, 200.0, 500.0, 250.0], "source": "xml", "state_id": "p1"},
            {"text": "ITEM_05", "bounds": [100.0, 300.0, 500.0, 350.0], "source": "xml", "state_id": "p1"},
        ]
        new_items, uncertain = merge_page_lines(page_0, page_1_few_overlap, direction="down")
        self.assertTrue(uncertain)
        self.assertEqual([it["text"] for it in new_items], ["ITEM_03", "ITEM_04", "ITEM_05"])

        # 2. 完全无重叠
        page_1_no_overlap = [
            {"text": "ITEM_10", "bounds": [100.0, 100.0, 500.0, 150.0], "source": "xml", "state_id": "p1"},
            {"text": "ITEM_11", "bounds": [100.0, 200.0, 500.0, 250.0], "source": "xml", "state_id": "p1"},
        ]
        new_items_no, uncertain_no = merge_page_lines(page_0, page_1_no_overlap, direction="down")
        self.assertTrue(uncertain_no)
        self.assertEqual([it["text"] for it in new_items_no], ["ITEM_10", "ITEM_11"])

        # 3. 整页完全相同判无新项 ([], false)
        new_items_same, uncertain_same = merge_page_lines(page_0, list(page_0), direction="down")
        self.assertFalse(uncertain_same)
        self.assertEqual(new_items_same, [])

    def test_content_description_fallback_and_top_left_sorting(self) -> None:
        """无 text 时回退 content-desc，页面按 top, left 严格排序。"""
        from omniflow.core.model import Observation
        from omniflow.runtime.screen_collection import extract_page_lines

        xml = (
            '<hierarchy rotation="0">'
            '<node class="android.widget.ImageButton" content-desc="搜索" bounds="[600,100][800,180]" />'
            '<node class="android.widget.Button" text="" contentDescription="设置" bounds="[100,100][300,180]" />'
            '<node class="android.widget.TextView" text="标题" bounds="[320,100][580,180]" />'
            '<node class="android.widget.TextView" text="正文" bounds="[100,300][900,400]" />'
            "</hierarchy>"
        )
        obs = Observation.from_value(
            {
                "state_id": "state_sort",
                "xml": xml,
                "display": {"width": 1000, "height": 1000},
                "extra": {"display": {"width": 1000, "height": 1000}},
            }
        )
        lines = extract_page_lines(obs, (0.0, 0.0, 1000.0, 1000.0), include_ocr=False)
        self.assertEqual(len(lines), 4)
        # 同一 y1=100 行按 x1 升序：100(设置) -> 320(标题) -> 600(搜索)，然后是 y1=300(正文)
        self.assertEqual([it["text"] for it in lines], ["设置", "标题", "搜索", "正文"])

    def test_display_1080x2400_coordinate_normalization_and_region_filtering(self) -> None:
        """真实分辨率 1080x2400 下准确归一化为 0..1000 规范坐标，防全 1000 掩盖像素换算错误。"""
        from omniflow.core.model import Observation
        from omniflow.runtime.screen_collection import extract_page_lines

        # 1080x2400 屏幕下：
        # node1: 像素 [108, 240][540, 480] -> 规范坐标 [100.0, 100.0, 500.0, 200.0]
        # node2: 像素 [108, 2280][540, 2400] -> 规范坐标 [100.0, 950.0, 500.0, 1000.0]
        xml = (
            '<hierarchy rotation="0">'
            '<node class="android.widget.TextView" text="ItemInRegion" bounds="[108,240][540,480]" />'
            '<node class="android.widget.TextView" text="ItemOutRegion" bounds="[108,2280][540,2400]" />'
            "</hierarchy>"
        )
        obs = Observation.from_value(
            {
                "xml": xml,
                "display": {"width": 1080, "height": 2400},
                "extra": {
                    "display": {"width": 1080, "height": 2400},
                    "state_id": "state_1080x2400",
                    "ocr": {
                        "status": "ready",
                        "items": [
                            # OCR 像素 [540, 1200, 864, 1440] -> 规范坐标 [500.0, 500.0, 800.0, 600.0]
                            {"text": "OcrInRegion", "bounds": [540, 1200, 864, 1440], "confidence": 0.9, "source": "ocr"}
                        ],
                    },
                },
            }
        )
        # region: canonical [50.0, 50.0, 950.0, 900.0]
        region = (50.0, 50.0, 950.0, 900.0)
        lines = extract_page_lines(obs, region, include_ocr=True)
        self.assertEqual(len(lines), 2)
        self.assertEqual(lines[0]["text"], "ItemInRegion")
        self.assertEqual(lines[0]["bounds"], [100.0, 100.0, 500.0, 200.0])
        self.assertEqual(lines[0]["state_id"], "state_1080x2400")
        self.assertEqual(lines[1]["text"], "OcrInRegion")
        self.assertEqual(lines[1]["bounds"], [500.0, 500.0, 800.0, 600.0])
        self.assertEqual(lines[1]["state_id"], "state_1080x2400")

    def test_missing_or_invalid_display_returns_empty(self) -> None:
        """屏幕尺寸缺失或非正数时不伪造默认 1000 坐标，直接返回空列表。"""
        from omniflow.core.model import Observation
        from omniflow.runtime.screen_collection import extract_page_lines

        obs_no_display = Observation.from_value({"xml": '<node text="T" bounds="[0,0][100,100]"/>', "extra": {}})
        self.assertEqual(extract_page_lines(obs_no_display, (0.0, 0.0, 1000.0, 1000.0), include_ocr=False), [])

        obs_zero_display = Observation.from_value({
            "xml": '<node text="T" bounds="[0,0][100,100]"/>',
            "extra": {"display": {"width": 0, "height": 0}},
        })
        self.assertEqual(extract_page_lines(obs_zero_display, (0.0, 0.0, 1000.0, 1000.0), include_ocr=False), [])
