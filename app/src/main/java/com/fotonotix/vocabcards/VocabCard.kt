package com.fotonotix.vocabcards

import java.io.Serializable

data class VocabCard(
    val gender: String,      // m / f / n / pl / "" (verbs/adjectives have no gender)
    val word: String,        // German word (col B)
    val russian: String,     // Russian translation (col C)
    val extra: String,       // All columns D, E, F… joined with "  |  "
    val section: String,     // "Neu" / "Alt" / etc.
    val subsection: String,  // "Substantive" / "Verben" / "Adjektive" / etc.
    var markedWrong: Boolean = false
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
