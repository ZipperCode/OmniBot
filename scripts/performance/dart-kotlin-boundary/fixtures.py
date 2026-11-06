"""Deterministic parser-only OOXML containers containing generated text."""
from pathlib import Path
from zipfile import ZipFile, ZipInfo, ZIP_DEFLATED


def _write(path, entries):
    with ZipFile(path, "w") as archive:
        for name, text in entries:
            item = ZipInfo(name, (1980, 1, 1, 0, 0, 0))
            item.compress_type = ZIP_DEFLATED
            archive.writestr(item, text.encode("utf-8"))


def create(directory):
    directory = Path(directory)
    directory.mkdir(parents=True, exist_ok=True)
    paragraphs = [
        f"<p><r><t>{i}:正文 performance {'数据 sample ' * 50}:END-{i}</t>"
        "<tab/><t>after-tab</t><br/><t>after-break</t></r></p>"
        for i in range(3000)
    ]
    _write(directory / "word.docx", [("word/document.xml", "<document><body>" + "".join(paragraphs) + "</body></document>")])
    entries = [
        ("xl/workbook.xml", '<workbook xmlns:r="urn:relationships"><sheets>' + "".join(
            f'<sheet name="Sheet {i + 1}" r:id="rId{i + 1}"/>' for i in range(5)
        ) + "</sheets></workbook>"),
        ("xl/_rels/workbook.xml.rels", "<Relationships>" + "".join(
            f'<Relationship Id="rId{i + 1}" Target="worksheets/sheet{i}.xml"/>' for i in range(5)
        ) + "</Relationships>"),
        ("xl/sharedStrings.xml", "<sst><si><t>Generated</t></si></sst>"),
    ]
    for sheet in range(5):
        rows = []
        for row in range(500):
            cells = "".join(
                f'<c r="{chr(65 + col)}{row + 1}" t="inlineStr"><is><t>R{row}C{col} {"数据 " * 12}</t></is></c>'
                for col in range(12)
            )
            rows.append("<row>" + cells + "</row>")
        entries.append((f"xl/worksheets/sheet{sheet}.xml", "<worksheet><sheetData>" + "".join(rows) + "</sheetData></worksheet>"))
    _write(directory / "sheet.xlsx", entries)
    _write(directory / "slides.pptx", [
        (f"ppt/slides/slide{slide}.xml", "<sld>" + "".join(
            f"<p><r><t>{slide}-{paragraph} 幻灯片 {'sample 中文 ' * 70}:END</t></r></p>"
            for paragraph in range(30)
        ) + "</sld>") for slide in range(1, 41)
    ])
