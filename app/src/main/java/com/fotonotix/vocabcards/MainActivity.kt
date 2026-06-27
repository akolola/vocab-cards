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
    private var loadedCards: List<VocabCard> = emptyList()

    private val db by lazy { ClipboardDatabase.get(this) }

    private val filePicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            grantAndSave(uri)
            loadFile(uri)
        }
    }

    private fun grantAndSave(uri: Uri) {
        try {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (_: Exception) {}
        WrongCardStore.saveLastFile(this, uri, resolveFileName(uri))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupTabs()
        setupAddWordTab()

        binding.btnPickFile.setOnClickListener {
            filePicker.launch(arrayOf(
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                "application/vnd.ms-excel",
                "*/*"
            ))
        }

        val lastUri = WrongCardStore.loadLastFileUri(this)
        if (lastUri != null) {
            val name = WrongCardStore.loadLastFileName(this)
            binding.tvStatus.text = "Loading ${if (name.isNotBlank()) name else "last file"}…"
            loadFile(lastUri, onFailure = {
                binding.tvStatus.text = "Could not reopen last file. Please pick it again."
                WrongCardStore.clearLastFile(this)
            })
        }
    }

    override fun onResume() {
        super.onResume()
        if (loadedCards.isNotEmpty()) updateStartButtons()
    }

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

    private fun setupAddWordTab() {
        // Live word count from DB
        lifecycleScope.launch {
            db.dao().countFlow().collectLatest { count ->
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

        binding.btnSaveWord.setOnClickListener { saveWord() }
        binding.btnExport.setOnClickListener { exportToDownloads() }
        binding.btnClearClipboard.setOnClickListener { confirmClearAll() }
    }

    private fun confirmClearAll() {
        AlertDialog.Builder(this)
            .setTitle("Clear all words?")
            .setMessage("This will permanently delete all saved words from the clipboard. This cannot be undone.")
            .setPositiveButton("Delete all") { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) {
                    db.dao().clearAll()
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

    private fun saveWord() {
        val word = binding.etWord.text?.toString()?.trim() ?: return
        if (word.isEmpty()) return
        val russian = ExcelWriter.isRussian(word)
        binding.btnSaveWord.isEnabled = false
        lifecycleScope.launch(Dispatchers.IO) {
            db.dao().insert(ClipboardWord(text = word, isRussian = russian))
            withContext(Dispatchers.Main) {
                binding.tvSaveStatus.text = "Saved: \"$word\""
                binding.etWord.text?.clear()
                binding.btnSaveWord.isEnabled = false
            }
        }
    }

    private fun exportToDownloads() {
        binding.btnExport.isEnabled = false
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val words = db.dao().getAll()
                if (words.isEmpty()) {
                    status("Nothing to export — save some words first.")
                    return@launch
                }
                status("Building Clipboard.xlsx (${words.size} words)…")
                val xlsx = buildClipboardXlsx(words)
                status("Writing to Downloads…")
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
            val escaped = w.text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            rows.append("""<row r="$row"><c r="$col$row" t="inlineStr"><is><t>$escaped</t></is></c></row>""")
        }
        val entries = linkedMapOf(
            "[Content_Types].xml" to """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/></Types>""",
            "_rels/.rels" to """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>""",
            "xl/workbook.xml" to """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets><sheet name="Clipboard" sheetId="1" r:id="rId1"/></sheets></workbook>""",
            "xl/_rels/workbook.xml.rels" to """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/></Relationships>""",
            "xl/worksheets/sheet1.xml" to """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>$rows</sheetData></worksheet>"""
        )
        val baos = ByteArrayOutputStream()
        val zos = ZipOutputStream(baos)
        for ((name, xml) in entries) {
            zos.putNextEntry(ZipEntry(name))
            zos.write(xml.toByteArray(Charsets.UTF_8))
            zos.closeEntry()
        }
        zos.close()
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

    private fun loadFile(uri: Uri, onFailure: (() -> Unit)? = null) {
        binding.progressBar.visibility = View.VISIBLE
        binding.btnPickFile.isEnabled  = false

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val stream = contentResolver.openInputStream(uri)
                    ?: throw IllegalStateException("Cannot open file")
                val cards = ExcelParser.parse(stream)
                stream.close()

                withContext(Dispatchers.Main) {
                    binding.progressBar.visibility = View.GONE
                    binding.btnPickFile.isEnabled  = true

                    if (cards.isEmpty()) {
                        binding.tvStatus.text = "No cards found. Make sure the sheet is named Fokus."
                        return@withContext
                    }

                    loadedCards = cards
                    val name = WrongCardStore.loadLastFileName(this@MainActivity)
                    binding.tvStatus.text =
                        if (name.isNotBlank()) "$name  ·  ${cards.size} cards"
                        else "${cards.size} cards loaded"

                    updateStartButtons()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    binding.progressBar.visibility = View.GONE
                    binding.btnPickFile.isEnabled  = true
                    if (onFailure != null) onFailure()
                    else {
                        binding.tvStatus.text = "Error: ${e.message}"
                        Toast.makeText(this@MainActivity, "Failed to parse file", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    private fun updateStartButtons() {
        val wrongIndices = WrongCardStore.load(this)

        binding.btnStartAll.visibility = View.VISIBLE
        binding.btnStartAll.text = "Start all  (${loadedCards.size})"
        binding.btnStartAll.setOnClickListener { openCards(loadedCards.indices.toList()) }

        if (wrongIndices.isNotEmpty()) {
            binding.btnStartWrong.visibility = View.VISIBLE
            binding.btnStartWrong.text = "Review wrong  (${wrongIndices.size})"
            binding.btnStartWrong.setOnClickListener {
                val valid = wrongIndices.filter { it < loadedCards.size }
                if (valid.isEmpty()) {
                    binding.btnStartWrong.visibility = View.GONE
                    binding.btnClearWrong.visibility = View.GONE
                    return@setOnClickListener
                }
                openCards(valid, wrongOnly = true)
            }
            binding.btnClearWrong.visibility = View.VISIBLE
            binding.btnClearWrong.setOnClickListener {
                WrongCardStore.clear(this)
                binding.btnStartWrong.visibility = View.GONE
                binding.btnClearWrong.visibility = View.GONE
            }
        } else {
            binding.btnStartWrong.visibility = View.GONE
            binding.btnClearWrong.visibility = View.GONE
        }
    }

    private fun openCards(indices: List<Int>, wrongOnly: Boolean = false) {
        val intent = Intent(this, CardActivity::class.java)
        intent.putExtra(CardActivity.EXTRA_CARDS, ArrayList(indices.map { loadedCards[it] }))
        intent.putExtra(CardActivity.EXTRA_WRONG_ONLY, wrongOnly)
        intent.putExtra(CardActivity.EXTRA_WRONG_INDICES, indices.toIntArray())
        startActivity(intent)
    }
}
