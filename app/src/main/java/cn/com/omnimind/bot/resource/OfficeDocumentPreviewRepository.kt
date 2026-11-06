package cn.com.omnimind.bot.resource

import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.helpers.DefaultHandler
import java.io.File
import java.io.StringReader
import java.util.ArrayDeque
import java.util.zip.ZipFile
import javax.xml.parsers.SAXParserFactory

/** Reads Office content only. Flutter owns preview labels, localization and rendering. */
object OfficeDocumentPreviewRepository {
    class PreviewException(val code: String, detail: String? = null) : Exception(detail)

    private data class Section(
        val title: String? = null,
        val lines: List<String> = emptyList(),
        val tableRows: List<List<String>> = emptyList(),
    ) {
        fun toPayload(): Map<String, Any?> = mapOf(
            "title" to title,
            "lines" to lines,
            "tableRows" to tableRows,
        )
    }

    // Only the current paragraph, worksheet row or shared string is retained.
    private class XmlElement(val name: String, val attributes: List<Pair<String, String>>) {
        var children: MutableList<XmlElement>? = null
        val text = if (name == "t" || name == "v" || name == "f") StringBuilder() else null

        fun addChild(child: XmlElement) {
            val existing = children
            if (existing == null) children = mutableListOf(child) else existing.add(child)
        }
    }

    // A reader is reused only for the sequential XML entries of this one file.
    // No parser or handler state is shared across preview requests.
    private class ParseSession(val archive: ZipFile) {
        val reader by lazy {
            SAXParserFactory.newInstance().apply {
                isNamespaceAware = false
                isValidating = false
            }.newSAXParser().xmlReader
        }
    }

    private val slidePath = Regex("^ppt/slides/slide(\\d+)\\.xml$")
    private val leadingDeclarationWhitespace = Regex("^[ \\t\\r\\n]+(?=<\\?xml\\b)")
    private val entityName = Regex("[\\p{L}_:][\\p{L}\\p{N}\\p{M}_.:\\-]*")
    private val predefinedEntities = setOf("amp", "lt", "gt", "apos", "quot")

    /** Call from a background dispatcher. ZIP entries are opened only when needed. */
    fun loadPreview(path: String, previewKind: String): Map<String, Any?> {
        ZipFile(File(path)).use { archive ->
            val session = ParseSession(archive)
            val sections = when (previewKind) {
                "office_word" -> parseWord(session)
                "office_sheet" -> parseWorkbook(session)
                "office_slide" -> parseSlides(session)
                else -> throw PreviewException("OFFICE_UNSUPPORTED")
            }
            return mapOf("sections" to sections.map(Section::toPayload))
        }
    }

    private fun parseWord(session: ParseSession): List<Section> {
        val paragraphs = mutableListOf<String>()
        forEachElement(session, "word/document.xml", "p", required = true) { paragraph ->
            normalizeText(collectText(paragraph)).takeIf(String::isNotEmpty)?.let(paragraphs::add)
        }
        if (paragraphs.isEmpty()) throw PreviewException("OFFICE_EMPTY_WORD")
        return listOf(Section(lines = paragraphs))
    }

    private fun parseWorkbook(session: ParseSession): List<Section> {
        val sheets = mutableListOf<Pair<String, String>>()
        forEachElement(session, "xl/workbook.xml", "sheet", required = true) { sheet ->
            sheets.add(attribute(sheet, "name") to attribute(sheet, "id"))
        }
        val targets = mutableMapOf<String, String>()
        forEachElement(session, "xl/_rels/workbook.xml.rels", "Relationship", required = true) { relation ->
            val id = attribute(relation, "Id")
            val target = attribute(relation, "Target")
            if (id.isNotEmpty() && target.isNotEmpty()) targets[id] = target
        }
        val sharedStrings = mutableListOf<String>()
        forEachElement(session, "xl/sharedStrings.xml", "si") { item ->
            normalizeText(collectText(item)).takeIf(String::isNotEmpty)?.let(sharedStrings::add)
        }
        val sections = mutableListOf<Section>()
        for ((name, id) in sheets) {
            val target = targets[id] ?: continue
            val entryPath = if (target.startsWith('/')) target.substring(1) else "xl/$target"
            parseSheet(session, entryPath, name, sharedStrings)?.let(sections::add)
        }
        if (sections.isEmpty()) throw PreviewException("OFFICE_EMPTY_SHEETS")
        return sections
    }

