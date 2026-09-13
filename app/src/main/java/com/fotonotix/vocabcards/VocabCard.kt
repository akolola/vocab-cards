package com.fotonotix.vocabcards

import java.io.Serializable

data class VocabCard(
    val id: Int = 0,             // DB primary key (0 = not yet persisted)
    val gender: String,          // m / f / n / pl / ""
    val word: String,            // German (col B)
    val russian: String,         // Russian (col C)
    val extra: String,           // Cols D+ joined
    val section: String,         // Neu / Alt / etc.
    val subsection: String,      // Substantiv / Verb / etc.
    var markedWrong: Boolean = false,
    var learned: Boolean = false
) : Serializable {

    val article: String get() = when (gender.lowercase()) {
        "m"  -> "der"
        "f"  -> "die"
        "n"  -> "das"
        "pl" -> "die (pl.)"
        else -> ""
    }

    val displayWord: String get() = if (article.isNotEmpty()) "$article $word" else word

    val isEmpty: Boolean get() = word.isBlank()
}
