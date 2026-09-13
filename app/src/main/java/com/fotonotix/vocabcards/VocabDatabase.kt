package com.fotonotix.vocabcards

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

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
            ).build().also { INSTANCE = it }
        }
    }
}
