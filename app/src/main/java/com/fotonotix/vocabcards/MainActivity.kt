package com.fotonotix.vocabcards

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
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
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
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

        binding.btnPickFile.setOnClickListener {
            filePicker.launch(arrayOf(
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                "application/vnd.ms-excel",
                "*/*"
            ))
        }

        // Auto-load last file on every startup
        val lastUri = WrongCardStore.loadLastFileUri(this)
        if (lastUri != null) {
            val name = WrongCardStore.loadLastFileName(this)
            binding.tvStatus.text = "Loading ${if (name.isNotBlank()) name else "last file"}…"
            loadFile(lastUri, onFailure = {
                // Saved URI expired — ask user to pick again
                binding.tvStatus.text = "Could not reopen last file. Please pick it again."
                WrongCardStore.clearLastFile(this)
            })
        }
    }

    override fun onResume() {
        super.onResume()
        if (loadedCards.isNotEmpty()) updateStartButtons()
    }

    private fun resolveFileName(uri: Uri): String {
        var name = ""
        try {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val col = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (cursor.moveToFirst() && col >= 0) name = cursor.getString(col)
            }
        } catch (_: Exception) {}
        return name
    }

    private fun loadFile(uri: Uri, onFailure: (() -> Unit)? = null) {
        binding.progressBar.visibility = View.VISIBLE
        binding.btnPickFile.isEnabled = false

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val stream = contentResolver.openInputStream(uri)
                    ?: throw IllegalStateException("Cannot open file")
                val cards = ExcelParser.parse(stream)
                stream.close()

                withContext(Dispatchers.Main) {
                    binding.progressBar.visibility = View.GONE
                    binding.btnPickFile.isEnabled = true

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
                    binding.btnPickFile.isEnabled = true
                    if (onFailure != null) {
                        onFailure()
                    } else {
                        binding.tvStatus.text = "Error: ${e.message}"
                        Toast.makeText(this@MainActivity, "Failed to parse file", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    private fun updateStartButtons() {
        val wrongIndices = WrongCardStore.load(this)
        val wrongCount = wrongIndices.size

        binding.btnStartAll.visibility = View.VISIBLE
        binding.btnStartAll.text = "Start all  (${loadedCards.size})"
        binding.btnStartAll.setOnClickListener { openCards(loadedCards.indices.toList()) }

        if (wrongCount > 0) {
            binding.btnStartWrong.visibility = View.VISIBLE
            binding.btnStartWrong.text = "Review wrong  ($wrongCount)"
            binding.btnStartWrong.setOnClickListener {
                val validIndices = wrongIndices.filter { it < loadedCards.size }
                if (validIndices.isEmpty()) {
                    binding.btnStartWrong.visibility = View.GONE
                    binding.btnClearWrong.visibility = View.GONE
                    return@setOnClickListener
                }
                openCards(validIndices, wrongOnly = true)
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
        val cards = indices.map { loadedCards[it] }
        val intent = Intent(this, CardActivity::class.java)
        intent.putExtra(CardActivity.EXTRA_CARDS, ArrayList(cards))
        intent.putExtra(CardActivity.EXTRA_WRONG_ONLY, wrongOnly)
        intent.putExtra(CardActivity.EXTRA_WRONG_INDICES, indices.toIntArray())
        startActivity(intent)
    }
}
