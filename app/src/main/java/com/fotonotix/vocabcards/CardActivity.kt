package com.fotonotix.vocabcards

import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.fotonotix.vocabcards.databinding.ActivityCardBinding

class CardActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_CARDS         = "extra_cards"
        const val EXTRA_WRONG_ONLY    = "extra_wrong_only"
        const val EXTRA_WRONG_INDICES = "extra_wrong_indices"
    }

    private lateinit var binding: ActivityCardBinding
    private lateinit var allCards: ArrayList<VocabCard>

    private val sessionOrder = mutableListOf<Int>()
    private var position = 0

    private val wrongIndices = mutableSetOf<Int>()  // session-local wrong tracking
    private var reviewingWrongs = false
    private var isRevealed = false

    // In wrong-only mode: maps session card index → global index in WrongCardStore
    private var globalWrongIndices = intArrayOf()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCardBinding.inflate(layoutInflater)
        setContentView(binding.root)

        @Suppress("UNCHECKED_CAST")
        allCards = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            intent.getSerializableExtra(EXTRA_CARDS, ArrayList::class.java) as ArrayList<VocabCard>
        else
            @Suppress("DEPRECATION")
            (intent.getSerializableExtra(EXTRA_CARDS) as? ArrayList<VocabCard>) ?: arrayListOf()

        if (allCards.isEmpty()) { finish(); return }

        reviewingWrongs = intent.getBooleanExtra(EXTRA_WRONG_ONLY, false)
        globalWrongIndices = intent.getIntArrayExtra(EXTRA_WRONG_INDICES) ?: intArrayOf()

        sessionOrder.addAll(allCards.indices)
        showCard()

        binding.btnNext.setOnClickListener {
            if (isRevealed) advance(+1) else revealWord()
        }
        binding.btnPrev.setOnClickListener { advance(-1) }

        binding.btnWrong.setOnClickListener {
            if (reviewingWrongs) {
                unmarkCurrentCard()
            } else {
                markCurrentCardWrong()
            }
        }

        binding.btnReviewWrong.setOnClickListener {
            if (wrongIndices.isEmpty()) return@setOnClickListener
            reviewingWrongs = true
            sessionOrder.clear()
            sessionOrder.addAll(wrongIndices.sorted())
            position = 0
            wrongIndices.clear()
            allCards.forEach { it.markedWrong = false }
            binding.btnReviewWrong.visibility = View.GONE
            showCard()
        }
    }

    private fun markCurrentCardWrong() {
        val cardIdx = sessionOrder[position]
        allCards[cardIdx].markedWrong = true
        wrongIndices.add(cardIdx)
        val persisted = WrongCardStore.load(this).toMutableSet()
        persisted.addAll(wrongIndices)
        WrongCardStore.save(this, persisted)
        updateActionButton(cardIdx)
        advance(+1)
    }

    private fun unmarkCurrentCard() {
        val sessionIdx = sessionOrder[position]
        // Resolve to global index (the one stored in WrongCardStore)
        val globalIdx = if (sessionIdx < globalWrongIndices.size)
            globalWrongIndices[sessionIdx] else sessionIdx

        val persisted = WrongCardStore.load(this).toMutableSet()
        persisted.remove(globalIdx)
        WrongCardStore.save(this, persisted)

        allCards[sessionIdx].markedWrong = false
        updateActionButton(sessionIdx)
        // Visually dim the card so user knows it's removed, then advance
        binding.btnWrong.text = "Removed"
        binding.btnWrong.isEnabled = false
        binding.btnWrong.alpha = 0.4f
    }

    private fun advance(dir: Int) {
        val next = position + dir
        when {
            next < 0                  -> return
            next >= sessionOrder.size -> showFinished()
            else                      -> { position = next; showCard() }
        }
    }

    private fun showCard() {
        val cardIdx = sessionOrder[position]
        val card = allCards[cardIdx]

        // Section badge
        val sectionColor = when (card.section.lowercase()) {
            "neu" -> R.color.badge_neu
            "alt" -> R.color.badge_alt
            else  -> R.color.badge_pl
        }
        if (card.section.isNotBlank()) {
            binding.tvSectionBadge.visibility = View.VISIBLE
            binding.tvSectionBadge.text = card.section.uppercase()
            binding.tvSectionBadge.backgroundTintList =
                ContextCompat.getColorStateList(this, sectionColor)
        } else {
            binding.tvSectionBadge.visibility = View.GONE
        }

        // Subsection label
        binding.tvSubsection.text = card.subsection
        binding.tvSubsection.visibility =
            if (card.subsection.isBlank()) View.GONE else View.VISIBLE

        // Word hidden until revealed
        isRevealed = false
        binding.tvWord.text = card.word
        binding.tvWord.visibility = View.INVISIBLE

        if (card.gender.isNotBlank()) {
            binding.tvGender.text = card.gender.uppercase()
            val badgeColor = when (card.gender.lowercase()) {
                "m"  -> R.color.badge_m
                "f"  -> R.color.badge_f
                "n"  -> R.color.badge_n
                else -> R.color.badge_pl
            }
            binding.tvGender.backgroundTintList =
                ContextCompat.getColorStateList(this, badgeColor)
            binding.tvGender.visibility = View.INVISIBLE
        } else {
            binding.tvGender.text = ""   // clear stale text so reveal check doesn't show old value
            binding.tvGender.visibility = View.GONE
        }

        binding.tvArticle.text = card.article
        binding.tvArticle.visibility =
            if (card.article.isEmpty()) View.GONE else View.INVISIBLE

        // Translations always visible
        showRow(binding.rowRussian, binding.tvRussian, card.russian)
        showRow(binding.rowExtra,   binding.tvExtra,   card.extra)

        // Buttons
        binding.btnNext.text = "Show"
        binding.btnWrong.isEnabled = false
        binding.btnWrong.alpha = 0.3f

        // Progress
        binding.tvProgress.text = "${position + 1} / ${sessionOrder.size}"
        binding.tvProgressLabel.visibility =
            if (reviewingWrongs) View.VISIBLE else View.GONE

        binding.btnReviewWrong.visibility =
            if (wrongIndices.isNotEmpty() && position == sessionOrder.size - 1)
                View.VISIBLE else View.GONE

        binding.finishedLayout.visibility = View.GONE
        binding.cardLayout.visibility = View.VISIBLE
    }

    private fun revealWord() {
        isRevealed = true
        binding.tvWord.visibility = View.VISIBLE
        binding.tvArticle.visibility =
            if (binding.tvArticle.text.isNotEmpty()) View.VISIBLE else View.GONE
        if (binding.tvGender.text.isNotEmpty())
            binding.tvGender.visibility = View.VISIBLE
        binding.btnNext.text = "Next >>"
        updateActionButton(sessionOrder[position])
    }

    private fun updateActionButton(cardIdx: Int) {
        if (reviewingWrongs) {
            val removed = !WrongCardStore.load(this).contains(
                if (cardIdx < globalWrongIndices.size) globalWrongIndices[cardIdx] else cardIdx
            )
            binding.btnWrong.text = if (removed) "Removed" else "Unmark"
            binding.btnWrong.alpha = if (removed) 0.4f else 1.0f
            binding.btnWrong.isEnabled = isRevealed && !removed
        } else {
            val isMarked = allCards[cardIdx].markedWrong
            binding.btnWrong.text = if (isMarked) "Marked" else "Mark"
            binding.btnWrong.alpha = if (isMarked) 0.5f else 1.0f
            binding.btnWrong.isEnabled = isRevealed && !isMarked
        }
    }

    private fun showRow(row: View, tv: android.widget.TextView, text: String) {
        if (text.isBlank()) {
            row.visibility = View.GONE
        } else {
            row.visibility = View.VISIBLE
            tv.text = text
        }
    }

    private fun showFinished() {
        binding.cardLayout.visibility = View.GONE
        binding.finishedLayout.visibility = View.VISIBLE
        binding.tvFinishedLabel.text =
            if (reviewingWrongs) "Review complete!" else "All cards done!"

        val wrongCount = wrongIndices.size
        if (wrongCount > 0) {
            binding.tvFinishedWrong.text = "$wrongCount card(s) marked for review"
            binding.tvFinishedWrong.visibility = View.VISIBLE
            binding.btnReviewWrong2.visibility = View.VISIBLE
            binding.btnReviewWrong2.setOnClickListener {
                reviewingWrongs = true
                sessionOrder.clear()
                sessionOrder.addAll(wrongIndices.sorted())
                position = 0
                wrongIndices.clear()
                allCards.forEach { it.markedWrong = false }
                showCard()
            }
        } else {
            binding.tvFinishedWrong.visibility = View.GONE
            binding.btnReviewWrong2.visibility = View.GONE
        }

        binding.btnRestartAll.setOnClickListener {
            reviewingWrongs = false
            sessionOrder.clear()
            sessionOrder.addAll(allCards.indices)
            position = 0
            wrongIndices.clear()
            allCards.forEach { it.markedWrong = false }
            showCard()
        }
    }
}
