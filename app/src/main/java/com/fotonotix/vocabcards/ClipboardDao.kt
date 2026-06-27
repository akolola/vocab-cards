package com.fotonotix.vocabcards

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ClipboardDao {
    @Insert
    suspend fun insert(word: ClipboardWord)

    @Query("SELECT * FROM clipboard_words ORDER BY createdAt ASC")
    suspend fun getAll(): List<ClipboardWord>

    @Query("SELECT COUNT(*) FROM clipboard_words")
    fun countFlow(): Flow<Int>

    @Query("DELETE FROM clipboard_words")
    suspend fun clearAll()
}
