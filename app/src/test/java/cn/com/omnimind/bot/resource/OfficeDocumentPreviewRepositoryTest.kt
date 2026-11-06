package cn.com.omnimind.bot.resource

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class OfficeDocumentPreviewRepositoryTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun `long Word paragraphs and final paragraph remain complete`() {
        val paragraphs = List(30) { "$it:${"正文".repeat(200)}:END-$it" }
        val file = archive("long.docx", mapOf(
            "word/document.xml" to "<document><body>${paragraphs.joinToString("") { "<p><r><t>$it</t></r></p>" }}</body></document>"
        ))
        assertEquals(paragraphs, sections(file, "office_word").single()["lines"])
    }

    @Test
    fun `every worksheet row column and long cell remains complete`() {
        val cell = "${"数据".repeat(80)}:END"
        val entries = linkedMapOf(
            "xl/workbook.xml" to "<workbook><sheets>${(0..4).joinToString("") { "<sheet name=\"Sheet$it\" id=\"r$it\"/>" }}</sheets></workbook>",
            "xl/_rels/workbook.xml.rels" to "<Relationships>${(0..4).joinToString("") { "<Relationship Id=\"r$it\" Target=\"worksheets/sheet$it.xml\"/>" }}</Relationships>"
        )
        for (sheet in 0..4) {
            entries["xl/worksheets/sheet$sheet.xml"] = "<worksheet><sheetData>${(1..25).joinToString("") { row ->
                "<row>${(0..11).joinToString("") { column -> "<c r=\"${('A'.code + column).toChar()}$row\" t=\"inlineStr\"><is><t>$cell</t></is></c>" }}</row>"
            }}</sheetData></worksheet>"
        }
        val result = sections(archive("long.xlsx", entries), "office_sheet")
        assertEquals(5, result.size)
        result.forEach { section ->
            val rows = section["tableRows"] as List<*>
            assertEquals(25, rows.size)
            assertEquals(List(12) { cell }, rows.last())
        }
    }

    @Test
    fun `every slide and long text line remains complete`() {
        val lines = List(12) { "$it:${"幻灯片".repeat(100)}:END-$it" }
        val file = archive("long.pptx", (1..10).associate { slide ->
            "ppt/slides/slide$slide.xml" to "<sld>${lines.joinToString("") { "<p><r><t>$it</t></r></p>" }}</sld>"
        })
        val result = sections(file, "office_slide")
        assertEquals(10, result.size)
        assertEquals(lines, result.last()["lines"])
    }

    @Test
    fun `parses namespaced docx body text`() {
        val file = archive("demo.docx", mapOf("word/document.xml" to """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
              <w:body>
                <w:p><w:r><w:t>第一段</w:t></w:r></w:p>
                <w:p><w:r><w:t>第二段</w:t></w:r></w:p>
              </w:body>
            </w:document>
        """.trimIndent()))
        assertEquals(listOf("第一段", "第二段"), sections(file, "office_word").single()["lines"])
    }

    @Test
    fun `parses xlsx shared strings and numeric cells`() {
        val file = workbook("demo.xlsx", """
            <sst><si><t>标题</t></si><si><t>数值</t></si></sst>
        """, """
            <worksheet><sheetData>
              <row><c r="A1" t="s"><v>0</v></c><c r="B1" t="s"><v>1</v></c></row>
              <row><c r="A2"><v>42</v></c><c r="B2"><v>84</v></c></row>
            </sheetData></worksheet>
        """)
        val section = sections(file, "office_sheet").single()
        assertEquals("Sheet1", section["title"])
        assertEquals(listOf(listOf("标题", "数值"), listOf("42", "84")), section["tableRows"])
    }

    @Test
    fun `parses namespaced pptx slide text`() {
        val file = archive("demo.pptx", mapOf("ppt/slides/slide1.xml" to """
            <p:sld xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main"
              xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main">
              <p:cSld><p:spTree><p:sp><p:txBody>
                <a:p><a:r><a:t>第一页标题</a:t></a:r></a:p>
                <a:p><a:r><a:t>第一页说明</a:t></a:r></a:p>
              </p:txBody></p:sp></p:spTree></p:cSld>
            </p:sld>
        """))
        assertEquals(listOf("第一页标题", "第一页说明"), sections(file, "office_slide").single()["lines"])
    }

    @Test
    fun `paragraph text preserves breaks tabs entities rich text and whitespace rules`() {
        val file = archive("text.docx", mapOf("word/document.xml" to """
            <w:document xmlns:w="urn:word"><w:body>
              <w:p><w:r><w:t> 甲&#160;乙 </w:t><w:tab/><w:t> 丙 &amp; 丁 </w:t><w:br/><w:t>第二行</w:t><w:cr/><w:t>第三行</w:t></w:r></w:p>
              <w:p><w:r><w:t> &#160; </w:t></w:r></w:p>
              <w:p><w:r><w:t><![CDATA[<尾段>]]></w:t></w:r></w:p>
            </w:body></w:document>
        """))
        assertEquals(listOf("甲 乙 丙 & 丁\n第二行\n第三行", "<尾段>"), sections(file, "office_word").single()["lines"])
    }

    @Test
    fun `streamed nested targets retain parent first traversal and mixed text order`() {
        val file = archive("nested.docx", mapOf("word/document.xml" to
            "<document><p><t>outer-a</t><p><t>inner</t></p><t>outer-b</t></p>" +
            "<p><t>start<t>nested</t>end</t><tab/><t>tail</t></p></document>"))
        assertEquals(listOf("outer-ainnerouter-b", "inner", "startnestedendnested tail"), sections(file, "office_word").single()["lines"])
    }

    @Test
    fun `UTF8 BOM malformed surrogate bytes and NEL match Dart decoding and trim`() {
        val file = temporary.newFile("utf8.docx")
        val xml = "\uFEFF<document><p><t>\u0085甲\u0085乙\u0085</t></p><p><t>\u0085</t></p><p><t>".toByteArray(Charsets.UTF_8) +
            byteArrayOf(0xed.toByte(), 0xa0.toByte(), 0x80.toByte()) +
            "</t></p><p><t>\uFEFF末尾\uFEFF</t></p></document>".toByteArray(Charsets.UTF_8)
        ZipOutputStream(file.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("word/document.xml"))
            zip.write(xml)
            zip.closeEntry()
        }
        assertEquals(listOf("甲\u0085乙", "\uFFFD\uFFFD\uFFFD", "末尾"), sections(file, "office_word").single()["lines"])
    }

    @Test
    fun `XML declaration may follow leading whitespace accepted by legacy parser`() {
        val file = archive("declaration.docx", mapOf("word/document.xml" to
            "\n\t \r\n<?xml version=\"1.0\" encoding=\"UTF-8\"?><document><p><t> 正文 </t><br/><t> 尾段 </t></p></document>"))
        assertEquals(listOf("正文\n尾段"), sections(file, "office_word").single()["lines"])
    }

    @Test
    fun `literal attribute whitespace and formula CRs remain exactly as in Dart`() {
        val title = "名\r\n页\t签>一"
        val formula = "SUM(A1,\r\nB1)\r+1"
        val cdataFormula = "A\r\nB\rC"
        val file = archive("raw-whitespace.xlsx", mapOf(
            "xl/workbook.xml" to "<workbook><sheets><sheet name=\" $title \" id=\"r1\"/></sheets></workbook>",
            "xl/_rels/workbook.xml.rels" to "<Relationships><Relationship Id=\"r1\" Target=\"worksheets/sheet1.xml\"/></Relationships>",
            "xl/worksheets/sheet1.xml" to "<worksheet><row><c r=\"A1\"><f>$formula</f></c><c r=\"B1\"><f><![CDATA[$cdataFormula]]></f></c></row></worksheet>"
        ))
        val section = sections(file, "office_sheet").single()
        assertEquals(title, section["title"])
        assertEquals(listOf(listOf("=$formula", "=$cdataFormula")), section["tableRows"])
    }

    @Test
    fun `whitespace compatibility does not rewrite CDATA comments processing instructions or DTD markup`() {
        val file = archive("markup.docx", mapOf("word/document.xml" to
            "<?xml version=\"1.0\"?>\r\n<!DOCTYPE document [<!ENTITY ignored 'a>b'>]>\n" +
            "<document><!-- <t>ignored\r\n</t> --><?ignore 'a>b'?>" +
            "<p><t>A</t><t><![CDATA[B\r\nC\rD>]]></t><br/><t>tail</t></p></document>"))
        assertEquals(listOf("AB\nC\nD>\ntail"), sections(file, "office_word").single()["lines"])
    }

    @Test
    fun `sparse sheets keep column positions booleans formulas and legacy shared string indexing`() {
        val file = workbook("sparse.xlsx", "<sst><si><t> </t></si><si><t>标题</t></si><si><r><t>富</t></r><r><t>文本</t></r></si></sst>", """
            <worksheet><sheetData>
              <row>
                <c r="A1" t="s"><v>0</v></c><c r="B1" t="s"><v>1</v></c>
                <c r="C1" t="b"><v>1</v></c><c r="D1" t="b"><v>0</v></c>
                <c r="F1" t="inlineStr"><is><t> 项 </t><tab/><t>目</t><br/><t>二行</t></is></c>
                <c r="AA1"><f>SUM(A1:B1)</f></c>
                <c><v>missing reference</v></c><c r="G1" t="s"><v>2</v></c>
                <c r="ı1"><v>non-ASCII reference ignored</v></c>
              </row>
              <row><c r="B2"><v> 42 </v><f>ignored</f></c><c r="E2"><f>x</f></c></row>
              <row><c r="A3"><v> </v></c></row>
            </sheetData></worksheet>
        """)
        val expectedFirst = MutableList(27) { "" }.apply {
            this[0] = "标题"; this[1] = "富文本"; this[2] = "TRUE"; this[3] = "FALSE"
            this[5] = "项 目\n二行"; this[26] = "=SUM(A1:B1)"
        }
        val expectedSecond = MutableList(27) { "" }.apply { this[1] = "42"; this[4] = "=x" }
        assertEquals(listOf(expectedFirst, expectedSecond), sections(file, "office_sheet").single()["tableRows"])
    }

    @Test
    fun `workbook relationships retain absolute paths missing sheets and empty unnamed sheet`() {
        val file = archive("relationships.xlsx", mapOf(
            "xl/workbook.xml" to "<workbook><sheets><sheet id=\"skip\"/><sheet id=\"missing\"/><sheet id=\"empty\"/><sheet name=\"后表\" id=\"content\"/></sheets></workbook>",
            "xl/_rels/workbook.xml.rels" to "<Relationships><Relationship Id=\"missing\" Target=\"worksheets/absent.xml\"/><Relationship Id=\"empty\" Target=\"/xl/worksheets/empty.xml\"/><Relationship Id=\"content\" Target=\"worksheets/content.xml\"/></Relationships>",
            "xl/worksheets/empty.xml" to "<worksheet><sheetData/></worksheet>",
            "xl/worksheets/content.xml" to "<worksheet><sheetData><row><c r=\"A1\"><v>尾格</v></c></row></sheetData></worksheet>"
        ))
        val result = sections(file, "office_sheet")
        assertEquals(2, result.size)
        assertEquals("", result[0]["title"])
        assertEquals(emptyList<List<String>>(), result[0]["tableRows"])
        assertEquals("后表", result[1]["title"])
        assertEquals(listOf(listOf("尾格")), result[1]["tableRows"])
    }

    @Test
    fun `slides sort numerically and keep empty slides`() {
        val file = archive("ordered.pptx", linkedMapOf(
            "ppt/slides/slide10.xml" to "<sld><p><t>十</t></p></sld>",
            "ppt/slides/slide2.xml" to "<sld/>",
            "ppt/slides/slide1.xml" to "<sld><p><t>一</t></p></sld>",
            "ppt/notesSlides/notesSlide1.xml" to "<sld><p><t>notes ignored</t></p></sld>"
        ))
        assertEquals(listOf(listOf("一"), emptyList<String>(), listOf("十")), sections(file, "office_slide").map { it["lines"] })
    }

    @Test
    fun `missing malformed unsupported and empty documents preserve error categories`() {
        assertFailure("OFFICE_MISSING_ENTRY", archive("missing.docx", emptyMap()), "office_word", "word/document.xml")
        assertFailure("OFFICE_XML_ERROR", archive("broken.docx", mapOf("word/document.xml" to "<document><p>")), "office_word")
        assertFailure("OFFICE_EMPTY_WORD", archive("empty.docx", mapOf("word/document.xml" to "<document/>")), "office_word")
        assertFailure("OFFICE_EMPTY_SLIDES", archive("empty.pptx", emptyMap()), "office_slide")
        assertFailure("OFFICE_UNSUPPORTED", archive("unknown.zip", emptyMap()), "unknown")
        assertFailure("OFFICE_EMPTY_SHEETS", archive("empty.xlsx", mapOf("xl/workbook.xml" to "<workbook/>", "xl/_rels/workbook.xml.rels" to "<Relationships/>")), "office_sheet")
    }

    @Test
    fun `external XML entities are never read as document content`() {
        val secret = temporary.newFile("unrelated.txt").apply { writeText("not preview content") }
        val file = archive("external.docx", mapOf("word/document.xml" to """
            <!DOCTYPE document [<!ENTITY external SYSTEM "${secret.toURI()}">]>
            <document><p><t>safe</t><t>&external;</t></p></document>
        """.trimIndent()))
        assertEquals(listOf("safe&external;"), sections(file, "office_word").single()["lines"])
    }

    @Test
    fun `unknown and declared named entities remain literal like Dart`() {
        val file = archive("entities.docx", mapOf("word/document.xml" to
            "<!DOCTYPE document [<!ENTITY custom 'must not expand'>]>" +
            "<document><p><t>&custom;&unknown;&amp;&lt;&#13;tail</t></p></document>"))
        assertEquals(listOf("&custom;&unknown;&<\ntail"), sections(file, "office_word").single()["lines"])
    }

    private fun workbook(name: String, strings: String, sheet: String): File = archive(name, mapOf(
        "xl/workbook.xml" to "<workbook xmlns:r=\"urn:relationships\"><sheets><sheet name=\"Sheet1\" r:id=\"rId1\"/></sheets></workbook>",
        "xl/_rels/workbook.xml.rels" to "<Relationships><Relationship Id=\"rId1\" Target=\"worksheets/sheet1.xml\"/></Relationships>",
        "xl/sharedStrings.xml" to strings,
        "xl/worksheets/sheet1.xml" to sheet
    ))

    private fun archive(name: String, entries: Map<String, String>): File = temporary.newFile(name).apply {
        ZipOutputStream(outputStream()).use { zip ->
            entries.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun sections(file: File, kind: String): List<Map<String, Any?>> =
        OfficeDocumentPreviewRepository.loadPreview(file.path, kind)["sections"] as List<Map<String, Any?>>

    private fun assertFailure(code: String, file: File, kind: String, detail: String? = null) {
        val error = runCatching { sections(file, kind) }.exceptionOrNull()
        assertTrue("Expected $code but got $error", error is OfficeDocumentPreviewRepository.PreviewException)
        error as OfficeDocumentPreviewRepository.PreviewException
        assertEquals(code, error.code)
        if (detail != null) assertEquals(detail, error.message)
    }
}
