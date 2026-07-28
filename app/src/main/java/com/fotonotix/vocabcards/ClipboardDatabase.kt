package com.fotonotix.vocabcards

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [ClipboardWord::class], version = 2)
abstract class ClipboardDatabase : RoomDatabase() {
    abstract fun dao(): ClipboardDao

    companion object {
        @Volatile private var INSTANCE: ClipboardDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE clipboard_words ADD COLUMN isOld INTEGER NOT NULL DEFAULT 0")
            }
        }

        fun get(context: Context): ClipboardDatabase = INSTANCE ?: synchronized(this) {
            INSTANCE ?: Room.databaseBuilder(
                context.applicationContext,
                ClipboardDatabase::class.java,
                "clipboard.db"
            ).addMigrations(MIGRATION_1_2).build().also { INSTANCE = it }
        }
    }
}
