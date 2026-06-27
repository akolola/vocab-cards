package com.fotonotix.vocabcards

import android.content.ContentUris
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.ParcelFileDescriptor
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var loadedCards: List<VocabCard> = emptyList()

    // File picker for the Study tab (read + write)
    private val filePicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            grantAndSave(uri)
            loadFile(uri)
        }
    }

    // File picker for the Add Word tab — only needs write access granted
    private val filePickerForAdd = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            grantAndSave(uri)
            val name = WrongCardStore.loadLastFileName(this)
            binding.tvSelectedFileName.text = name.ifBlank { uri.lastPathSegment ?: "file" }
            binding.btnSaveWord.isEnabled =
                binding.etWord.text?.toString()?.trim()?.isNotEmpty() == true
            binding.tvSaveStatus.text = "File ready — you can now save words."
        }
    }

    private fun grantAndSave(uri: Uri) {
        try {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (_: Exception) {}
        val name = resolveFileName(uri)
        WrongCardStore.saveLastFile(this, uri, name)
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
            binding.tvStatus.text =
                "Loading ${if (name.isNotBlank()) name else "last file"}…"
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
        binding.tabLayout.addTab(binding.tabLayout.newTab().setText("Add Word"))
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
        // Show saved file name if already available
        val savedName = WrongCardStore.loadLastFileName(this)
        binding.tvSelectedFileName.text =
            if (savedName.isNotBlank()) savedName else "No file selected"

        binding.btnSelectFileForAdd.setOnClickListener {
            filePickerForAdd.launch(arrayOf(
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                "application/vnd.ms-excel",
                "*/*"
            ))
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
                    binding.tvColTarget.text    = if (russian) "→ column C" else "→ column B"
                    binding.btnSaveWord.isEnabled =
                        WrongCardStore.loadLastFileUri(this@MainActivity) != null
                }
                binding.tvSaveStatus.text = ""
            }
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
        })

        binding.btnSaveWord.setOnClickListener { saveWord() }
    }

    // ── TEST MODE: write a fresh minimal xlsx directly to Downloads/Wortschatz.xlsx ──
    // (ExcelWriter / SAF logic temporarily disabled to prove write path works)
    private fun saveWord() {
        val word = binding.etWord.text?.toString()?.trim() ?: return
        if (word.isEmpty()) return
        binding.btnSaveWord.isEnabled = false

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                status("Building xlsx…")
                val xlsx = buildMinimalXlsx(word)

                status("Writing ${xlsx.size} bytes to Downloads/VocabCards_test.xlsx…")
                writeToDownloads(xlsx, "VocabCards_test.xlsx")

                status("Done! Open Downloads on your phone and check VocabCards_test.xlsx — cell A1 should contain: \"$word\"")
                withContext(Dispatchers.Main) { binding.etWord.text?.clear() }
            } catch (e: Exception) {
                status("Error (${e.javaClass.simpleName}): ${e.message}")
            } finally {
                withContext(Dispatchers.Main) { binding.btnSaveWord.isEnabled = true }
            }
        }
    }

    private fun buildMinimalXlsx(word: String): ByteArray {
        val escaped = word.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        val entries = linkedMapOf(
            "[Content_Types].xml" to """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/></Types>""",
            "_rels/.rels" to """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>""",
            "xl/workbook.xml" to """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets><sheet name="Sheet1" sheetId="1" r:id="rId1"/></sheets></workbook>""",
            "xl/_rels/workbook.xml.rels" to """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/></Relationships>""",
            "xl/worksheets/sheet1.xml" to """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData><row r="1"><c r="A1" t="inlineStr"><is><t>$escaped</t></is></c></row></sheetData></worksheet>"""
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val mimeType = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            val resolver = contentResolver

            // Find existing entry our app owns (so we overwrite instead of creating duplicates)
            var existingId: Long? = null
            resolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.MediaColumns._ID),
                "${MediaStore.MediaColumns.DISPLAY_NAME} = ?",
                arrayOf(fileName), null
            )?.use { c ->
                if (c.moveToFirst()) existingId = c.getLong(0)
            }

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
                ?: throw IOException("openOutputStream returned null for $uri")
        } else {
            // Android 9 and below — direct file I/O (WRITE_EXTERNAL_STORAGE permission covers this)
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

                    val wordTyped = binding.etWord.text?.toString()?.trim() ?: ""
                    if (wordTyped.isNotEmpty()) binding.btnSaveWord.isEnabled = true

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
