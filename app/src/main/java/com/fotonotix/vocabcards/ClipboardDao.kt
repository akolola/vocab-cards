package com.fotonotix.vocabcards

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ClipboardDao {
    @Insert
    suspend fun insert(word: ClipboardWord)

    @Query("SELECT * FROM clipboard_words WHERE isOld = 0 ORDER BY createdAt ASC")
    suspend fun getNew(): List<ClipboardWord>

    @Query("SELECT * FROM clipboard_words WHERE isOld = 1 ORDER BY createdAt ASC")
    suspend fun getOld(): List<ClipboardWord>

    @Query("SELECT COUNT(*) FROM clipboard_words WHERE isOld = 0")
    fun countNewFlow(): Flow<Int>

    @Query("SELECT COUNT(*) FROM clipboard_words WHERE isOld = 1")
    fun countOldFlow(): Flow<Int>

    @Query("DELETE FROM clipboard_words")
    suspend fun clearAll()
}
