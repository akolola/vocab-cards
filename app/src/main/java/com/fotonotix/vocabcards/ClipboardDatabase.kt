package com.fotonotix.vocabcards

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [ClipboardWord::class], version = 3)
abstract class ClipboardDatabase : RoomDatabase() {
    abstract fun dao(): ClipboardDao

    companion object {
        @Volatile private var INSTANCE: ClipboardDatabase? = null

        // 1→2 added isOld column; 2→3 removes it (recreate table)
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE clipboard_words ADD COLUMN isOld INTEGER NOT NULL DEFAULT 0")
            }
        }
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""CREATE TABLE clipboard_words_new (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    text TEXT NOT NULL,
                    isRussian INTEGER NOT NULL,
                    createdAt INTEGER NOT NULL)""")
                db.execSQL("INSERT INTO clipboard_words_new SELECT id, text, isRussian, createdAt FROM clipboard_words")
                db.execSQL("DROP TABLE clipboard_words")
                db.execSQL("ALTER TABLE clipboard_words_new RENAME TO clipboard_words")
            }
        }

        fun get(context: Context): ClipboardDatabase = INSTANCE ?: synchronized(this) {
            INSTANCE ?: Room.databaseBuilder(
                context.applicationContext,
                ClipboardDatabase::class.java,
                "clipboard.db"
            ).addMigrations(MIGRATION_1_2, MIGRATION_2_3).build().also { INSTANCE = it }
        }
    }
}
