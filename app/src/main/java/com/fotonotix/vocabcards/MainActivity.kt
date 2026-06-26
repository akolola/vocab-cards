package com.fotonotix.vocabcards

import android.content.Intent
import android.net.Uri
import android.os.Bundle
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

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var loadedCards: List<VocabCard> = emptyList()

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
            val name = resolveFileName(uri)
            WrongCardStore.saveLastFile(this, uri, name)
            loadFile(uri)
        }
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

    private fun saveWord() {
        val word = binding.etWord.text?.toString()?.trim() ?: return
        if (word.isEmpty()) return
        val uri = WrongCardStore.loadLastFileUri(this) ?: run {
            binding.tvSaveStatus.text = "No file loaded — open a file in the Study tab first."
            return
        }
        val russian = ExcelWriter.isRussian(word)

        binding.btnSaveWord.isEnabled = false
        binding.tvSaveStatus.text = "Saving…"

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val bytes    = contentResolver.openInputStream(uri)!!.readBytes()
                val modified = ExcelWriter.appendWord(bytes, word, russian)
                    ?: throw IllegalStateException("Sheet 'Zwischenablage' not found in file")

                contentResolver.openOutputStream(uri, "wt")!!.use { it.write(modified) }

                withContext(Dispatchers.Main) {
                    val col = if (russian) "column C (Russian)" else "column B (German)"
                    binding.tvSaveStatus.text = "Saved \"$word\" to $col"
                    binding.etWord.text?.clear()
                    binding.btnSaveWord.isEnabled = false
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    binding.tvSaveStatus.text = "Error: ${e.message}"
                    binding.btnSaveWord.isEnabled = true
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
