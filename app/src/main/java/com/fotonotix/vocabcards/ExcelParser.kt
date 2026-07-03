package com.fotonotix.vocabcards

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.zip.ZipInputStream

object ExcelParser {

    private val SECTION_WORDS = setOf(
        "neu", "alt", "neu+alt", "extra", "bonus", "review"
    )
    private val SUBSECTION_WORDS = setOf(
        "substantive", "verben", "adjektive", "adverbien", "phrasen",
        "redewendungen", "sonstiges", "andere"
    )

    fun parse(inputStream: InputStream): List<VocabCard> {
        val entries = readZipEntries(inputStream)

        val sharedStrings = entries["xl/sharedStrings.xml"]
            ?.let { parseSharedStrings(it) } ?: emptyList()

        val focusPath = findSheetPath(entries, "focus")
            ?: findSheetPath(entries, "fokus")
            ?: return emptyList()
        val sheetData = entries[focusPath] ?: return emptyList()

        return parseSheet(sheetData, sharedStrings)
    }

    private fun readZipEntries(inputStream: InputStream): Map<String, ByteArray> {
        val result = mutableMapOf<String, ByteArray>()
        val zip = ZipInputStream(inputStream)
        var entry = zip.nextEntry
        while (entry != null) {
            if (!entry.isDirectory) {
                result[entry.name] = zip.readBytes()
            }
            zip.closeEntry()
            entry = zip.nextEntry
        }
        zip.close()
        return result
    }

