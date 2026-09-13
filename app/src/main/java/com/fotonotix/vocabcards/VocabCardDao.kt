package com.fotonotix.vocabcards

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface VocabCardDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(cards: List<VocabCardEntity>)

    @Query("SELECT * FROM vocab_cards ORDER BY id ASC")
    suspend fun getAll(): List<VocabCardEntity>

    @Query("SELECT * FROM vocab_cards WHERE learned = 0 ORDER BY id ASC")
    suspend fun getLearning(): List<VocabCardEntity>

    @Query("SELECT * FROM vocab_cards WHERE learned = 1 ORDER BY id ASC")
    suspend fun getLearned(): List<VocabCardEntity>

    @Query("SELECT * FROM vocab_cards WHERE marked_wrong = 1 ORDER BY id ASC")
    suspend fun getWrong(): List<VocabCardEntity>

    @Query("SELECT COUNT(*) FROM vocab_cards")
    fun countFlow(): Flow<Int>

    @Query("UPDATE vocab_cards SET marked_wrong = :wrong WHERE id = :id")
    suspend fun setWrong(id: Int, wrong: Boolean)

    @Query("UPDATE vocab_cards SET learned = :learned WHERE id = :id")
    suspend fun setLearned(id: Int, learned: Boolean)

    @Query("UPDATE vocab_cards SET marked_wrong = 0")
    suspend fun clearAllWrong()

    @Query("UPDATE vocab_cards SET learned = 0")
    suspend fun clearAllLearned()

    @Query("DELETE FROM vocab_cards")
    suspend fun clearAll()
}
