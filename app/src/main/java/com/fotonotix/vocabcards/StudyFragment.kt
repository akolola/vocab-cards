package com.fotonotix.vocabcards

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.fotonotix.vocabcards.databinding.FragmentStudyBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class StudyFragment : Fragment() {

    companion object { private const val TAG = "VocardmemDB" }

    private var _binding: FragmentStudyBinding? = null
    private val binding get() = _binding!!

    private val vocabDb by lazy { VocabDatabase.get(requireContext()) }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentStudyBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        refreshStudyTab()
    }

    override fun onResume() {
        super.onResume()
        refreshStudyTab()
    }

    fun refreshStudyTab() {
        lifecycleScope.launch(Dispatchers.IO) {
            val learning = vocabDb.dao().getLearning().map { it.toCard() }
            val learned  = vocabDb.dao().getLearned().map { it.toCard() }
            val wrong    = vocabDb.dao().getWrong().map { it.toCard() }
            val archived = vocabDb.dao().countArchived()
            withContext(Dispatchers.Main) {
                if (_binding != null) updateStartButtons(learning, learned, wrong, archived)
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

        val total = learning.size + learned.size
        val archivedSuffix = if (archived > 0) "  ·  $archived mastered" else ""
        binding.tvStatus.text = "$total cards in deck$archivedSuffix"

        // Start learning
        binding.btnStartAll.visibility = View.VISIBLE
        val learningLabel = if (learned.isEmpty()) "Start all" else "Start learning"
        binding.btnStartAll.text = "$learningLabel  (${learning.size})"
        binding.btnStartAll.isEnabled = learning.isNotEmpty()
        binding.btnStartAll.alpha = if (learning.isEmpty()) 0.4f else 1.0f
        binding.btnStartAll.setOnClickListener {
            if (learning.isEmpty()) return@setOnClickListener
            openCards(learning)
        }

        // Move all to database
        binding.btnArchiveAll.visibility = View.VISIBLE
        val allMastered = learning.isEmpty() && learned.isNotEmpty()
        binding.btnArchiveAll.isEnabled = allMastered
        binding.btnArchiveAll.alpha = if (allMastered) 1.0f else 0.35f
        binding.btnArchiveAll.text = "Move all to database  (${learned.size})"
        binding.btnArchiveAll.setOnClickListener {
            AlertDialog.Builder(requireContext())
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
                                if (_binding != null) {
                                    Toast.makeText(requireContext(), "$beforeLearned word(s) archived", Toast.LENGTH_SHORT).show()
                                    refreshStudyTab()
                                }
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "archiveAll: FAILED  ${e.javaClass.simpleName}: ${e.message}", e)
                            withContext(Dispatchers.Main) {
                                if (_binding != null)
                                    Toast.makeText(requireContext(), "Archive failed: ${e.message}", Toast.LENGTH_LONG).show()
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

    private fun openCards(cards: List<VocabCard>, wrongOnly: Boolean = false, learnedMode: Boolean = false) {
        val intent = Intent(requireContext(), CardActivity::class.java)
        intent.putExtra(CardActivity.EXTRA_CARDS,        ArrayList(cards))
        intent.putExtra(CardActivity.EXTRA_WRONG_ONLY,   wrongOnly)
        intent.putExtra(CardActivity.EXTRA_LEARNED_MODE, learnedMode)
        startActivity(intent)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