    private fun parseSheet(
        session: ParseSession,
        path: String,
        title: String,
        sharedStrings: List<String>,
    ): Section? {
        if (session.archive.getEntry(path) == null) return null
        val rows = mutableListOf<Map<Int, String>>()
        var maxColumn = -1
        forEachElement(session, path, "row") { row ->
            val values = mutableMapOf<Int, String>()
            for (cell in row.children.orEmpty()) {
                if (cell.name != "c") continue
                val column = columnIndex(attribute(cell, "r"))
                if (column < 0) continue
                val value = cellValue(cell, sharedStrings)
                if (value.isNotEmpty()) {
                    values[column] = value
                    maxColumn = maxOf(maxColumn, column)
                }
            }
            if (values.isNotEmpty()) rows.add(values)
        }
        return Section(
            title = title,
            tableRows = rows.map { row -> List(maxColumn + 1) { row[it].orEmpty() } },
        )
    }

    private fun cellValue(cell: XmlElement, sharedStrings: List<String>): String {
        val type = attribute(cell, "t")
        if (type == "inlineStr") return normalizeText(collectText(cell))
        val value = firstText(cell, "v")
        return when (type) {
            "s" -> value.toIntOrNull()?.let(sharedStrings::getOrNull).orEmpty()
            "b" -> if (value == "1") "TRUE" else "FALSE"
            else -> {
                if (value.isNotEmpty()) return normalizeText(value)
                val formula = firstText(cell, "f")
                if (formula.isNotEmpty()) "=$formula" else normalizeText(collectText(cell))
            }
        }
    }

    private fun parseSlides(session: ParseSession): List<Section> {
        val slides = session.archive.entries().asSequence()
            .filter { !it.isDirectory && slidePath.matches(it.name) }
            .sortedBy { slidePath.matchEntire(it.name)?.groupValues?.get(1)?.toLongOrNull() ?: 0L }
            .toList()
        if (slides.isEmpty()) throw PreviewException("OFFICE_EMPTY_SLIDES")
        return slides.map { entry ->
            val lines = mutableListOf<String>()
            forEachElement(session, entry.name, "p", required = true) { paragraph ->
                normalizeText(collectText(paragraph)).takeIf(String::isNotEmpty)?.let(lines::add)
            }
            Section(lines = lines)
        }
    }

    private fun forEachElement(
        session: ParseSession,
        path: String,
        targetName: String,
        required: Boolean = false,
        consume: (XmlElement) -> Unit,
    ) {
        val entry = session.archive.getEntry(path)
        if (entry == null) {
            if (required) throw PreviewException("OFFICE_MISSING_ENTRY", path)
            return
        }
        val xml = session.archive.getInputStream(entry).use { decodeUtf8(it.readBytes()) }
            .removePrefix("\uFEFF")
            .replace(leadingDeclarationWhitespace, "")
        val handler = object : DefaultHandler() {
            private val captured = mutableListOf<XmlElement>()

            override fun startElement(uri: String?, local: String?, name: String, attributes: Attributes) {
                val normalizedName = localName(name)
                if (captured.isEmpty() && normalizedName != targetName) return
                val values = if (attributes.length == 0) emptyList() else List(attributes.length) { index ->
                    localName(attributes.getQName(index)) to attributes.getValue(index)
                }
                val element = XmlElement(normalizedName, values)
                captured.lastOrNull()?.addChild(element)
                captured.add(element)
            }

            override fun characters(characters: CharArray, start: Int, length: Int) {
                for (element in captured) element.text?.append(characters, start, length)
            }

            override fun ignorableWhitespace(characters: CharArray, start: Int, length: Int) =
                this.characters(characters, start, length)

            override fun endElement(uri: String?, local: String?, name: String?) {
                if (captured.isEmpty()) return
                val element = captured.removeAt(captured.lastIndex)
                if (captured.isEmpty()) {
                    // Legacy descendants traverse parents before nested targets.
                    consume(element)
                    forEachDescendant(element) { child ->
                        if (child.name == targetName) consume(child)
                    }
                }
            }
        }
        try {
            val reader = session.reader
            reader.contentHandler = handler
            reader.errorHandler = handler
            // The resolver never performs external I/O, including external DTDs.
            reader.entityResolver = org.xml.sax.EntityResolver { _, _ -> InputSource(StringReader("")) }
            reader.parse(InputSource(StringReader(preserveXmlWhitespace(xml))))
        } catch (error: SAXException) {
            throw PreviewException("OFFICE_XML_ERROR", error.message)
        }
    }

