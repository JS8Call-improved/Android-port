package com.js8call.example.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Room database for storing JS8 messages and heard-station contacts.
 */
@Database(
    entities = [MessageEntity::class, ContactEntity::class],
    version = 3,
    exportSchema = false
)
abstract class MessageDatabase : RoomDatabase() {

    abstract fun messageDao(): MessageDao

    abstract fun contactDao(): ContactDao

    companion object {
        private const val DATABASE_NAME = "js8_messages.db"

        @Volatile
        private var INSTANCE: MessageDatabase? = null

        fun getInstance(context: Context): MessageDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: buildDatabase(context).also { INSTANCE = it }
            }
        }

        // Adding the contacts table must not wipe stored messages.
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `contacts` (
                        `callsign` TEXT NOT NULL,
                        `lastHeard` INTEGER NOT NULL,
                        `snr` INTEGER,
                        `offset` REAL,
                        `grid` TEXT,
                        `heardUs` INTEGER NOT NULL DEFAULT 0,
                        `starred` INTEGER NOT NULL DEFAULT 0,
                        `comment` TEXT,
                        PRIMARY KEY(`callsign`)
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_contacts_lastHeard` ON `contacts` (`lastHeard`)")
            }
        }

        private fun buildDatabase(context: Context): MessageDatabase {
            return Room.databaseBuilder(
                context.applicationContext,
                MessageDatabase::class.java,
                DATABASE_NAME
            )
                .addMigrations(MIGRATION_2_3)
                .fallbackToDestructiveMigration()
                .build()
        }
    }
}
