package com.fotonotix.vocabcards

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "vocab_cards")
data class VocabCardEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val word: String,
    val russian: String,
    val gender: String,
    val extra: String,
    val section: String,
    val subsection: String,
    val learned: Boolean = false,
    @ColumnInfo(name = "marked_wrong") val markedWrong: Boolean = false,
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis()
) {
    fun toCard() = VocabCard(
        id          = id,
        gender      = gender,
        word        = word,
        russian     = russian,
        extra       = extra,
        section     = section,
        subsection  = subsection,
        markedWrong = markedWrong,
        learned     = learned
    )
}

fun VocabCard.toEntity() = VocabCardEntity(
    word       = word,
    russian    = russian,
    gender     = gender,
    extra      = extra,
    section    = section,
    subsection = subsection
)