    private inline fun forEachDescendant(element: XmlElement, visit: (XmlElement) -> Unit) {
        val children = element.children ?: return
        val pending = ArrayDeque<XmlElement>()
        for (index in children.lastIndex downTo 0) pending.addLast(children[index])
        while (pending.isNotEmpty()) {
            val child = pending.removeLast()
            visit(child)
            val nested = child.children ?: continue
            for (index in nested.lastIndex downTo 0) pending.addLast(nested[index])
        }
    }

    private fun collectText(element: XmlElement): String = buildString {
        forEachDescendant(element) { child ->
            when (child.name) {
                "t" -> append(child.text)
                "tab" -> append("    ")
                "br", "cr" -> append('\n')
            }
        }
    }

    private fun firstText(element: XmlElement, name: String): String {
        forEachDescendant(element) { child ->
            if (child.name == name) return trim(child.text?.toString().orEmpty())
        }
        return ""
    }

    private fun attribute(element: XmlElement, name: String): String {
        for (index in element.attributes.indices) {
            val attribute = element.attributes[index]
            if (attribute.first == name) return trim(attribute.second)
        }
        return ""
    }

    private fun localName(name: String): String = name.substringAfter(':')
    // Dart's RegExp \\s uses this ECMAScript set; trim additionally removes NEL.
    // A character scan avoids a platform regex matcher for every cell and line.
    private fun isWhitespace(character: Char): Boolean = when (character) {
        in '\t'..'\r', ' ', '\u00A0', '\u1680', in '\u2000'..'\u200A',
        '\u2028', '\u2029', '\u202F', '\u205F', '\u3000', '\uFEFF' -> true
        else -> false
    }

    private fun isTrimWhitespace(character: Char): Boolean =
        character == '\u0085' || isWhitespace(character)

    private fun trim(value: String): String {
        var start = 0
        var end = value.length
        while (start < end && isTrimWhitespace(value[start])) start++
        while (end > start && isTrimWhitespace(value[end - 1])) end--
        return if (start == 0 && end == value.length) value else value.substring(start, end)
    }

    private fun columnIndex(reference: String): Int {
        var index = 0
        var letters = 0
        for (character in reference) {
            if (character !in 'A'..'Z' && character !in 'a'..'z') break
            val upper = character.uppercaseChar()
            index = index * 26 + (upper.code - 'A'.code + 1)
            letters++
        }
        return if (letters == 0) -1 else index - 1
    }

    private fun normalizeText(value: String): String = buildString(value.length) {
        var lineStart = 0
        for (index in 0..value.length) {
            if (index < value.length && value[index] != '\r' && value[index] != '\n') continue
            var start = lineStart
            var end = index
            while (start < end && isTrimWhitespace(value[start])) start++
            while (end > start && isTrimWhitespace(value[end - 1])) end--
            if (start < end) {
                if (isNotEmpty()) append('\n')
                var previousWhitespace = false
                for (position in start until end) {
                    val character = value[position]
                    val space = isWhitespace(character)
                    if (!space || !previousWhitespace) append(if (space) ' ' else character)
                    previousWhitespace = space
                }
            }
            lineStart = index + 1
        }
    }

