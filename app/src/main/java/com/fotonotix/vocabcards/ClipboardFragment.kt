package com.fotonotix.vocabcards

import android.app.AlertDialog
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.fotonotix.vocabcards.databinding.FragmentClipboardBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ClipboardFragment : Fragment() {

    private var _binding: FragmentClipboardBinding? = null
    private val binding get() = _binding!!

    private val clipboardDb by lazy { ClipboardDatabase.get(requireContext()) }
    private val vocabDb     by lazy { VocabDatabase.get(requireContext()) }

    private val dupCheckHandler = Handler(Looper.getMainLooper())
    private var dupCheckRunnable: Runnable? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentClipboardBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        lifecycleScope.launch {
            clipboardDb.dao().countFlow().collectLatest { count ->
                binding.tvWordCount.text = "$count word${if (count == 1) "" else "s"} saved"
            }
        }

        binding.etWord.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                val text = s?.toString()?.trim() ?: ""
                dupCheckRunnable?.let { dupCheckHandler.removeCallbacks(it) }
                if (text.isEmpty()) {
                    binding.tvLangDetected.text = "—"
                    binding.btnSaveWord.isEnabled = false
                    binding.btnSaveWord.alpha = 1.0f
                    binding.tvSaveStatus.text = ""
                } else {
                    val russian = ExcelWriter.isRussian(text)
                    binding.tvLangDetected.text = if (russian) "Russian" else "German"
                    binding.btnSaveWord.isEnabled = true
                    binding.btnSaveWord.alpha = 1.0f
                    binding.tvSaveStatus.text = ""
                    val runnable = Runnable { checkDuplicate(text) }
                    dupCheckRunnable = runnable
                    dupCheckHandler.postDelayed(runnable, 500)
                }
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

    private fun checkDuplicate(word: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            val inClipboard = clipboardDb.dao().countByWord(word) > 0
            val inVocab     = vocabDb.dao().findByWord(word)
            val msg = when {
                inVocab == true  -> "Already mastered — not saving again"
                inVocab == false -> "In current study deck"
                inClipboard      -> "Already in clipboard"
                else             -> ""
            }
            val blocked = inVocab == true
            withContext(Dispatchers.Main) {
                if (binding.etWord.text?.toString()?.trim() == word) {
                    binding.tvSaveStatus.text = msg
                    if (blocked) {
                        binding.btnSaveWord.isEnabled = false
                        binding.btnSaveWord.alpha = 0.4f
                    }
                }
            }
        }
    }

    private fun confirmClearClipboard() {
        AlertDialog.Builder(requireContext())
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

    override fun onDestroyView() {
        super.onDestroyView()
        dupCheckRunnable?.let { dupCheckHandler.removeCallbacks(it) }
        _binding = null
    }
}
