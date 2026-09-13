package com.fotonotix.vocabcards

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Database(entities = [VocabCardEntity::class], version = 1)
abstract class VocabDatabase : RoomDatabase() {

    abstract fun dao(): VocabCardDao

    companion object {
        @Volatile private var INSTANCE: VocabDatabase? = null

        fun get(context: Context): VocabDatabase = INSTANCE ?: synchronized(this) {
            INSTANCE ?: Room.databaseBuilder(
                context.applicationContext,
                VocabDatabase::class.java,
                "vocab_db"
            ).build().also { db ->
                INSTANCE = db
                CoroutineScope(Dispatchers.IO).launch {
                    if (db.dao().countAll() == 0) {
                        db.dao().insertAll(SeedData.cards)
                    }
                }
            }
        }
    }
}
