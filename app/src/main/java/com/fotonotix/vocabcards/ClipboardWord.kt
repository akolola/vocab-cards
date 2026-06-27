package com.fotonotix.vocabcards

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "clipboard_words")
data class ClipboardWord(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val text: String,
    val isRussian: Boolean,
    val createdAt: Long = System.currentTimeMillis()
)
