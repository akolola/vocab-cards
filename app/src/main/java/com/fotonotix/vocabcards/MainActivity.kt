package com.fotonotix.vocabcards

import android.app.AlertDialog
import android.content.ContentUris
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.tabs.TabLayout
import com.fotonotix.vocabcards.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val clipboardDb by lazy { ClipboardDatabase.get(this) }
    private val vocabDb     by lazy { VocabDatabase.get(this) }

    private val filePicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (_: Exception) {}
            importFromExcel(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupTabs()
        setupClipboardTab()
        setupStudyTab()
    }

    override fun onResume() {
        super.onResume()
        refreshStudyTab()
    }

    // ──────────────────────────── TABS ────────────────────────────

    private fun setupTabs() {
        binding.tabLayout.addTab(binding.tabLayout.newTab().setText("Clipboard"))
        binding.tabLayout.addTab(binding.tabLayout.newTab().setText("Study"))

        binding.tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                binding.tabAddWord.visibility = if (tab.position == 0) View.VISIBLE else View.GONE
                binding.tabStudy.visibility   = if (tab.position == 1) View.VISIBLE else View.GONE
            }
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })

        binding.tabAddWord.visibility = View.VISIBLE
        binding.tabStudy.visibility   = View.GONE
    }

    // ──────────────────────────── CLIPBOARD TAB ────────────────────────────

    private fun setupClipboardTab() {
        lifecycleScope.launch {
            clipboardDb.dao().countFlow().collectLatest { count ->
                binding.tvWordCount.text = "$count word${if (count == 1) "" else "s"} saved"
            }
        }

        binding.etWord.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                val text = s?.toString()?.trim() ?: ""
                if (text.isEmpty()) {
                    binding.tvLangDetected.text = "—"
                    binding.tvColTarget.text    = ""
                    binding.btnSaveWord.isEnabled = false
                } else {
                    val russian = ExcelWriter.isRussian(text)
                    binding.tvLangDetected.text = if (russian) "Russian" else "German"
                    binding.tvColTarget.text    = if (russian) "→ col C" else "→ col B"
                    binding.btnSaveWord.isEnabled = true
                }
                binding.tvSaveStatus.text = ""
            }
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
        })

        binding.btnSaveWord.setOnClickListener { saveClipboardWord() }
        binding.btnExport.setOnClickListener { exportClipboardToDownloads() }
        binding.btnClearClipboard.setOnClickListener { confirmClearClipboard() }
    }

    private fun saveClipboardWord() {
        val word = binding.etWord.text?.toString()?.trim() ?: return
        if (word.isEmpty()) return
        val russian = ExcelWriter.isRussian(word)
        binding.btnSaveWord.isEnabled = false
        lifecycleScope.launch(Dispatchers.IO) {
            clipboardDb.dao().insert(ClipboardWord(text = word, isRussian = russian))
            withContext(Dispatchers.Main) {
                binding.tvSaveStatus.text = "Saved: \"$word\""
                binding.etWord.text?.clear()
            }
        }
    }

    private fun confirmClearClipboard() {
        AlertDialog.Builder(this)
            .setTitle("Clear all words?")
            .setMessage("This will permanently delete all saved words from the clipboard. This cannot be undone.")
            .setPositiveButton("Delete all") { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) {
                    clipboardDb.dao().clearAll()
                    withContext(Dispatchers.Main) {
                        binding.tvSaveStatus.text = "Clipboard cleared."
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
            .also { dialog ->
                dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                    ?.setTextColor(android.graphics.Color.parseColor("#CC0000"))
            }
    }

    private fun exportClipboardToDownloads() {
        binding.btnExport.isEnabled = false
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val words = clipboardDb.dao().getAll()
                if (words.isEmpty()) {
                    status("Nothing to export — save some words first.")
                    return@launch
                }
                status("Building Clipboard.xlsx (${words.size} words)…")
                val xlsx = buildClipboardXlsx(words)
                writeToDownloads(xlsx, "Clipboard.xlsx")
                status("Exported ${words.size} words to Downloads/Clipboard.xlsx")
            } catch (e: Exception) {
                status("Export error (${e.javaClass.simpleName}): ${e.message}")
            } finally {
                withContext(Dispatchers.Main) { binding.btnExport.isEnabled = true }
            }
        }
    }

    private fun buildClipboardXlsx(words: List<ClipboardWord>): ByteArray {
        val rows = StringBuilder()
        words.forEachIndexed { i, w ->
            val row = i + 1
            val col = if (w.isRussian) "C" else "B"
            val esc = w.text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            rows.append("""<row r="$row"><c r="$col$row" t="inlineStr"><is><t>$esc</t></is></c></row>""")
        }
        val entries = linkedMapOf(
            "[Content_Types].xml" to """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/></Types>""",
            "_rels/.rels" to """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>""",
            "xl/workbook.xml" to """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets><sheet name="Clipboard" sheetId="1" r:id="rId1"/></sheets></workbook>""",
            "xl/_rels/workbook.xml.rels" to """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/></Relationships>""",
            "xl/worksheets/sheet1.xml" to """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>$rows</sheetData></worksheet>"""
        )
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zos ->
            for ((name, xml) in entries) {
                zos.putNextEntry(ZipEntry(name))
                zos.write(xml.toByteArray(Charsets.UTF_8))
                zos.closeEntry()
            }
        }
        return baos.toByteArray()
    }

    private fun writeToDownloads(data: ByteArray, fileName: String) {
        val mimeType = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = contentResolver
            var existingId: Long? = null
            resolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.MediaColumns._ID),
                "${MediaStore.MediaColumns.DISPLAY_NAME} = ?",
                arrayOf(fileName), null
            )?.use { c -> if (c.moveToFirst()) existingId = c.getLong(0) }

            val uri = if (existingId != null) {
                ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, existingId!!)
            } else {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
                resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: throw IOException("MediaStore.insert returned null")
            }
            resolver.openOutputStream(uri, "wt")?.use { it.write(data) }
                ?: throw IOException("openOutputStream returned null")
        } else {
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            java.io.File(dir, fileName).writeBytes(data)
        }
    }

    private suspend fun status(msg: String) = withContext(Dispatchers.Main) {
        binding.tvSaveStatus.text = msg
    }

    // ──────────────────────────── STUDY TAB ────────────────────────────

    private fun setupStudyTab() {
        binding.btnPickFile.setOnClickListener {
            filePicker.launch(arrayOf(
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                "application/vnd.ms-excel",
                "*/*"
            ))
        }
    }

    private fun refreshStudyTab() {
        lifecycleScope.launch(Dispatchers.IO) {
            val learning  = vocabDb.dao().getLearning().map { it.toCard() }
            val learned   = vocabDb.dao().getLearned().map { it.toCard() }
            val wrong     = vocabDb.dao().getWrong().map { it.toCard() }
            val archived  = vocabDb.dao().countArchived()
            withContext(Dispatchers.Main) {
                updateStartButtons(learning, learned, wrong, archived)
            }
        }
    }

    private fun updateStartButtons(
        learning: List<VocabCard>,
        learned: List<VocabCard>,
        wrong: List<VocabCard>,
        archived: Int
    ) {
        val hasActive = learning.isNotEmpty() || learned.isNotEmpty()

        if (!hasActive) {
            val archivedNote = if (archived > 0) "$archived words mastered total" else "No active deck — ask Claude to check clipboard"
            binding.tvStatus.text = archivedNote
            binding.btnStartAll.visibility      = View.GONE
            binding.btnStartComplete.visibility = View.GONE
            binding.btnStartWrong.visibility    = View.GONE
            binding.btnClearWrong.visibility    = View.GONE
            binding.btnClearLearned.visibility  = View.GONE
            binding.btnArchiveAll.visibility    = View.GONE
            return
        }

        // Status
        val total = learning.size + learned.size
        val archivedSuffix = if (archived > 0) "  ·  $archived mastered" else ""
        binding.tvStatus.text = "$total cards in deck$archivedSuffix"

        // Start learning (sublist: not yet learned)
        binding.btnStartAll.visibility = View.VISIBLE
        val learningLabel = if (learned.isEmpty()) "Start all" else "Start learning"
        binding.btnStartAll.text = "$learningLabel  (${learning.size})"
        binding.btnStartAll.isEnabled = learning.isNotEmpty()
        binding.btnStartAll.alpha = if (learning.isEmpty()) 0.4f else 1.0f
        binding.btnStartAll.setOnClickListener {
            if (learning.isEmpty()) return@setOnClickListener
            openCards(learning)
        }

        // Move all to database — always visible, enabled only when sublist is empty
        binding.btnArchiveAll.visibility = View.VISIBLE
        val allMastered = learning.isEmpty() && learned.isNotEmpty()
        binding.btnArchiveAll.isEnabled = allMastered
        binding.btnArchiveAll.alpha = if (allMastered) 1.0f else 0.35f
        binding.btnArchiveAll.text = "Move all to database  (${learned.size})"
        binding.btnArchiveAll.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Move all to database?")
                .setMessage("${learned.size} mastered word(s) will be archived. The deck clears and is ready for a new batch.")
                .setPositiveButton("Move") { _, _ ->
                    lifecycleScope.launch(Dispatchers.IO) {
                        vocabDb.dao().archiveAllLearned()
                        withContext(Dispatchers.Main) { refreshStudyTab() }
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        // Review mastered
        if (learned.isNotEmpty()) {
            binding.btnStartComplete.visibility = View.VISIBLE
            binding.btnStartComplete.text = "Review mastered  (${learned.size})"
            binding.btnStartComplete.setOnClickListener { openCards(learned, learnedMode = true) }
            binding.btnClearLearned.visibility = View.VISIBLE
            binding.btnClearLearned.setOnClickListener {
                lifecycleScope.launch(Dispatchers.IO) {
                    vocabDb.dao().clearAllLearned()
                    withContext(Dispatchers.Main) { refreshStudyTab() }
                }
            }
        } else {
            binding.btnStartComplete.visibility = View.GONE
            binding.btnClearLearned.visibility  = View.GONE
        }

        // Wrong cards
        if (wrong.isNotEmpty()) {
            binding.btnStartWrong.visibility = View.VISIBLE
            binding.btnStartWrong.text = "Review wrong  (${wrong.size})"
            binding.btnStartWrong.setOnClickListener { openCards(wrong, wrongOnly = true) }
            binding.btnClearWrong.visibility = View.VISIBLE
            binding.btnClearWrong.setOnClickListener {
                lifecycleScope.launch(Dispatchers.IO) {
                    vocabDb.dao().clearAllWrong()
                    withContext(Dispatchers.Main) { refreshStudyTab() }
                }
            }
        } else {
            binding.btnStartWrong.visibility = View.GONE
            binding.btnClearWrong.visibility = View.GONE
        }
    }

    private fun openCards(
        cards: List<VocabCard>,
        wrongOnly: Boolean = false,
        learnedMode: Boolean = false
    ) {
        val intent = Intent(this, CardActivity::class.java)
        intent.putExtra(CardActivity.EXTRA_CARDS,        ArrayList(cards))
        intent.putExtra(CardActivity.EXTRA_WRONG_ONLY,   wrongOnly)
        intent.putExtra(CardActivity.EXTRA_LEARNED_MODE, learnedMode)
        startActivity(intent)
    }

    // ──────────────────────────── EXCEL IMPORT ────────────────────────────

    private fun importFromExcel(uri: Uri) {
        binding.progressBar.visibility = View.VISIBLE
        binding.btnPickFile.isEnabled  = false
        binding.tvStatus.text = "Parsing Excel…"

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val stream = contentResolver.openInputStream(uri)
                    ?: throw IllegalStateException("Cannot open file")
                val cards = ExcelParser.parse(stream)
                stream.close()

                if (cards.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        binding.progressBar.visibility = View.GONE
                        binding.btnPickFile.isEnabled  = true
                        binding.tvStatus.text = "No cards found — check sheet is named Focus"
                    }
                    return@launch
                }

                // Smart merge: preserve learned/wrong for cards that already exist (match by word)
                val existing = vocabDb.dao().getAll().associateBy { it.word }
                val entities = cards.map { card ->
                    val prev = existing[card.word]
                    card.toEntity().copy(
                        learned     = prev?.learned     ?: false,
                        markedWrong = prev?.markedWrong ?: false
                    )
                }
                vocabDb.dao().clearAll()
                vocabDb.dao().insertAll(entities)

                val learning  = vocabDb.dao().getLearning().map { it.toCard() }
                val learned   = vocabDb.dao().getLearned().map { it.toCard() }
                val wrong     = vocabDb.dao().getWrong().map { it.toCard() }
                val archived  = vocabDb.dao().countArchived()

                withContext(Dispatchers.Main) {
                    binding.progressBar.visibility = View.GONE
                    binding.btnPickFile.isEnabled  = true
                    val name = resolveFileName(uri)
                    val label = if (name.isNotBlank()) "$name  ·  " else ""
                    binding.tvStatus.text = "${label}${cards.size} cards imported"
                    updateStartButtons(learning, learned, wrong, archived)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    binding.progressBar.visibility = View.GONE
                    binding.btnPickFile.isEnabled  = true
                    binding.tvStatus.text = "Import error: ${e.message}"
                    Toast.makeText(this@MainActivity, "Failed to parse file", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun resolveFileName(uri: Uri): String {
        var name = ""
        try {
            contentResolver.query(uri, null, null, null, null)?.use { c ->
                val col = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (c.moveToFirst() && col >= 0) name = c.getString(col)
            }
        } catch (_: Exception) {}
        return name
    }
}
