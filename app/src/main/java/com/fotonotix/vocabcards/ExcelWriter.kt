package com.fotonotix.vocabcards

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

object ExcelWriter {

    /** Returns true if text is predominantly Cyrillic (Russian). */
    fun isRussian(text: String): Boolean {
        val cyrillic = text.count { it in 'Ѐ'..'ӿ' }
        val latin    = text.count { it.isLetter() && it !in 'Ѐ'..'ӿ' }
        return cyrillic > latin
    }

    /**
     * Appends [word] to the Zwischenablage sheet, inserting it just before
     * the "Alt" section header so it stays inside the Neu area.
     * Russian → column C, German → column B.
     */
    fun appendWord(fileBytes: ByteArray, word: String, russian: Boolean): ByteArray? {
        return try {
            val entries = readZip(fileBytes)
            val path    = findSheetPath(entries, "zwischenablage") ?: return null
            val xml     = entries[path]?.toString(Charsets.UTF_8) ?: return null
            val shared  = parseSharedStrings(entries["xl/sharedStrings.xml"] ?: byteArrayOf())

            val col     = if (russian) "C" else "B"
            val escaped = escapeXml(word.trim())

            val altRow = findSectionHeaderRow(xml, shared, "alt")

            val modified = if (altRow != null) {
                // Shift all rows >= altRow down by 1 to make room
                val shifted = shiftRowsFrom(xml, altRow, by = 1)
                // The Alt row is now at altRow+1; insert word at altRow
                val newRow = buildRow(altRow, col, escaped)
                // Insert before the shifted Alt row tag
                shifted.replace(
                    "<row r=\"${altRow + 1}\"",
                    "$newRow<row r=\"${altRow + 1}\""
                )
            } else {
                // No Alt section — just append at the end
                val lastRow = Regex("""<row\s+r="(\d+)"""")
                    .findAll(xml)
                    .mapNotNull { it.groupValues[1].toIntOrNull() }
                    .maxOrNull() ?: 0
                val newRow = buildRow(lastRow + 1, col, escaped)
                when {
                    "</sheetData>" in xml  -> xml.replace("</sheetData>", "$newRow</sheetData>")
                    "<sheetData/>" in xml  -> xml.replace("<sheetData/>", "<sheetData>$newRow</sheetData>")
                    else                   -> return null
                }
            }

            val updated = entries.toMutableMap()
            updated[path] = modified.toByteArray(Charsets.UTF_8)
            writeZip(updated)
        } catch (_: Exception) { null }
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private fun buildRow(rowNum: Int, col: String, escapedValue: String) =
        """<row r="$rowNum"><c r="$col$rowNum" t="inlineStr"><is><t>$escapedValue</t></is></c></row>"""

    private fun escapeXml(s: String) = s
        .replace("&", "&amp;").replace("<", "&lt;")
        .replace(">", "&gt;").replace("\"", "&quot;")

    /**
     * Finds the row number of the first section header matching [target] (e.g. "alt").
     * A header row has exactly one cell whose value (shared or inline) equals target.
     */
    private fun findSectionHeaderRow(
        sheetXml: String,
        sharedStrings: List<String>,
        target: String
    ): Int? {
        val rowRx    = Regex("""<row\s+r="(\d+)"[^>]*>([\s\S]*?)</row>""")
        val cellRx   = Regex("""<c[\s\S]*?</c>""")
        val sharedRx = Regex("""<v>(\d+)</v>""")
        val inlineRx = Regex("""<t>([\s\S]*?)</t>""")

        for (rowM in rowRx.findAll(sheetXml)) {
            val rowNum = rowM.groupValues[1].toIntOrNull() ?: continue
            val body   = rowM.groupValues[2]
            val cells  = cellRx.findAll(body).toList()
            if (cells.size != 1) continue

            val cell = cells[0].value

            // Check shared string
            val sIdx = sharedRx.find(cell)?.groupValues?.get(1)?.toIntOrNull()
            if (sIdx != null && sIdx < sharedStrings.size &&
                sharedStrings[sIdx].trim().lowercase() == target) return rowNum

            // Check inline string
            val inline = inlineRx.find(cell)?.groupValues?.get(1)
            if (inline != null && inline.trim().lowercase() == target) return rowNum
        }
        return null
    }

    /**
     * Increments the row number of every row with r >= [fromRow] by [by],
     * including the cell references (e.g. C50 → C51) inside those rows.
     * Processes in descending order to avoid double-renaming.
     */
    private fun shiftRowsFrom(xml: String, fromRow: Int, by: Int): String {
        val nums = Regex("""<row\s+r="(\d+)"""")
            .findAll(xml)
            .mapNotNull { it.groupValues[1].toIntOrNull() }
            .filter { it >= fromRow }
            .sortedDescending()

        var result = xml
        for (n in nums) {
            // Rename <row r="n" and <row r="n>
            result = result
                .replace("<row r=\"$n\"", "<row r=\"${n + by}\"")
                .replace("<row r=\"$n\"", "<row r=\"${n + by}\"")
            // Rename cell refs: letters + n → letters + (n+by)
            result = result.replace(
                Regex("""(r=")([A-Z]+)$n(")""")
            ) { m -> "${m.groupValues[1]}${m.groupValues[2]}${n + by}${m.groupValues[3]}" }
        }
        return result
    }

    // ── ZIP helpers ───────────────────────────────────────────────────────

    private fun readZip(bytes: ByteArray): Map<String, ByteArray> {
        val map = mutableMapOf<String, ByteArray>()
        val zip = ZipInputStream(ByteArrayInputStream(bytes))
        var e = zip.nextEntry
        while (e != null) {
            if (!e.isDirectory) map[e.name] = zip.readBytes()
            zip.closeEntry(); e = zip.nextEntry
        }
        zip.close()
        return map
    }

    private fun writeZip(entries: Map<String, ByteArray>): ByteArray {
        val baos = ByteArrayOutputStream()
        val zos  = ZipOutputStream(baos)
        for ((name, data) in entries) {
            zos.putNextEntry(ZipEntry(name)); zos.write(data); zos.closeEntry()
        }
        zos.close()
        return baos.toByteArray()
    }

    private fun parseSharedStrings(data: ByteArray): List<String> {
        if (data.isEmpty()) return emptyList()
        val strings = mutableListOf<String>()
        val parser  = Xml.newPullParser()
        parser.setInput(ByteArrayInputStream(data), "UTF-8")
        val current = StringBuilder()
        var inT = false
        var ev  = parser.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            when (ev) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "si" -> current.clear()
                    "t"  -> { inT = true }
                }
                XmlPullParser.TEXT -> if (inT) current.append(parser.text)
                XmlPullParser.END_TAG -> when (parser.name) {
                    "si" -> strings.add(current.toString().trim())
                    "t"  -> inT = false
                }
            }
            ev = parser.next()
        }
        return strings
    }

    private fun findSheetPath(entries: Map<String, ByteArray>, target: String): String? {
        val wb   = entries["xl/workbook.xml"]            ?: return null
        val rels = entries["xl/_rels/workbook.xml.rels"] ?: return null
        var rId: String? = null
        val p = Xml.newPullParser(); p.setInput(ByteArrayInputStream(wb), "UTF-8")
        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            if (ev == XmlPullParser.START_TAG && p.name == "sheet" &&
                p.getAttributeValue(null, "name")?.trim()?.lowercase() == target) {
                rId = p.getAttributeValue(null, "r:Id")
                    ?: p.getAttributeValue(
                        "http://schemas.openxmlformats.org/officeDocument/2006/relationships", "id")
            }
            ev = p.next()
        }
        if (rId == null) return null
        val r = Xml.newPullParser(); r.setInput(ByteArrayInputStream(rels), "UTF-8")
        ev = r.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            if (ev == XmlPullParser.START_TAG && r.name == "Relationship"
                && r.getAttributeValue(null, "Id") == rId) {
                val t = r.getAttributeValue(null, "Target") ?: continue
                return if (t.startsWith("/")) t.removePrefix("/") else "xl/$t"
            }
            ev = r.next()
        }
        return null
    }
}
