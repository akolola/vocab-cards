package com.fotonotix.vocabcards

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Database(entities = [VocabCardEntity::class], version = 2, exportSchema = false)
abstract class VocabDatabase : RoomDatabase() {

    abstract fun dao(): VocabCardDao

    companion object {
        @Volatile private var INSTANCE: VocabDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE vocab_cards ADD COLUMN archived INTEGER NOT NULL DEFAULT 0")
            }
        }

        fun get(context: Context): VocabDatabase = INSTANCE ?: synchronized(this) {
            INSTANCE ?: Room.databaseBuilder(
                context.applicationContext,
                VocabDatabase::class.java,
                "vocab_db"
            )
            .addMigrations(MIGRATION_1_2)
            .build().also { db ->
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