    // Dart preserves literal CR, attribute whitespace and unresolved named
    // entities. Quote those values before the platform XML reader normalizes
    // or expands them, without changing markup or CDATA/comment contents.
    private fun preserveXmlWhitespace(xml: String): String {
        if (xml.none { it == '\r' || it == '\n' || it == '\t' || it == '&' }) return xml
        return buildString(xml.length) {
            var offset = 0
            var depth = 0
            fun appendEntity(start: Int): Int {
                val end = xml.indexOf(';', start + 1)
                if (end < 0) { append('&'); return start + 1 }
                val name = xml.substring(start + 1, end)
                if (name !in predefinedEntities && entityName.matches(name)) {
                    append("&amp;").append(name).append(';')
                    return end + 1
                }
                append('&')
                return start + 1
            }
            while (offset < xml.length) {
                if (xml.startsWith("<![CDATA[", offset)) {
                    val end = xml.indexOf("]]>", offset + 9)
                    if (end < 0) { append(xml, offset, xml.length); break }
                    append("<![CDATA[")
                    append(xml.substring(offset + 9, end).replace("\r", "]]>&#13;<![CDATA["))
                    append("]]>")
                    offset = end + 3
                } else if (xml.startsWith("<!--", offset) || xml.startsWith("<?", offset)) {
                    val terminator = if (xml.startsWith("<!--", offset)) "-->" else "?>"
                    val end = xml.indexOf(terminator, offset + 2)
                    val next = if (end < 0) xml.length else end + terminator.length
                    append(xml, offset, next)
                    offset = next
                } else if (xml[offset] == '<') {
                    val declaration = xml.startsWith("<!", offset)
                    val closing = xml.startsWith("</", offset)
                    var quote: Char? = null
                    var brackets = 0
                    var previous = '<'
                    append(xml[offset++])
                    while (offset < xml.length) {
                        val character = xml[offset++]
                        if (quote != null) {
                            when {
                                character == quote -> { append(character); quote = null }
                                !declaration && character == '\r' -> append("&#13;")
                                !declaration && character == '\n' -> append("&#10;")
                                !declaration && character == '\t' -> append("&#9;")
                                !declaration && character == '&' -> offset = appendEntity(offset - 1)
                                else -> append(character)
                            }
                        } else {
                            append(character)
                            if (character == '\'' || character == '"') quote = character
                            if (declaration && character == '[') brackets++
                            if (declaration && character == ']') brackets--
                            if (character == '>' && brackets == 0) {
                                if (!declaration) depth += if (closing) -1 else if (previous == '/') 0 else 1
                                break
                            }
                        }
                        previous = character
                    }
                } else {
                    val character = xml[offset++]
                    when {
                        depth > 0 && character == '\r' -> append("&#13;")
                        depth > 0 && character == '&' -> offset = appendEntity(offset - 1)
                        else -> append(character)
                    }
                }
            }
        }
    }

    /** Preserve Dart utf8.decode(allowMalformed: true) for invalid surrogate bytes. */
    private fun decodeUtf8(bytes: ByteArray): String {
        val decoded = bytes.toString(Charsets.UTF_8)
        if ('\uFFFD' !in decoded) return decoded
        return buildString {
            var offset = 0
            while (offset < bytes.size) {
                val first = bytes[offset].toInt() and 0xff
                if (first < 0x80) {
                    append(first.toChar())
                    offset++
                    continue
                }
                val length = when (first) {
                    in 0xc2..0xdf -> 2
                    in 0xe0..0xef -> 3
                    in 0xf0..0xf4 -> 4
                    else -> 0
                }
                var consumed = 1
                var codePoint = first and (0x7f shr length)
                if (length > 0) {
                    while (consumed < length && offset + consumed < bytes.size) {
                        val next = bytes[offset + consumed].toInt() and 0xff
                        val minimum = if (consumed == 1 && first == 0xe0) 0xa0
                            else if (consumed == 1 && first == 0xf0) 0x90 else 0x80
                        val maximum = if (consumed == 1 && first == 0xed) 0x9f
                            else if (consumed == 1 && first == 0xf4) 0x8f else 0xbf
                        if (next !in minimum..maximum) break
                        codePoint = (codePoint shl 6) or (next and 0x3f)
                        consumed++
                    }
                }
                if (length > 0 && consumed == length) appendCodePoint(codePoint)
                else append('\uFFFD')
                offset += consumed
            }
        }
    }
}
