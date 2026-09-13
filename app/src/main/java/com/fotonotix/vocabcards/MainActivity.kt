package com.fotonotix.vocabcards

import android.app.AlertDialog
import android.content.Intent
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.View
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.tabs.TabLayout
import com.fotonotix.vocabcards.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    companion object { private const val TAG = "VocardmemDB" }

    private lateinit var binding: ActivityMainBinding

    private val clipboardDb by lazy { ClipboardDatabase.get(this) }
    private val vocabDb     by lazy { VocabDatabase.get(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupTabs()
        setupClipboardTab()
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
                    binding.btnSaveWord.isEnabled = false
                } else {
                    val russian = ExcelWriter.isRussian(text)
                    binding.tvLangDetected.text = if (russian) "Russian" else "German"
                    binding.btnSaveWord.isEnabled = true
                }
                binding.tvSaveStatus.text = ""
            }
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
        })

        binding.btnSaveWord.setOnClickListener { saveClipboardWord() }
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

    // ──────────────────────────── STUDY TAB ────────────────────────────

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
                        try {
                            val beforeLearning = vocabDb.dao().getLearning().size
                            val beforeLearned  = vocabDb.dao().getLearned().size
                            val beforeArchived = vocabDb.dao().countArchived()
                            Log.d(TAG, "archiveAll: START  learning=$beforeLearning  learned=$beforeLearned  archived=$beforeArchived")

                            vocabDb.dao().archiveAllLearned()

                            val afterLearning = vocabDb.dao().getLearning().size
                            val afterLearned  = vocabDb.dao().getLearned().size
                            val afterArchived = vocabDb.dao().countArchived()
                            Log.d(TAG, "archiveAll: DONE   learning=$afterLearning  learned=$afterLearned  archived=$afterArchived")

                            withContext(Dispatchers.Main) {
                                Toast.makeText(this@MainActivity, "$beforeLearned word(s) archived", Toast.LENGTH_SHORT).show()
                                refreshStudyTab()
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "archiveAll: FAILED  ${e.javaClass.simpleName}: ${e.message}", e)
                            withContext(Dispatchers.Main) {
                                Toast.makeText(this@MainActivity, "Archive failed: ${e.message}", Toast.LENGTH_LONG).show()
                            }
                        }
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

}