    private fun parseSharedStrings(data: ByteArray): List<String> {
        val strings = mutableListOf<String>()
        val parser = Xml.newPullParser()
        parser.setInput(ByteArrayInputStream(data), "UTF-8")

        val current = StringBuilder()
        var inT = false

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "si" -> current.clear()
                    "t"  -> inT = true
                }
                XmlPullParser.TEXT -> if (inT) current.append(parser.text)
                XmlPullParser.END_TAG -> when (parser.name) {
                    "si" -> strings.add(current.toString().trim())
                    "t"  -> inT = false
                }
            }
            event = parser.next()
        }
        return strings
    }

    /** Finds the xl/worksheets/sheetN.xml path for the sheet whose name matches [targetName]. */
    private fun findSheetPath(entries: Map<String, ByteArray>, targetName: String): String? {
        val workbookData = entries["xl/workbook.xml"] ?: return null
        val relsData    = entries["xl/_rels/workbook.xml.rels"] ?: return null

        // 1. Find rId for the sheet named "Focus"
        var focusRId: String? = null
        val wb = Xml.newPullParser()
        wb.setInput(ByteArrayInputStream(workbookData), "UTF-8")
        var ev = wb.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            if (ev == XmlPullParser.START_TAG && wb.name == "sheet") {
                val name = wb.getAttributeValue(null, "name") ?: ""
                if (name.trim().lowercase() == targetName) {
                    focusRId = wb.getAttributeValue(null, "r:Id")
                        ?: wb.getAttributeValue(
                            "http://schemas.openxmlformats.org/officeDocument/2006/relationships",
                            "id"
                        )
                }
            }
            ev = wb.next()
        }
        if (focusRId == null) return null

        // 2. Resolve rId -> relative target in rels file
        val rel = Xml.newPullParser()
        rel.setInput(ByteArrayInputStream(relsData), "UTF-8")
        ev = rel.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            if (ev == XmlPullParser.START_TAG && rel.name == "Relationship") {
                if (rel.getAttributeValue(null, "Id") == focusRId) {
                    val target = rel.getAttributeValue(null, "Target") ?: continue
                    return if (target.startsWith("/")) target.removePrefix("/")
                    else "xl/$target"
                }
            }
            ev = rel.next()
        }
        return null
    }

    private fun parseSheet(data: ByteArray, sharedStrings: List<String>): List<VocabCard> {
        val parser = Xml.newPullParser()
        parser.setInput(ByteArrayInputStream(data), "UTF-8")

        // Collect rows: Map<rowIndex, Map<colIndex, cellValue>>
        val rows = mutableMapOf<Int, MutableMap<Int, String>>()
        var currentRow = -1
        var currentCol = -1
        var currentType = ""
        var inV = false
        val cellBuffer = StringBuilder()

        var ev = parser.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            when (ev) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "row" -> {
                        val r = parser.getAttributeValue(null, "r")?.toIntOrNull() ?: 0
                        currentRow = r
                        rows.getOrPut(r) { mutableMapOf() }
                    }
                    "c" -> {
                        val ref = parser.getAttributeValue(null, "r") ?: ""
                        currentCol = colIndexFromRef(ref)
                        currentType = parser.getAttributeValue(null, "t") ?: ""
                        inV = false
                        cellBuffer.clear()
                    }
                    "v", "t" -> { inV = true; cellBuffer.clear() }
                }
                XmlPullParser.TEXT -> if (inV) cellBuffer.append(parser.text)
                XmlPullParser.END_TAG -> when (parser.name) {
                    "v", "t" -> {
                        if (currentRow > 0 && currentCol >= 0) {
                            val raw = cellBuffer.toString().trim()
                            val value = if (currentType == "s") {
                                sharedStrings.getOrElse(raw.toIntOrNull() ?: -1) { raw }
                            } else raw
                            rows[currentRow]?.set(currentCol, value)
                        }
                        inV = false
                    }
                }
            }
            ev = parser.next()
        }

        return buildCards(rows)
    }

    /** Converts cell reference like "B3" -> 0-based column index (A=0, B=1, …) */
    private fun colIndexFromRef(ref: String): Int {
        var col = 0
        for (ch in ref) {
            if (ch.isLetter()) col = col * 26 + (ch.uppercaseChar() - 'A' + 1)
            else break
        }
        return col - 1  // 0-based
    }

    private fun buildCards(rows: Map<Int, Map<Int, String>>): List<VocabCard> {
        val cards = mutableListOf<VocabCard>()
        var section = ""
        var subsection = ""

        for (rowIdx in rows.keys.sorted()) {
            val row = rows[rowIdx] ?: continue
            val colA = row[0]?.trim() ?: ""   // gender
            val colB = row[1]?.trim() ?: ""   // German word
            val colC = row[2]?.trim() ?: ""   // Russian translation
            // Col D (index 3) onwards: all additional info combined
            val maxCol = row.keys.maxOrNull() ?: 2
            val extra = (3..maxCol)
                .mapNotNull { row[it]?.trim()?.ifBlank { null } }
                .joinToString("  |  ")

            // "Neu" / "Alt" can be a merged cell starting at col A — value stored in colA,
            // colB and beyond are empty. Or they might be in colB if not merged.
            val headerCandidate = when {
                colA.isNotBlank() && colB.isBlank() && colC.isBlank() && extra.isBlank() -> colA
                colB.isNotBlank() && colC.isBlank() && extra.isBlank() -> colB
                else -> null
            }

            if (headerCandidate != null) {
                val hLower = headerCandidate.trim().lowercase()
                when {
                    hLower in SECTION_WORDS -> {
                        section = headerCandidate.trim()
                        subsection = ""
                        continue
                    }
                    hLower in SUBSECTION_WORDS -> {
                        subsection = headerCandidate.trim()
                        continue
                    }
                }
            }

            // Skip rows with no German word
            if (colB.isBlank() && colA !in setOf("m", "f", "n", "pl")) continue

            // Data row — nouns have gender in col A; verbs/adjectives leave it empty
            cards.add(
                VocabCard(
                    gender     = if (colA.lowercase() in setOf("m", "f", "n", "pl")) colA else "",
                    word       = colB,
                    russian    = colC,
                    extra      = extra,
                    section    = section,
                    subsection = subsection
                )
            )
        }
        return cards
    }
}
