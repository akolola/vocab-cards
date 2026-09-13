package com.fotonotix.vocabcards

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface VocabCardDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(cards: List<VocabCardEntity>)

    @Query("SELECT * FROM vocab_cards ORDER BY id ASC")
    suspend fun getAll(): List<VocabCardEntity>

    @Query("SELECT * FROM vocab_cards WHERE archived = 0 AND learned = 0 ORDER BY id ASC")
    suspend fun getLearning(): List<VocabCardEntity>

    @Query("SELECT * FROM vocab_cards WHERE archived = 0 AND learned = 1 ORDER BY id ASC")
    suspend fun getLearned(): List<VocabCardEntity>

    @Query("SELECT * FROM vocab_cards WHERE archived = 0 AND marked_wrong = 1 ORDER BY id ASC")
    suspend fun getWrong(): List<VocabCardEntity>

    @Query("SELECT COUNT(*) FROM vocab_cards WHERE archived = 0")
    fun countFlow(): Flow<Int>

    @Query("SELECT COUNT(*) FROM vocab_cards")
    suspend fun countAll(): Int

    @Query("SELECT COUNT(*) FROM vocab_cards WHERE archived = 1")
    suspend fun countArchived(): Int

    @Query("UPDATE vocab_cards SET marked_wrong = :wrong WHERE id = :id")
    suspend fun setWrong(id: Int, wrong: Boolean)

    @Query("UPDATE vocab_cards SET learned = :learned WHERE id = :id")
    suspend fun setLearned(id: Int, learned: Boolean)

    @Query("UPDATE vocab_cards SET marked_wrong = 0 WHERE archived = 0")
    suspend fun clearAllWrong()

    @Query("UPDATE vocab_cards SET learned = 0 WHERE archived = 0")
    suspend fun clearAllLearned()

    @Query("UPDATE vocab_cards SET archived = 1, learned = 0 WHERE learned = 1")
    suspend fun archiveAllLearned()

    @Query("DELETE FROM vocab_cards WHERE archived = 0")
    suspend fun clearActive()

    @Query("DELETE FROM vocab_cards")
    suspend fun clearAll()
}
