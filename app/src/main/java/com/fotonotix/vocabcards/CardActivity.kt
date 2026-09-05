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
        const val EXTRA_CARD_INDICES  = "extra_card_indices"
        const val EXTRA_LEARNED_MODE  = "extra_learned_mode"
    }

    private lateinit var binding: ActivityCardBinding
    private lateinit var allCards: ArrayList<VocabCard>

    private val sessionOrder = mutableListOf<Int>()
    private var position = 0

    private val wrongIndices = mutableSetOf<Int>()
    private var reviewingWrongs = false
    private var learnedMode = false
    private var isRevealed = false

    private var globalWrongIndices = intArrayOf()
    private var cardGlobalIndices  = intArrayOf()

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

        reviewingWrongs    = intent.getBooleanExtra(EXTRA_WRONG_ONLY, false)
        learnedMode        = intent.getBooleanExtra(EXTRA_LEARNED_MODE, false)
        globalWrongIndices = intent.getIntArrayExtra(EXTRA_WRONG_INDICES) ?: intArrayOf()
        cardGlobalIndices  = intent.getIntArrayExtra(EXTRA_CARD_INDICES)  ?: intArrayOf()

        sessionOrder.addAll(allCards.indices)
        showCard()

        binding.btnNext.setOnClickListener {
            if (isRevealed) advance(+1) else revealWord()
        }
        binding.btnPrev.setOnClickListener { advance(-1) }

        binding.btnWrong.setOnClickListener {
            if (reviewingWrongs) unmarkCurrentCard()
            else markCurrentCardWrong()
        }

        binding.btnLearned.setOnClickListener {
            if (!isRevealed) return@setOnClickListener
            if (learnedMode) unmarkCurrentCardLearned()
            else markCurrentCardLearned()
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
        val globalIdx = if (sessionIdx < globalWrongIndices.size)
            globalWrongIndices[sessionIdx] else sessionIdx

        val persisted = WrongCardStore.load(this).toMutableSet()
        persisted.remove(globalIdx)
        WrongCardStore.save(this, persisted)

        allCards[sessionIdx].markedWrong = false
        updateActionButton(sessionIdx)
        binding.btnWrong.text = "Removed"
        binding.btnWrong.isEnabled = false
        binding.btnWrong.alpha = 0.4f
    }

    private fun markCurrentCardLearned() {
        val localIdx  = sessionOrder[position]
        val globalIdx = if (localIdx < cardGlobalIndices.size) cardGlobalIndices[localIdx] else localIdx
        val persisted = LearnedCardStore.load(this).toMutableSet()
        persisted.add(globalIdx)
        LearnedCardStore.save(this, persisted)

        sessionOrder.removeAt(position)
        if (sessionOrder.isEmpty()) { showFinished(allMastered = true); return }
        if (position >= sessionOrder.size) position = 0
        showCard()
    }

    private fun unmarkCurrentCardLearned() {
        val localIdx  = sessionOrder[position]
        val globalIdx = if (localIdx < cardGlobalIndices.size) cardGlobalIndices[localIdx] else localIdx
        val persisted = LearnedCardStore.load(this).toMutableSet()
        persisted.remove(globalIdx)
        LearnedCardStore.save(this, persisted)

        sessionOrder.removeAt(position)
        if (sessionOrder.isEmpty()) { showFinished(); return }
        if (position >= sessionOrder.size) position = 0
        showCard()
    }

    private fun advance(dir: Int) {
        val next = position + dir
        when {
            next < 0 -> return
            next >= sessionOrder.size -> {
                if (reviewingWrongs) showFinished()
                else { position = 0; showCard() }
            }
            else -> { position = next; showCard() }
        }
    }

    private fun showCard() {
        val cardIdx = sessionOrder[position]
        val card = allCards[cardIdx]

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

        binding.tvSubsection.text = card.subsection
        binding.tvSubsection.visibility =
            if (card.subsection.isBlank()) View.GONE else View.VISIBLE

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
            binding.tvGender.text = ""
            binding.tvGender.visibility = View.GONE
        }

        binding.tvArticle.text = card.article
        binding.tvArticle.visibility =
            if (card.article.isEmpty()) View.GONE else View.INVISIBLE

        showRow(binding.rowRussian, binding.tvRussian, card.russian)
        showRow(binding.rowExtra,   binding.tvExtra,   card.extra)

        binding.btnNext.text = "Show"
        binding.btnWrong.isEnabled = false
        binding.btnWrong.alpha = 0.3f

        // Learned button visibility and state
        when {
            reviewingWrongs -> {
                binding.btnLearned.visibility = View.GONE
            }
            learnedMode -> {
                binding.btnLearned.visibility = View.VISIBLE
                binding.btnLearned.text = "Unlearn"
                binding.btnLearned.isEnabled = false
                binding.btnLearned.alpha = 0.3f
            }
            else -> {
                binding.btnLearned.visibility = View.VISIBLE
                binding.btnLearned.text = "Learned"
                binding.btnLearned.isEnabled = false
                binding.btnLearned.alpha = 0.3f
            }
        }

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

        if (!reviewingWrongs) {
            binding.btnLearned.isEnabled = true
            binding.btnLearned.alpha = 1.0f
        }
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

    private fun showFinished(allMastered: Boolean = false) {
        binding.cardLayout.visibility = View.GONE
        binding.finishedLayout.visibility = View.VISIBLE
        binding.tvFinishedLabel.text = when {
            allMastered     -> "All cards mastered!"
            learnedMode     -> "Session complete!"
            reviewingWrongs -> "Review complete!"
            else            -> "All cards done!"
        }

        val wrongCount = wrongIndices.size
        if (wrongCount > 0 && !learnedMode) {
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
    }
}
