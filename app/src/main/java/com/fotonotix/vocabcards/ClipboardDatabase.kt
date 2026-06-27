package com.fotonotix.vocabcards

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [ClipboardWord::class], version = 1)
abstract class ClipboardDatabase : RoomDatabase() {
    abstract fun dao(): ClipboardDao

    companion object {
        @Volatile private var INSTANCE: ClipboardDatabase? = null

        fun get(context: Context): ClipboardDatabase = INSTANCE ?: synchronized(this) {
            INSTANCE ?: Room.databaseBuilder(
                context.applicationContext,
                ClipboardDatabase::class.java,
                "clipboard.db"
            ).build().also { INSTANCE = it }
        }
    }
}
